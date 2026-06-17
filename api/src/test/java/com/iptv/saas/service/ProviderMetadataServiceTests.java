package com.iptv.saas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.IptvAccount;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderMetadataServiceTests {
    private HttpServer server;
    private ProviderMetadataService metadata;
    private String providerBaseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/player_api.php", this::metadataResponse);
        server.start();
        providerBaseUrl = "http://localhost:" + server.getAddress().getPort();
        metadata = new ProviderMetadataService(new ObjectMapper(), 5, 10);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void enrichesMovieDetailsFromXtreamMetadata() {
        IptvAccount account = account();
        M3uPlaylistService.Entry movie = entry(
                "movie-1",
                "Night Passage (2024)",
                "movie",
                providerBaseUrl + "/movie/demo/secret/2001.mkv",
                null,
                null,
                null,
                null
        );

        Map<String, Object> details = metadata.movieDetails(account, movie);

        assertEquals("Une course contre la montre.", details.get("summary"));
        assertEquals(List.of("Action", "Thriller"), details.get("genres"));
        assertEquals(List.of("Awa Diop", "Marc Leroy"), details.get("cast"));
        assertEquals("1 h 42 min", details.get("duration"));
        assertEquals("8.2", details.get("rating"));
        assertEquals(2024, details.get("releaseYear"));
        assertEquals(true, details.get("metadataAvailable"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enrichesSeriesAndMatchesEpisodeMetadata() {
        IptvAccount account = account();
        M3uPlaylistService.Entry episode = entry(
                "episode-1",
                "Happy Family USA S01E01",
                "series",
                providerBaseUrl + "/series/demo/secret/3001.mkv",
                "series-1",
                "Happy Family USA",
                1,
                1
        );
        M3uPlaylistService.Series series = new M3uPlaylistService.Series(
                "series-1",
                "Happy Family USA",
                "series-drama",
                "Drama",
                "https://images.test/happy.jpg",
                List.of(episode)
        );

        Map<String, Object> details = metadata.seriesDetails(account, series);
        List<Map<String, Object>> seasons = (List<Map<String, Object>>) details.get("seasons");
        List<Map<String, Object>> episodes = (List<Map<String, Object>>) seasons.get(0).get("episodes");

        assertEquals("Une famille recommence sa vie ailleurs.", details.get("summary"));
        assertEquals(List.of("Comédie", "Drame"), details.get("genres"));
        assertEquals("Nouveau départ", episodes.get(0).get("name"));
        assertEquals("La famille découvre sa nouvelle maison.", episodes.get(0).get("summary"));
        assertEquals("45 min", episodes.get(0).get("duration"));
        assertTrue(String.valueOf(details.get("backdrop")).contains("backdrop.jpg"));
    }

    private IptvAccount account() {
        IptvAccount account = new IptvAccount();
        account.id = 42L;
        return account;
    }

    private M3uPlaylistService.Entry entry(
            String id,
            String name,
            String type,
            String streamUrl,
            String seriesId,
            String seriesTitle,
            Integer season,
            Integer episode
    ) {
        return new M3uPlaylistService.Entry(
                id,
                "",
                name,
                type,
                type + "-category",
                "MOVIES | Action",
                "https://images.test/poster.jpg",
                streamUrl,
                seriesId,
                seriesTitle,
                season,
                episode,
                episode == null ? null : "Épisode " + episode
        );
    }

    private void metadataResponse(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        String response;
        if (query.contains("action=get_vod_info")) {
            response = """
                    {
                      "info": {
                        "plot": "Une course contre la montre.",
                        "genre": "Action, Thriller",
                        "cast": "Awa Diop, Marc Leroy",
                        "director": "Lina Moreau",
                        "rating": "8.2",
                        "duration": "01:42:00",
                        "releasedate": "2024-05-18",
                        "cover_big": "https://images.test/movie.jpg",
                        "backdrop_path": ["https://images.test/movie-backdrop.jpg"]
                      },
                      "movie_data": {"stream_id": 2001}
                    }
                    """;
        } else if (query.contains("action=get_series_info")) {
            response = """
                    {
                      "info": {
                        "name": "Happy Family USA",
                        "plot": "Une famille recommence sa vie ailleurs.",
                        "genre": "Comédie, Drame",
                        "cast": "Nora Fall, Jules Martin",
                        "director": "Mina Cole",
                        "rating": "7.8",
                        "releaseDate": "2025-01-12",
                        "backdrop_path": ["https://images.test/backdrop.jpg"]
                      },
                      "episodes": {
                        "1": [{
                          "episode_num": 1,
                          "title": "Nouveau départ",
                          "info": {
                            "plot": "La famille découvre sa nouvelle maison.",
                            "duration": "00:45:00",
                            "rating": "8.0",
                            "movie_image": "https://images.test/episode.jpg"
                          }
                        }]
                      }
                    }
                    """;
        } else {
            response = """
                    [{
                      "series_id": 77,
                      "name": "Happy Family USA",
                      "cover": "https://images.test/happy.jpg",
                      "plot": "Une famille recommence sa vie ailleurs.",
                      "genre": "Comédie, Drame"
                    }]
                    """;
        }

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
