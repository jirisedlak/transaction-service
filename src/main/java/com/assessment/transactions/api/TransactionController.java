package com.assessment.transactions.api;

import static com.assessment.transactions.api.IdempotentResponses.IDEMPOTENCY_KEY_HEADER;

import com.assessment.transactions.api.dto.CreateTransactionRequest;
import com.assessment.transactions.api.dto.EventResponse;
import com.assessment.transactions.api.dto.TransactionResponse;
import com.assessment.transactions.config.OpenApiConfig;
import com.assessment.transactions.idempotency.IdempotencyService;
import com.assessment.transactions.idempotency.IdempotentResult;
import com.assessment.transactions.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
@Tag(name = OpenApiConfig.TAG_TRANSACTIONS)
public class TransactionController {

    static final String SCOPE = "transactions";

    private final TransactionService transactionService;
    private final IdempotencyService idempotencyService;

    public TransactionController(TransactionService transactionService, IdempotencyService idempotencyService) {
        this.transactionService = transactionService;
        this.idempotencyService = idempotencyService;
    }

    @Operation(
            summary = "Create a transaction",
            description = "Appends a `CREATED` event carrying the request data, emits it to the internal consumer and "
                    + "waits for the read model to be projected, so the `201` body already shows the transaction in "
                    + "its initial `NEW` state. Idempotent via `Idempotency-Key`.")
    @ApiResponse(responseCode = "201", description = "Transaction created (or replayed – see `Idempotency-Replayed`)",
            headers = {
                    @Header(name = "Location", description = "URL of the transaction", schema = @Schema(type = "string")),
                    @Header(name = "Idempotency-Replayed", description = "`true` when replayed from an earlier request with the same key", schema = @Schema(type = "string")),
                    @Header(name = "X-Correlation-Id", description = "Correlation id of the request", schema = @Schema(type = "string"))})
    @ApiResponse(responseCode = "400", description = "Validation failure or missing/invalid `Idempotency-Key`", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A request with the same key is still being processed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "The key was already used with a different body", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<TransactionResponse> create(
            @Parameter(description = "Client-generated key that makes the request idempotent (1–255 characters, e.g. a UUID)", required = true, example = "3f1c2a9b-8d7e-4f50-9c1a-2b3c4d5e6f70")
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {
        IdempotentResult<TransactionResponse> result = idempotencyService.execute(SCOPE, idempotencyKey, request,
                () -> IdempotentResult.created(TransactionResponse.from(transactionService.create(request))));
        return IdempotentResponses.toEntity(result, body -> URI.create("/transactions/" + body.id()));
    }

    @Operation(
            summary = "Read a transaction",
            description = "The transaction read model. It is updated asynchronously by the event consumer, so shortly "
                    + "after `POST /events` it may still show the previous status; `version` tells which event "
                    + "sequence it reflects.")
    @ApiResponse(responseCode = "200", description = "The transaction")
    @ApiResponse(responseCode = "404", description = "No transaction with this id", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public TransactionResponse get(@Parameter(description = "Transaction id") @PathVariable UUID id) {
        return TransactionResponse.from(transactionService.get(id));
    }

    @Operation(
            summary = "Read the event stream",
            description = "All events of the transaction in the order they were received (by `sequence`), starting with `CREATED`.")
    @ApiResponse(responseCode = "200", description = "The stream, ordered by `sequence`")
    @ApiResponse(responseCode = "404", description = "No transaction with this id", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}/events")
    public List<EventResponse> events(@Parameter(description = "Transaction id") @PathVariable UUID id) {
        return transactionService.events(id).stream().map(EventResponse::from).toList();
    }

    @Operation(
            summary = "Replay the event stream",
            description = "Discards the transaction read model and rebuilds it by replaying the whole event stream "
                    + "from the beginning. Naturally idempotent, so no `Idempotency-Key` is required.")
    @ApiResponse(responseCode = "200", description = "The rebuilt transaction")
    @ApiResponse(responseCode = "404", description = "No transaction with this id", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/replay")
    public TransactionResponse replay(@Parameter(description = "Transaction id") @PathVariable UUID id) {
        return TransactionResponse.from(transactionService.replay(id));
    }
}
