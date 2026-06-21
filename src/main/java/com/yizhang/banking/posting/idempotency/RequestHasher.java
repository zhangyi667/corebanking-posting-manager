package com.yizhang.banking.posting.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable canonical hash of a request body so replays can be detected even if the
 * client serializes fields in a different order.
 */
@Component
public class RequestHasher {

    private final ObjectMapper canonical;

    public RequestHasher(ObjectMapper mapper) {
        // sortPropertiesAlphabetically gives stable order across re-serialization.
        this.canonical = mapper.copy()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String hash(Object body) {
        try {
            byte[] bytes = canonical.writeValueAsBytes(body);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("failed to hash request", e);
        }
    }
}
