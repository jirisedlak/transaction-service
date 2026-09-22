package com.assessment.transactions.api;

import com.assessment.transactions.idempotency.IdempotentResult;
import java.net.URI;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class IdempotentResponses {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private IdempotentResponses() {
    }

    static <T> ResponseEntity<T> toEntity(IdempotentResult<T> result, Function<T, URI> location) {
        HttpHeaders headers = new HttpHeaders();
        if (result.replayed()) {
            headers.set(REPLAYED_HEADER, "true");
        }
        if (result.status() == HttpStatus.CREATED.value() || result.status() == HttpStatus.ACCEPTED.value()) {
            headers.setLocation(location.apply(result.body()));
        }
        return ResponseEntity.status(result.status()).headers(headers).body(result.body());
    }
}
