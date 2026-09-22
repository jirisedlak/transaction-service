package com.assessment.transactions.api;

import static com.assessment.transactions.api.IdempotentResponses.IDEMPOTENCY_KEY_HEADER;

import com.assessment.transactions.api.dto.EventResponse;
import com.assessment.transactions.api.dto.PostEventRequest;
import com.assessment.transactions.config.OpenApiConfig;
import com.assessment.transactions.idempotency.IdempotencyService;
import com.assessment.transactions.idempotency.IdempotentResult;
import com.assessment.transactions.service.TransactionEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
@Tag(name = OpenApiConfig.TAG_EVENTS)
public class EventController {

    static final String SCOPE = "events";

    private final TransactionEventService eventService;
    private final IdempotencyService idempotencyService;

    public EventController(TransactionEventService eventService, IdempotencyService idempotencyService) {
        this.eventService = eventService;
        this.idempotencyService = idempotencyService;
    }

    @Operation(
            summary = "Post a lifecycle event",
            description = "Appends the event to the transaction's stream (verifying the transaction exists) and emits "
                    + "it to the internal consumer. Responds `202 Accepted` immediately with the stored event; the "
                    + "transaction read model is updated asynchronously, in stream order.\n\n"
                    + "Every event is accepted as a fact – out-of-order or repeated events are **not** rejected here "
                    + "but reported by `GET /reconciliation/report`. `CREATED` cannot be posted. "
                    + "Idempotent via `Idempotency-Key`.")
    @ApiResponse(responseCode = "202", description = "Event appended and emitted (or replayed – see `Idempotency-Replayed`)",
            headers = {
                    @Header(name = "Location", description = "The transaction's event stream", schema = @Schema(type = "string")),
                    @Header(name = "Idempotency-Replayed", description = "`true` when replayed from an earlier request with the same key", schema = @Schema(type = "string")),
                    @Header(name = "X-Correlation-Id", description = "Correlation id of the request", schema = @Schema(type = "string"))})
    @ApiResponse(responseCode = "400", description = "Validation failure, unknown event type, `CREATED`, or missing/invalid `Idempotency-Key`", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No transaction with this id (the key is released)", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A request with the same key is still being processed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "The key was already used with a different body", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<EventResponse> post(
            @Parameter(description = "Client-generated key that makes the request idempotent (1–255 characters, e.g. a UUID)", required = true, example = "9d2f6c1e-0a7b-4c3d-8e5f-6a7b8c9d0e1f")
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody PostEventRequest request) {
        IdempotentResult<EventResponse> result = idempotencyService.execute(SCOPE, idempotencyKey, request,
                () -> IdempotentResult.accepted(EventResponse.from(eventService.post(request))));
        return IdempotentResponses.toEntity(result, body -> URI.create("/transactions/" + body.transactionId() + "/events"));
    }
}
