package com.assessment.transactions.api;

import com.assessment.transactions.api.dto.DeadLetterResponse;
import com.assessment.transactions.api.dto.TransactionResponse;
import com.assessment.transactions.service.DeadLetterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dead-letters")
@Tag(name = "dead-letters", description = "Events the consumer could not process; inspect and redeliver")
public class DeadLetterController {

    private final DeadLetterService service;

    public DeadLetterController(DeadLetterService service) {
        this.service = service;
    }

    @Operation(summary = "List dead-lettered events",
            description = "Events whose consumption failed `events.consumer.max-attempts` times, oldest failure first.")
    @GetMapping
    public List<DeadLetterResponse> list() {
        return service.list().stream().map(DeadLetterResponse::from).toList();
    }

    @Operation(summary = "Read one dead-lettered event")
    @ApiResponse(responseCode = "200", description = "The parked event and its failure")
    @ApiResponse(responseCode = "404", description = "Not in the dead-letter queue", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{eventId}")
    public DeadLetterResponse get(@Parameter(description = "Event id") @PathVariable UUID eventId) {
        return DeadLetterResponse.from(service.get(eventId));
    }

    @Operation(summary = "Redeliver a dead-lettered event",
            description = "Removes the event from the queue and publishes it again through the normal consumer "
                    + "path (same retries). Waits for the outcome: on success returns the transaction read model; "
                    + "if consumption fails again the event is parked again and `409` is returned.")
    @ApiResponse(responseCode = "200", description = "Redelivered and applied; the transaction read model")
    @ApiResponse(responseCode = "404", description = "Not in the dead-letter queue", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Consumption failed again; the event is back in the queue", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{eventId}/redeliver")
    public TransactionResponse redeliver(@Parameter(description = "Event id") @PathVariable UUID eventId) {
        return TransactionResponse.from(service.redeliver(eventId));
    }
}
