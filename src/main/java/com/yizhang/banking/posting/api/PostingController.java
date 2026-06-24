package com.yizhang.banking.posting.api;

import com.yizhang.banking.posting.service.PostingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PostingController {

    private final PostingService postingService;

    public PostingController(PostingService postingService) {
        this.postingService = postingService;
    }

    @PostMapping("/postings")
    public ResponseEntity<PostingResponse> createPosting(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody PostingRequest request) {

        // Optional client-supplied id linking multiple postings into one journey.
        // Server generates one if absent so downstream consumers always see a key.
        String effectiveCorrelationId = (correlationId == null || correlationId.isBlank())
                ? UUID.randomUUID().toString()
                : correlationId;
        PostingService.ApplyResult result = postingService.apply(request, idempotencyKey, effectiveCorrelationId);
        return ResponseEntity.status(result.httpStatus())
                .header("X-Correlation-Id", effectiveCorrelationId)
                .body(result.response());
    }
}
