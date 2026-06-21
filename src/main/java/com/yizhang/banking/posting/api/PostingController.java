package com.yizhang.banking.posting.api;

import com.yizhang.banking.posting.service.PostingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
            @Valid @RequestBody PostingRequest request) {

        PostingService.ApplyResult result = postingService.apply(request, idempotencyKey);
        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }
}
