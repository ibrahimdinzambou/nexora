package com.iptv.saas.web;

import com.iptv.saas.service.CatalogImageService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class CatalogImageController {
    private static final MediaType SVG = MediaType.parseMediaType("image/svg+xml");
    private static final byte[] PLACEHOLDER = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 450">
              <rect width="300" height="450" fill="#111827"/>
              <path d="M92 164h116v122H92z" fill="#1f2937"/>
              <path d="m108 264 31-35 24 25 18-17 19 27z" fill="#64748b"/>
              <circle cx="177" cy="199" r="13" fill="#94a3b8"/>
            </svg>
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final CatalogImageService images;

    public CatalogImageController(CatalogImageService images) {
        this.images = images;
    }

    @GetMapping("/api/catalog/images/{key}")
    public ResponseEntity<byte[]> image(@PathVariable String key) {
        try {
            CatalogImageService.CachedImage image = images.load(key);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                    .contentType(MediaType.parseMediaType(image.contentType()))
                    .body(image.bytes());
        } catch (ApiException exception) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(SVG)
                    .body(PLACEHOLDER);
        }
    }
}
