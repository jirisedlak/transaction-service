package com.assessment.transactions.api;

import static com.assessment.transactions.api.IdempotentResponses.IDEMPOTENCY_KEY_HEADER;

import com.assessment.transactions.api.dto.CreateTransactionRequest;
import com.assessment.transactions.api.dto.EventResponse;
import com.assessment.transactions.api.dto.TransactionResponse;
import com.assessment.transactions.idempotency.IdempotencyService;
import com.assessment.transactions.idempotency.IdempotentResult;
import com.assessment.transactions.service.TransactionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
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
public class TransactionController {

    static final String SCOPE = "transactions";

    private final TransactionService transactionService;
    private final IdempotencyService idempotencyService;

    public TransactionController(TransactionService transactionService, IdempotencyService idempotencyService) {
        this.transactionService = transactionService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {
        IdempotentResult<TransactionResponse> result = idempotencyService.execute(SCOPE, idempotencyKey, request,
                () -> IdempotentResult.created(TransactionResponse.from(transactionService.create(request))));
        return IdempotentResponses.toEntity(result, body -> URI.create("/transactions/" + body.id()));
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        return TransactionResponse.from(transactionService.get(id));
    }

    /** The transaction's event stream in the order the events were received. */
    @GetMapping("/{id}/events")
    public List<EventResponse> events(@PathVariable UUID id) {
        return transactionService.events(id).stream().map(EventResponse::from).toList();
    }

    /** Rebuilds the transaction read model by replaying its event stream from the beginning. */
    @PostMapping("/{id}/replay")
    public TransactionResponse replay(@PathVariable UUID id) {
        return TransactionResponse.from(transactionService.replay(id));
    }
}
