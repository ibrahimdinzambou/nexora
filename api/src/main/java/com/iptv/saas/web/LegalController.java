package com.iptv.saas.web;

import com.iptv.saas.domain.LegalDocument;
import com.iptv.saas.repository.LegalDocumentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/legal")
public class LegalController {
    private final LegalDocumentRepository documents;

    public LegalController(LegalDocumentRepository documents) {
        this.documents = documents;
    }

    @GetMapping
    public Object list() {
        return Responses.ok(documents.findAll());
    }

    @PostMapping
    public Object create(@Valid @RequestBody LegalRequest request) {
        LegalDocument document = new LegalDocument();
        document.documentType = request.type();
        document.title = request.title();
        document.content = request.content();
        document.published = request.published() == null || request.published();
        return Responses.ok(documents.save(document));
    }

    @PutMapping("/{id}")
    public Object update(@PathVariable Long id, @RequestBody LegalRequest request) {
        LegalDocument document = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document legal introuvable"));
        if (request.type() != null && !request.type().isBlank()) document.documentType = request.type();
        if (request.title() != null && !request.title().isBlank()) document.title = request.title();
        if (request.content() != null) document.content = request.content();
        if (request.published() != null) document.published = request.published();
        return Responses.ok(documents.save(document));
    }

    public record LegalRequest(@NotBlank String type, @NotBlank String title, @NotBlank String content, Boolean published) {
    }
}
