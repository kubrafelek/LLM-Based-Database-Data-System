package com.llmdiscovery.classification;

import com.llmdiscovery.classification.ClassifyDtos.ClassifyRequest;
import com.llmdiscovery.classification.ClassifyDtos.ClassifyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "classification")
@SecurityRequirement(name = "bearer")
public class ClassificationController {

    private final ClassificationService service;

    public ClassificationController(ClassificationService service) {
        this.service = service;
    }

    @Operation(summary = "Sample a column's values and return a probability distribution over the 12 PII categories")
    @PostMapping({"/api/v1/classify", "/classify"})
    public ClassifyResponse classify(@Valid @RequestBody ClassifyRequest request) {
        return service.classify(request);
    }
}
