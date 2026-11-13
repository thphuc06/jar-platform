package io.github.thphuc06.jars.imports.internal.web;

import io.github.thphuc06.jars.imports.internal.service.ImportService;
import io.github.thphuc06.jars.imports.internal.service.ImportService.CreatedImport;
import io.github.thphuc06.jars.imports.internal.service.ImportService.ImportView;
import io.github.thphuc06.jars.shared.UserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Statement import. The client creates an import, sends the file to the returned {@code upload} target (a presigned S3
 * URL on AWS, called without the API token; an API path locally, called with it), then polls the import until its
 * status is {@code COMPLETED} or {@code FAILED}.
 */
@RestController
@RequestMapping("/api/v1/imports")
class ImportController {

    record CreateImportRequest(@NotBlank @Size(max = 200) String fileName) {}

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreatedImport create(UserId userId, @Valid @RequestBody CreateImportRequest request) {
        return imports.create(userId, request.fileName());
    }

    @GetMapping
    List<ImportView> history(UserId userId) {
        return imports.history(userId);
    }

    @GetMapping("/{importId}")
    ImportView get(UserId userId, @PathVariable UUID importId) {
        return imports.get(userId, importId);
    }
}
