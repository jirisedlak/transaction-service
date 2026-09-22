package com.assessment.transactions.api;

import static com.assessment.transactions.api.IdempotentResponses.IDEMPOTENCY_KEY_HEADER;

import com.assessment.transactions.api.dto.EventResponse;
import com.assessment.transactions.api.dto.PostEventRequest;
import com.assessment.transactions.idempotency.IdempotencyService;
import com.assessment.transactions.idempotency.IdempotentResult;
import com.assessment.transactions.service.TransactionEventService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
public class EventController {

    static final String SCOPE = "events";

    private final TransactionEventService eventService;
    private final IdempotencyService idempotencyService;

    public EventController(TransactionEventService eventService, IdempotencyService idempotencyService) {
        this.eventService = eventService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Appends the event to the transaction's stream and emits it to the internal consumer.
     * Responds {@code 202 Accepted}: the transaction read model is updated asynchronously.
     */
    @PostMapping
    public ResponseEntity<EventResponse> post(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody PostEventRequest request) {
        IdempotentResult<EventResponse> result = idempotencyService.execute(SCOPE, idempotencyKey, request,
                () -> IdempotentResult.accepted(EventResponse.from(eventService.post(request))));
        return IdempotentResponses.toEntity(result, body -> URI.create("/transactions/" + body.transactionId() + "/events"));
    }
}
