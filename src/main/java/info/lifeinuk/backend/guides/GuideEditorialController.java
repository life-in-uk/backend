package info.lifeinuk.backend.guides;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/editorial/guide-batches")
class GuideEditorialController {
    private final GuideContentBatchService service;
    private final String approvalActor;

    GuideEditorialController(GuideContentBatchService service,
            @Value("${life-in-uk.editorial.approval-actor:}") String approvalActor) {
        this.service = service;
        this.approvalActor = approvalActor;
    }

    @PostMapping
    ResponseEntity<?> create(@RequestBody JsonNode request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.create(request));
    }

    @GetMapping("/{batchId}")
    ResponseEntity<?> get(@PathVariable UUID batchId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(batchId));
    }

    @PutMapping("/{batchId}/locales/{locale}")
    ResponseEntity<?> locale(@PathVariable UUID batchId, @PathVariable String locale, @RequestBody byte[] draft) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.translate(batchId, locale, draft));
    }

    @PostMapping("/{batchId}/locales/{locale}/failure")
    ResponseEntity<?> failedLocale(@PathVariable UUID batchId, @PathVariable String locale, @RequestBody JsonNode request) {
        if (!request.isObject() || request.size() != 1 || !request.has("failureCode") || !request.get("failureCode").isString()) {
            throw new IllegalArgumentException("failure request must contain only failureCode");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.failTranslation(batchId, locale, request.get("failureCode").stringValue()));
    }

    @PostMapping("/{batchId}/reviews/{locale}")
    ResponseEntity<?> review(@PathVariable UUID batchId, @PathVariable String locale, @RequestBody JsonNode request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.review(batchId, locale, request));
    }

    @PostMapping("/{batchId}/approve")
    ResponseEntity<?> approve(@PathVariable UUID batchId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.approve(batchId, approvalActor));
    }

    @PostMapping("/{batchId}/publish")
    ResponseEntity<?> publish(@PathVariable UUID batchId, @RequestBody JsonNode request) {
        if (!request.isObject() || request.size() != 3 || !request.has("checklistId")
                || !request.has("verifiedOn") || !request.has("expectCurrentPublicationId")) {
            throw new IllegalArgumentException("publish request requires checklistId, verifiedOn, and expectCurrentPublicationId");
        }
        if (!request.get("checklistId").isString() || !request.get("verifiedOn").isString()
                || !(request.get("expectCurrentPublicationId").isNull() || request.get("expectCurrentPublicationId").isString())) {
            throw new IllegalArgumentException("publish request fields have invalid types");
        }
        UUID current = request.get("expectCurrentPublicationId").isNull() ? null
                : UUID.fromString(request.get("expectCurrentPublicationId").stringValue());
        java.time.LocalDate verifiedOn;
        try { verifiedOn = java.time.LocalDate.parse(request.get("verifiedOn").stringValue()); }
        catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("verifiedOn must be an ISO date"); }
        var command = new GuideContentBatchService.PublishRequest(current,
                request.get("checklistId").stringValue(), verifiedOn);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.publish(batchId, command, approvalActor));
    }

    @ExceptionHandler(GuideContentBatchService.NotFound.class)
    ResponseEntity<?> notFound(RuntimeException failure) { return error(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND", failure.getMessage()); }
    @ExceptionHandler({GuideContentBatchService.IdempotencyConflict.class, GuideContentBatchStore.IncompleteBatch.class,
            GuideContentBatchStore.StaleLocaleRevision.class, GuidePublicationService.Refused.class})
    ResponseEntity<?> conflict(RuntimeException failure) { return error(HttpStatus.CONFLICT, "EDITORIAL_CONFLICT", failure.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException failure) { return error(HttpStatus.BAD_REQUEST, "INVALID_EDITORIAL_REQUEST", failure.getMessage()); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> state(IllegalStateException failure) { return error(HttpStatus.CONFLICT, "EDITORIAL_STATE_CONFLICT", failure.getMessage()); }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiError(code, message));
    }
    record ApiError(String code, String message) { }
}
