package com.llmdiscovery.discovery;

import com.llmdiscovery.discovery.MetadataDtos.DbConnectionRequest;
import com.llmdiscovery.discovery.MetadataDtos.DeleteResponse;
import com.llmdiscovery.discovery.MetadataDtos.MetadataDetail;
import com.llmdiscovery.discovery.MetadataDtos.MetadataList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Each endpoint is served at /api/v1/... and at the case study PDF's root path (alias, not documented). */
@RestController
@Tag(name = "metadata")
@SecurityRequirement(name = "bearer")
public class MetadataController {

    private final MetadataService service;

    public MetadataController(MetadataService service) {
        this.service = service;
    }

    @Operation(summary = "Connect to a PostgreSQL database, extract its tables/columns and store them")
    @PostMapping({"/api/v1/db/metadata", "/db/metadata"})
    @ResponseStatus(HttpStatus.CREATED)
    public MetadataDetail create(@Valid @RequestBody DbConnectionRequest request) {
        return service.create(request);
    }

    @GetMapping({"/api/v1/metadata", "/metadata"})
    public MetadataList list(
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return service.list(limit, offset);
    }

    @GetMapping({"/api/v1/metadata/{metadataId}", "/metadata/{metadataId}"})
    public MetadataDetail get(@PathVariable("metadataId") UUID metadataId) {
        return service.get(metadataId);
    }

    @Operation(summary = "Delete a metadata snapshot with its tables and columns. The stored connection is kept.")
    @DeleteMapping({"/api/v1/metadata/{metadataId}", "/metadata/{metadataId}"})
    public DeleteResponse delete(@PathVariable("metadataId") UUID metadataId) {
        service.delete(metadataId);
        return new DeleteResponse(metadataId, true);
    }
}
