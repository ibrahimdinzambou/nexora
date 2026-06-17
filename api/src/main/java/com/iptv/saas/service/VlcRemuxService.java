package com.iptv.saas.service;

import com.iptv.saas.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class VlcRemuxService {
    private static final String TS_OUTPUT = "--sout=#standard{access=file,mux=ts,dst=-}";

    private final String vlcCommand;
    private final Duration startupTimeout;
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "vlc-remux-io");
        thread.setDaemon(true);
        return thread;
    });

    public VlcRemuxService(
            @Value("${app.iptv.vlc-path:}") String configuredPath,
            @Value("${app.iptv.vlc-startup-timeout-seconds:45}") long startupTimeoutSeconds
    ) {
        this.vlcCommand = locateVlc(configuredPath);
        this.startupTimeout = Duration.ofSeconds(Math.max(10, startupTimeoutSeconds));
    }

    public boolean requiresRemux(String streamUrl) {
        try {
            String path = URI.create(streamUrl).getPath().toLowerCase(Locale.ROOT);
            return path.endsWith(".mkv");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static String normalizeQuality(String quality) {
        return switch (quality == null ? "" : quality.strip().toLowerCase(Locale.ROOT)) {
            case "data", "hd", "fullhd", "uhd" -> quality.strip().toLowerCase(Locale.ROOT);
            default -> "auto";
        };
    }

    public boolean requiresTranscode(String quality) {
        String normalized = normalizeQuality(quality);
        return "data".equals(normalized) || "hd".equals(normalized) || "fullhd".equals(normalized);
    }

    public boolean isAvailable() {
        return vlcCommand != null;
    }

    public boolean requiresProcessing(String streamUrl, String quality) {
        return requiresRemux(streamUrl) || requiresTranscode(quality);
    }

    public RemuxStream open(InputStream source) {
        return open(source, "auto");
    }

    public RemuxStream open(InputStream source, String quality) {
        if (vlcCommand == null) {
            closeQuietly(source);
            throw ApiException.serviceUnavailable(
                    "Conversion video indisponible: configurez IPTV_VLC_PATH vers l'executable VLC"
            );
        }

        List<String> command = List.of(
                vlcCommand,
                "--intf=dummy",
                "--dummy-quiet",
                "--no-one-instance",
                "--no-video-title-show",
                "--play-and-exit",
                "--network-caching=700",
                "-",
                soutFor(quality),
                "vlc://quit"
        );
        try {
            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            activeProcesses.add(process);
            Future<?> feeder = ioExecutor.submit(() -> feedSource(process, source));
            InputStream processOutput = process.getInputStream();
            Callable<Integer> firstByteTask = processOutput::read;
            Future<Integer> firstByteRead = ioExecutor.submit(firstByteTask);
            int firstByte;
            try {
                firstByte = firstByteRead.get(startupTimeout.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException exception) {
                firstByteRead.cancel(true);
                closeProcess(process, source, feeder, processOutput);
                throw ApiException.serviceUnavailable("Le flux MKV ne demarre pas");
            }
            if (firstByte < 0) {
                closeProcess(process, source, feeder, processOutput);
                throw ApiException.serviceUnavailable("Le flux MKV est vide ou incompatible");
            }
            InputStream body = new SequenceInputStream(
                    new ByteArrayInputStream(new byte[]{(byte) firstByte}),
                    processOutput
            );
            return new RemuxStream(process, source, feeder, body);
        } catch (IOException exception) {
            closeQuietly(source);
            throw ApiException.serviceUnavailable("Impossible de demarrer le lecteur MKV");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            closeQuietly(source);
            throw ApiException.serviceUnavailable("Demarrage du lecteur MKV interrompu");
        }
    }

    private String soutFor(String quality) {
        QualityProfile profile = qualityProfile(quality);
        if (profile == null) {
            return TS_OUTPUT;
        }
        return "--sout=#transcode{vcodec=h264,vb=" + profile.videoBitrateKbps()
                + ",height=" + profile.height()
                + ",acodec=mp4a,ab=" + profile.audioBitrateKbps()
                + ",channels=2,samplerate=48000}:standard{access=file,mux=ts,dst=-}";
    }

    QualityProfile qualityProfile(String quality) {
        return switch (normalizeQuality(quality)) {
            case "data" -> new QualityProfile(480, 1_200, 96);
            case "hd" -> new QualityProfile(720, 2_500, 128);
            case "fullhd" -> new QualityProfile(1080, 5_000, 160);
            default -> null;
        };
    }

    @PreDestroy
    void shutdown() {
        activeProcesses.forEach(Process::destroyForcibly);
        activeProcesses.clear();
        ioExecutor.shutdownNow();
    }

    private void feedSource(Process process, InputStream source) {
        try (source; var input = process.getOutputStream()) {
            source.transferTo(input);
        } catch (IOException exception) {
            process.destroy();
        }
    }

    private void closeProcess(Process process, InputStream source, Future<?> feeder, InputStream body) {
        process.destroy();
        closeQuietly(source);
        closeQuietly(body);
        feeder.cancel(true);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        activeProcesses.remove(process);
    }

    private void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Closing is best-effort while unwinding a failed media stream.
        }
    }

    private String locateVlc(String configuredPath) {
        List<Path> candidates = new ArrayList<>();
        if (configuredPath != null && !configuredPath.isBlank()) {
            candidates.add(Path.of(configuredPath.strip()));
        }
        addInstallCandidate(candidates, System.getenv("ProgramFiles"));
        addInstallCandidate(candidates, System.getenv("ProgramFiles(x86)"));

        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(File.pathSeparator)) {
                if (!directory.isBlank()) {
                    candidates.add(Path.of(directory, isWindows() ? "vlc.exe" : "vlc"));
                }
            }
        }
        return candidates.stream()
                .filter(Files::isRegularFile)
                .map(candidate -> candidate.toAbsolutePath().toString())
                .findFirst()
                .orElse(null);
    }

    private void addInstallCandidate(List<Path> candidates, String programFiles) {
        if (programFiles != null && !programFiles.isBlank()) {
            candidates.add(Path.of(programFiles, "VideoLAN", "VLC", isWindows() ? "vlc.exe" : "vlc"));
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public final class RemuxStream implements AutoCloseable {
        private final Process process;
        private final InputStream source;
        private final Future<?> feeder;
        private final InputStream body;
        private boolean closed;

        private RemuxStream(Process process, InputStream source, Future<?> feeder, InputStream body) {
            this.process = process;
            this.source = source;
            this.feeder = feeder;
            this.body = body;
        }

        public InputStream body() {
            return body;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            process.destroy();
            closeQuietly(source);
            feeder.cancel(true);
            try {
                body.close();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (IOException exception) {
                process.destroyForcibly();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } finally {
                activeProcesses.remove(process);
            }
        }
    }

    record QualityProfile(int height, int videoBitrateKbps, int audioBitrateKbps) {
    }
}
