package com.assessment.transactions.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Produces a stable SHA-256 fingerprint of a request body so key re-use with a different payload can be detected. */
@Component
public class RequestFingerprinter {

    private final ObjectMapper canonicalMapper;

    public RequestFingerprinter(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.INDENT_OUTPUT);
    }

    public String fingerprint(Object request) {
        try {
            byte[] json = canonicalMapper.writeValueAsBytes(request);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to fingerprint request", e);
        }
    }
}
