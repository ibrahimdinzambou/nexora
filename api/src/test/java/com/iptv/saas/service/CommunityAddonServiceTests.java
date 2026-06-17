package com.iptv.saas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.CommunityAddon;
import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.CommunityAddonRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.web.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommunityAddonServiceTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void installsReadsAndResolvesAnApprovedCommunityAddon() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String root = "http://127.0.0.1:" + port;
        json("/manifest.json", """
                {
                  "id": "org.example.free",
                  "name": "Free Cinema",
                  "version": "1.0.0",
                  "description": "Films libres",
                  "catalogs": [{"type": "movie", "id": "free", "name": "Films libres"}]
                }
                """);
        json("/catalog/movie/free.json", """
                {"metas":[{"id":"film-1","type":"movie","name":"Film public","poster":"https://images.example/poster.jpg"}]}
                """);
        json("/meta/movie/film-1.json", """
                {"meta":{"id":"film-1","type":"movie","name":"Film public","description":"Domaine public"}}
                """);
        json("/stream/movie/film-1.json", """
                {"streams":[{"url":"%s/video.mp4"}]}
                """.formatted(root));
        server.start();

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        doNothing().when(images).rewrite(any());
        when(repository.findByManifestUrl(root + "/manifest.json")).thenReturn(Optional.empty());
        when(repository.save(any(CommunityAddon.class))).thenAnswer(invocation -> {
            CommunityAddon addon = invocation.getArgument(0);
            if (addon.id == null) addon.id = 7L;
            return addon;
        });

        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );
        CommunityAddon addon = service.install(
                root + "/manifest.json",
                "127.0.0.1",
                "Public Domain",
                root + "/license",
                false
        );
        addon.status = Enums.AddonStatus.APPROVED;
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        when(repository.findById(7L)).thenReturn(Optional.of(addon));

        List<Map<String, Object>> items = service.items("movie", null, null, "title-asc", 20);
        String publicId = String.valueOf(items.get(0).get("id"));

        assertEquals("Film public", items.get(0).get("name"));
        assertTrue(publicId.startsWith("addon~7~movie~"));
        assertEquals("Domaine public", service.itemInfo(publicId).get("summary"));
        assertEquals(root + "/video.mp4", service.streamUrl(publicId));
    }

    @Test
    void marksAddonItemsWithoutAllowedStreamHostsAsCatalogOnly() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String root = "http://127.0.0.1:" + port;
        json("/manifest.json", """
                {
                  "id": "org.example.blocked",
                  "name": "Blocked Cinema",
                  "catalogs": [{"type": "movie", "id": "blocked", "name": "Blocked"}]
                }
                """);
        json("/catalog/movie/blocked.json", """
                {"metas":[{"id":"film-1","type":"movie","name":"Blocked movie"}]}
                """);
        json("/meta/movie/film-1.json", """
                {"meta":{"id":"film-1","type":"movie","name":"Blocked movie","description":"Metadata only"}}
                """);
        json("/stream/movie/film-1.json", """
                {"streams":[{"url":"https://blocked.example/video.mp4"}]}
                """);
        server.start();

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        doNothing().when(images).rewrite(any());
        when(repository.findByManifestUrl(root + "/manifest.json")).thenReturn(Optional.empty());
        when(repository.save(any(CommunityAddon.class))).thenAnswer(invocation -> {
            CommunityAddon addon = invocation.getArgument(0);
            addon.id = 8L;
            return addon;
        });

        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );
        CommunityAddon addon = service.install(
                root + "/manifest.json",
                "127.0.0.1",
                "Public Domain",
                root + "/license",
                false
        );
        addon.status = Enums.AddonStatus.APPROVED;
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        when(repository.findById(8L)).thenReturn(Optional.of(addon));

        String publicId = String.valueOf(service.items("movie", null, null, "title-asc", 20).get(0).get("id"));
        Map<String, Object> item = service.itemInfo(publicId);
        ApiException exception = assertThrows(ApiException.class, () -> service.streamUrl(publicId));

        assertEquals(false, item.get("streamAvailable"));
        assertEquals(
                "Aucun flux ne correspond aux domaines autorises: blocked.example",
                item.get("streamUnavailableReason")
        );
        assertEquals(503, exception.status().value());
    }

    @Test
    void wildcardAllowedStreamHostsAllowsAnyAddonStreamHost() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String root = "http://127.0.0.1:" + port;
        json("/manifest.json", """
                {
                  "id": "org.example.dynamic",
                  "name": "Dynamic CDN",
                  "catalogs": [{"type": "movie", "id": "dynamic", "name": "Dynamic"}]
                }
                """);
        json("/catalog/movie/dynamic.json", """
                {"metas":[{"id":"film-1","type":"movie","name":"Dynamic movie"}]}
                """);
        json("/stream/movie/film-1.json", """
                {"streams":[{"url":"https://dynamic-cdn.example/video.m3u8"}]}
                """);
        server.start();

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        doNothing().when(images).rewrite(any());
        when(repository.findByManifestUrl(root + "/manifest.json")).thenReturn(Optional.empty());
        when(repository.save(any(CommunityAddon.class))).thenAnswer(invocation -> {
            CommunityAddon addon = invocation.getArgument(0);
            addon.id = 18L;
            return addon;
        });

        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );
        CommunityAddon addon = service.install(
                root + "/manifest.json",
                "*",
                "Public Domain",
                root + "/license",
                false
        );
        addon.status = Enums.AddonStatus.APPROVED;
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        when(repository.findById(18L)).thenReturn(Optional.of(addon));

        String publicId = String.valueOf(service.items("movie", null, null, "title-asc", 20).get(0).get("id"));

        assertEquals("*", addon.allowedStreamHosts);
        assertEquals("https://dynamic-cdn.example/video.m3u8", service.streamUrl(publicId));
    }

    @Test
    void installsStreamOnlyAddonWithoutCatalogs() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String root = "http://127.0.0.1:" + port;
        json("/manifest.json", """
                {
                  "id": "org.example.stream-only",
                  "name": "Stream Only",
                  "types": ["movie", "series"],
                  "resources": ["stream"],
                  "idPrefixes": ["tt"],
                  "catalogs": []
                }
                """);
        server.start();

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        when(repository.findByManifestUrl(root + "/manifest.json")).thenReturn(Optional.empty());
        when(repository.save(any(CommunityAddon.class))).thenAnswer(invocation -> {
            CommunityAddon addon = invocation.getArgument(0);
            addon.id = 9L;
            return addon;
        });

        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );
        CommunityAddon addon = service.install(
                root + "/manifest.json",
                "127.0.0.1",
                "Test",
                root + "/license",
                false
        );
        addon.status = Enums.AddonStatus.APPROVED;
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));

        assertEquals("Stream Only", addon.name);
        assertTrue(service.categories("movie").isEmpty());
        assertEquals(false, service.hasApprovedAddons(null));
    }

    @Test
    void usesApprovedStreamOnlyAddonAsPlaybackFallback() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String root = "http://127.0.0.1:" + port;
        json("/catalog/movie/recent.json", """
                {"metas":[{"id":"tt1234567","type":"movie","name":"Fallback movie"}]}
                """);
        json("/stream/movie/tt1234567.json", """
                {"streams":[{"url":"https://blocked.example/video.mp4"}]}
                """);
        json("/resolver/stream/movie/tt1234567.json", """
                {"streams":[
                  {"url":"%s/large.mkv","behaviorHints":{"notWebReady":true}},
                  {"url":"%s/video.m3u8"}
                ]}
                """.formatted(root, root));
        server.start();

        CommunityAddon catalogAddon = new CommunityAddon();
        catalogAddon.id = 21L;
        catalogAddon.addonKey = "org.example.catalog";
        catalogAddon.name = "Catalog";
        catalogAddon.manifestUrl = root + "/manifest.json";
        catalogAddon.allowedStreamHosts = "allowed.example";
        catalogAddon.status = Enums.AddonStatus.APPROVED;
        catalogAddon.manifestJson = """
                {
                  "id": "org.example.catalog",
                  "name": "Catalog",
                  "types": ["movie"],
                  "resources": ["catalog", "stream"],
                  "catalogs": [{"type":"movie","id":"recent","name":"Recent"}]
                }
                """;

        CommunityAddon resolverAddon = new CommunityAddon();
        resolverAddon.id = 22L;
        resolverAddon.addonKey = "org.example.resolver";
        resolverAddon.name = "Resolver";
        resolverAddon.manifestUrl = root + "/resolver/manifest.json";
        resolverAddon.allowedStreamHosts = "127.0.0.1";
        resolverAddon.status = Enums.AddonStatus.APPROVED;
        resolverAddon.manifestJson = """
                {
                  "id": "org.example.resolver",
                  "name": "Resolver",
                  "types": ["movie"],
                  "resources": ["stream"],
                  "idPrefixes": ["tt"],
                  "catalogs": []
                }
                """;

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        doNothing().when(images).rewrite(any());
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED))
                .thenReturn(List.of(catalogAddon, resolverAddon));
        when(repository.findById(21L)).thenReturn(Optional.of(catalogAddon));

        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );
        String publicId = String.valueOf(service.items("movie", null, null, "title-asc", 20).get(0).get("id"));

        assertEquals(root + "/video.m3u8", service.streamUrl(publicId));
    }

    @Test
    void mapsACustomCatalogTypeToItsSingleDeclaredStremioType() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String root = "http://127.0.0.1:" + port;
        json("/manifest.json", """
                {
                  "id": "org.example.custom",
                  "name": "Custom Cinema",
                  "types": ["custom"],
                  "resources": [
                    {"name": "catalog", "types": ["custom"]},
                    {"name": "meta", "types": ["movie"]},
                    {"name": "stream", "types": ["movie"]}
                  ],
                  "catalogs": [{"type": "porn", "id": "recent", "name": "Recent"}]
                }
                """);
        json("/catalog/porn/recent.json", """
                {"metas":[{"id":"remote-1","type":"movie","name":"Mapped movie"}]}
                """);
        server.start();

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        doNothing().when(images).rewrite(any());
        when(repository.findByManifestUrl(root + "/manifest.json")).thenReturn(Optional.empty());
        when(repository.save(any(CommunityAddon.class))).thenAnswer(invocation -> {
            CommunityAddon addon = invocation.getArgument(0);
            addon.id = 11L;
            return addon;
        });

        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );
        CommunityAddon addon = service.install(
                root + "/manifest.json",
                "127.0.0.1",
                "Public Domain",
                root + "/license",
                true
        );
        addon.status = Enums.AddonStatus.APPROVED;
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        when(repository.findById(11L)).thenReturn(Optional.of(addon));

        List<Map<String, Object>> categories = service.categories("movie");
        List<Map<String, Object>> items = service.items("movie", null, null, "title-asc", 20);

        assertEquals(1, categories.size());
        assertEquals("movie", categories.get(0).get("type"));
        assertEquals("Mapped movie", items.get(0).get("name"));
        assertTrue(String.valueOf(items.get(0).get("id")).startsWith("addon~11~movie~"));
    }

    @Test
    void mapsPornCatalogsToMoviesAndUsesTheRemoteTypeForResources() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        json("/manifest.json", """
                {
                  "id":"org.example.porn",
                  "name":"Private catalog",
                  "types":["Porn"],
                  "resources":["catalog","meta","stream"],
                  "catalogs":[{
                    "type":"Porn",
                    "id":"recent",
                    "name":"Recent",
                    "extra":[{"name":"search"},{"name":"skip"}]
                  }]
                }
                """);
        json("/catalog/Porn/recent.json", """
                {"metas":[{"id":"remote-1","type":"Porn","name":"Mapped title"}]}
                """);
        json("/meta/Porn/remote-1.json", """
                {"meta":{"id":"remote-1","type":"Porn","name":"Mapped title","description":"Remote metadata"}}
                """);
        json("/stream/Porn/remote-1.json", """
                {"streams":[{"url":"%s/video.mp4"}]}
                """.formatted(root));
        server.start();

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        doNothing().when(images).rewrite(any());
        when(repository.findByManifestUrl(root + "/manifest.json")).thenReturn(Optional.empty());
        when(repository.save(any(CommunityAddon.class))).thenAnswer(invocation -> {
            CommunityAddon addon = invocation.getArgument(0);
            addon.id = 18L;
            return addon;
        });
        CommunityAddonService service = new CommunityAddonService(
                repository, users, images, new ObjectMapper(), torrentResolver,
                262_144, 300, true
        );
        CommunityAddon addon = service.install(
                root + "/manifest.json", "127.0.0.1", "Test", root + "/license", true
        );
        addon.status = Enums.AddonStatus.APPROVED;
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        when(repository.findById(18L)).thenReturn(Optional.of(addon));

        String itemId = String.valueOf(service.items("movie", null, null, "title-asc", 20).get(0).get("id"));

        assertEquals("Remote metadata", service.itemInfo(itemId).get("summary"));
        assertEquals(root + "/video.mp4", service.streamUrl(itemId));
    }

    @Test
    void exposesMixedMarvelCatalogsAndUsesCatalogDataWhenMetaIsMissing() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        json("/manifest.json", """
                {
                  "id":"org.example.marvel",
                  "name":"Marvel",
                  "types":["movie","series"],
                  "resources":["catalog"],
                  "catalogs":[{"type":"Marvel","id":"marvel-mcu","name":"MCU"}]
                }
                """);
        json("/catalog/Marvel/marvel-mcu.json", """
                {"metas":[
                  {"id":"movie-1","type":"movie","name":"Movie","description":"Movie summary"},
                  {"id":"series-1","type":"series","name":"Series","description":"Series summary"}
                ]}
                """);
        server.start();

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        doNothing().when(images).rewrite(any());
        when(repository.findByManifestUrl(root + "/manifest.json")).thenReturn(Optional.empty());
        when(repository.save(any(CommunityAddon.class))).thenAnswer(invocation -> {
            CommunityAddon addon = invocation.getArgument(0);
            addon.id = 19L;
            return addon;
        });
        CommunityAddonService service = new CommunityAddonService(
                repository, users, images, new ObjectMapper(), torrentResolver,
                262_144, 300, true
        );
        CommunityAddon addon = service.install(
                root + "/manifest.json", "127.0.0.1", "Test", root + "/license", false
        );
        addon.status = Enums.AddonStatus.APPROVED;
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        when(repository.findById(19L)).thenReturn(Optional.of(addon));

        List<Map<String, Object>> movies = service.items("movie", null, null, "title-asc", 20);
        List<Map<String, Object>> series = service.items("series", null, null, "title-asc", 20);
        String movieId = String.valueOf(movies.get(0).get("id"));

        assertEquals(1, movies.size());
        assertEquals(1, series.size());
        assertEquals("Movie summary", service.itemInfo(movieId).get("summary"));
        assertEquals(false, movies.get(0).get("streamAvailable"));
        assertThrows(ApiException.class, () -> service.streamUrl(movieId));
    }

    @Test
    void retriesTransientAddonFailuresWhenLoadingMetadata() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String root = "http://127.0.0.1:" + port;
        AtomicInteger metaRequests = new AtomicInteger();
        json("/catalog/movie/recent.json", """
                {"metas":[{"id":"film-1","type":"movie","name":"Transient movie"}]}
                """);
        server.createContext("/meta/movie/film-1.json", exchange -> {
            if (metaRequests.incrementAndGet() < 3) {
                respond(exchange, 503, "{\"error\":\"temporary\"}");
                return;
            }
            respond(exchange, 200, """
                    {"meta":{"id":"film-1","type":"movie","name":"Transient movie","description":"Available"}}
                    """);
        });
        server.start();

        CommunityAddon addon = new CommunityAddon();
        addon.id = 12L;
        addon.name = "Transient";
        addon.manifestUrl = root + "/manifest.json";
        addon.allowedStreamHosts = "127.0.0.1";
        addon.status = Enums.AddonStatus.APPROVED;
        addon.manifestJson = """
                {"catalogs":[{"type":"movie","id":"recent","name":"Recent"}]}
                """;

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        doNothing().when(images).rewrite(any());
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        when(repository.findById(12L)).thenReturn(Optional.of(addon));
        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );

        String itemId = String.valueOf(service.items("movie", null, null, "title-asc", 20).get(0).get("id"));

        assertEquals("Available", service.itemInfo(itemId).get("summary"));
        assertEquals(3, metaRequests.get());
    }

    @Test
    void opensSeriesWhenAddonMetadataOmitsTheMetaId() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        json("/catalog/series/top_kdramas_2025.json", """
                {"metas":[{"id":"tt37600136","type":"series","name":"Bon Appetit, Your Majesty"}]}
                """);
        json("/meta/series/tt37600136.json", """
                {"meta":{
                  "type":"series",
                  "name":"Bon Appetit, Your Majesty",
                  "description":"Royal kitchen drama",
                  "videos":[{"id":"tt37600136:1:1","season":1,"episode":1,"title":"Episode 1"}]
                }}
                """);
        server.start();

        CommunityAddon addon = new CommunityAddon();
        addon.id = 71L;
        addon.name = "K-Dramas";
        addon.manifestUrl = root + "/manifest.json";
        addon.status = Enums.AddonStatus.APPROVED;
        addon.manifestJson = """
                {
                  "types":["series"],
                  "resources":["catalog","meta","stream"],
                  "catalogs":[{"type":"series","id":"top_kdramas_2025","name":"Top K-Dramas 2025"}]
                }
                """;

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        doNothing().when(images).rewrite(any());
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        when(repository.findById(71L)).thenReturn(Optional.of(addon));
        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );

        String itemId = String.valueOf(service.items("series", null, null, "title-asc", 20).get(0).get("id"));
        Map<String, Object> series = service.seriesInfo(itemId);

        assertEquals(itemId, series.get("id"));
        assertEquals("Bon Appetit, Your Majesty", series.get("name"));
        assertEquals("Royal kitchen drama", series.get("summary"));
        assertEquals(1, series.get("episodeCount"));
    }

    @Test
    void opensSeriesFromCatalogWhenAddonMetadataIsEmpty() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        json("/catalog/series/J-Dramas.json", """
                {"metas":[{"id":"315209","type":"series","name":"Canned Mackerel Heads to Space","description":"Catalog summary"}]}
                """);
        json("/meta/series/315209.json", """
                {"meta":null}
                """);
        server.start();

        CommunityAddon addon = new CommunityAddon();
        addon.id = 71L;
        addon.name = "J-Dramas";
        addon.manifestUrl = root + "/manifest.json";
        addon.status = Enums.AddonStatus.APPROVED;
        addon.manifestJson = """
                {
                  "types":["series"],
                  "resources":["catalog","meta","stream"],
                  "catalogs":[{"type":"series","id":"J-Dramas","name":"J-Dramas"}]
                }
                """;

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        doNothing().when(images).rewrite(any());
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        when(repository.findById(71L)).thenReturn(Optional.of(addon));
        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );

        String itemId = String.valueOf(service.items("series", null, null, "title-asc", 20).get(0).get("id"));
        Map<String, Object> series = service.seriesInfo(itemId);

        assertEquals(itemId, series.get("id"));
        assertEquals("Canned Mackerel Heads to Space", series.get("name"));
        assertEquals("Catalog summary", series.get("summary"));
    }

    @Test
    void appliesCatalogFiltersAndLoadsAdditionalStremioPages() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        List<String> requestedPaths = new CopyOnWriteArrayList<>();
        server.createContext("/catalog/porn/studio/", exchange -> {
            String rawPath = exchange.getRequestURI().getRawPath();
            requestedPaths.add(rawPath);
            respond(exchange, rawPath.contains("skip=24")
                    ? catalogResponse("second", 2)
                    : catalogResponse("first", 24));
        });
        server.start();

        CommunityAddon addon = new CommunityAddon();
        addon.id = 13L;
        addon.name = "Filtered";
        addon.manifestUrl = root + "/manifest.json";
        addon.status = Enums.AddonStatus.APPROVED;
        addon.manifestJson = """
                {
                  "types":["movie"],
                  "catalogs":[{
                    "type":"porn",
                    "id":"studio",
                    "name":"Studio",
                    "extra":[
                      {"name":"studio","isRequired":true,"options":["All","Adult Prime"]},
                      {"name":"skip"}
                    ]
                  }]
                }
                """;

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        doNothing().when(images).rewrite(any());
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );
        Map<String, Object> category = service.categories("movie").get(0);

        List<Map<String, Object>> items = service.items(
                "movie",
                null,
                String.valueOf(category.get("id")),
                "title-asc",
                0,
                "Adult Prime",
                2,
                null
        );

        assertEquals(List.of("All", "Adult Prime"), category.get("filterOptions"));
        assertEquals(26, items.size());
        assertTrue(requestedPaths.stream().anyMatch(path -> path.contains("studio=Adult%20Prime")));
        assertTrue(requestedPaths.stream().anyMatch(path -> path.contains("skip=24")));
    }

    @Test
    void replacesTheManifestUrlWhenAnAddonIsReconfigured() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        json("/configured/manifest.json", """
                {
                  "id":"org.example.private",
                  "name":"Configured addon",
                  "version":"2.0.0",
                  "catalogs":[{"type":"movie","id":"recent","name":"Recent"}]
                }
                """);
        server.start();

        UserEntity owner = new UserEntity();
        owner.id = 41L;
        owner.role = Enums.UserRole.ADMIN;
        CommunityAddon addon = new CommunityAddon();
        addon.id = 16L;
        addon.addonKey = "org.example.private";
        addon.name = "Public addon";
        addon.manifestUrl = root + "/manifest.json";
        addon.manifestJson = "{\"catalogs\":[]}";
        addon.privateUse = true;
        addon.owner = owner;

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        when(repository.findById(16L)).thenReturn(Optional.of(addon));
        when(repository.findByManifestUrl(root + "/configured/manifest.json")).thenReturn(Optional.empty());
        when(repository.save(addon)).thenReturn(addon);
        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );

        CommunityAddon updated = service.update(
                16L,
                root + "/configured/manifest.json",
                ".tb-cdn.io",
                null,
                null,
                true,
                true,
                owner
        );

        assertEquals(root + "/configured/manifest.json", updated.manifestUrl);
        assertEquals("Configured addon", updated.name);
        assertEquals("2.0.0", updated.version);
        assertEquals(".tb-cdn.io", updated.allowedStreamHosts);
        assertEquals(Enums.AddonStatus.PENDING, updated.status);
    }

    @Test
    void keepsAPrivateAddonVisibleAndManageableOnlyByItsOwner() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String root = "http://127.0.0.1:" + port;
        json("/manifest.json", """
                {
                  "id": "org.example.private",
                  "name": "Private Cinema",
                  "types": ["movie"],
                  "catalogs": [{"type": "custom", "id": "recent", "name": "Private recent"}]
                }
                """);
        server.start();

        UserEntity owner = new UserEntity();
        owner.id = 41L;
        owner.email = "owner@example.test";
        UserEntity otherAdmin = new UserEntity();
        otherAdmin.id = 42L;
        otherAdmin.email = "other@example.test";

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        when(repository.findByManifestUrl(root + "/manifest.json")).thenReturn(Optional.empty());
        when(repository.save(any(CommunityAddon.class))).thenAnswer(invocation -> {
            CommunityAddon addon = invocation.getArgument(0);
            addon.id = 15L;
            return addon;
        });

        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );
        CommunityAddon addon = service.install(
                root + "/manifest.json",
                "127.0.0.1",
                null,
                null,
                true,
                true,
                owner
        );
        when(repository.findById(15L)).thenReturn(Optional.of(addon));

        service.approve(15L, owner);
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(addon));

        assertEquals(Enums.AddonStatus.APPROVED, addon.status);
        assertEquals(1, service.categories("movie", owner).size());
        assertTrue(service.categories("movie", otherAdmin).isEmpty());
        assertEquals(1, service.list(owner).size());
        assertTrue(service.list(otherAdmin).isEmpty());
        assertThrows(ApiException.class, () -> service.disable(15L, otherAdmin));
    }

    @Test
    void superAdminCanShareAPrivateAddonWithARestrictedUser() throws Exception {
        UserEntity owner = new UserEntity();
        owner.id = 41L;
        owner.email = "owner@example.test";
        owner.role = Enums.UserRole.ADMIN;
        UserEntity superAdmin = new UserEntity();
        superAdmin.id = 1L;
        superAdmin.email = "super@example.test";
        superAdmin.role = Enums.UserRole.SUPER_ADMIN;
        UserEntity allowed = new UserEntity();
        allowed.id = 55L;
        allowed.name = "Allowed";
        allowed.email = "allowed@example.test";
        allowed.role = Enums.UserRole.USER;
        allowed.active = true;
        UserEntity denied = new UserEntity();
        denied.id = 56L;
        denied.email = "denied@example.test";

        CommunityAddon addon = new CommunityAddon();
        addon.id = 22L;
        addon.name = "Private";
        addon.privateUse = true;
        addon.owner = owner;
        addon.status = Enums.AddonStatus.APPROVED;
        addon.manifestJson = """
                {"catalogs":[{"type":"movie","id":"private","name":"Private"}]}
                """;

        CommunityAddonRepository repository = mock(CommunityAddonRepository.class);
        UserRepository users = mock(UserRepository.class);
        CatalogImageService images = mock(CatalogImageService.class);
        TorBoxTorrentResolver torrentResolver = mock(TorBoxTorrentResolver.class);
        when(repository.findById(22L)).thenReturn(Optional.of(addon));
        when(repository.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED)).thenReturn(List.of(addon));
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(addon));
        when(users.findAllById(any())).thenReturn(List.of(allowed));
        when(repository.save(addon)).thenReturn(addon);

        CommunityAddonService service = new CommunityAddonService(
                repository,
                users,
                images,
                new ObjectMapper(),
                torrentResolver,
                262_144,
                300,
                true
        );

        service.updateAccess(22L, List.of(55L), superAdmin);

        assertEquals(1, service.categories("movie", allowed).size());
        assertTrue(service.categories("movie", denied).isEmpty());
        assertTrue(service.list(allowed).isEmpty());
        assertEquals(List.of(55L), service.list(superAdmin).get(0).get("allowedUserIds"));
        assertThrows(ApiException.class, () -> service.updateAccess(22L, List.of(56L), owner));
    }

    private void json(String path, String body) {
        server.createContext(path, exchange -> respond(exchange, body));
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String catalogResponse(String prefix, int count) {
        StringBuilder metas = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) metas.append(',');
            metas.append("""
                    {"id":"%s-%d","type":"movie","name":"%s %d"}
                    """.formatted(prefix, index, prefix, index));
        }
        return "{\"metas\":[" + metas + "]}";
    }
}
