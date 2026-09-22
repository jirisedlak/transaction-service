package com.assessment.transactions.reconciliation;

import com.assessment.transactions.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reconciliation")
@Tag(name = OpenApiConfig.TAG_RECONCILIATION)
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @Operation(
            summary = "Reconciliation report",
            description = """
                    Scans all transactions and reports:

                    - `staleTransactions` – not in a terminal state after `staleAfter` (default 2 minutes, property `reconciliation.stale-after`)
                    - `duplicateEvents` – the same event type recorded more than once on a transaction (reported regardless of age)
                    - `missingTransitions` – an event without its expected successor (`CREATED` without `APPROVED`, \
                    `APPROVED` without `SUBMITTED`, `RESERVED` without `SETTLED`), unless the transaction was `REVERSED`; \
                    only evaluated after `staleAfter`
                    - `deadLetteredEvents` – events the consumer could not apply after all retries (see `/dead-letters`); \
                    the read model of that transaction lags its stream

                    Pass `asOf` to evaluate the report as if that were the current time (simulates elapsed time).""")
    @ApiResponse(responseCode = "200", description = "The report")
    @ApiResponse(responseCode = "400", description = "Malformed `asOf`", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/report")
    public ReconciliationReport report(
            @Parameter(description = "ISO-8601 instant to evaluate the report at; defaults to now", example = "2026-09-22T08:32:02Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        return asOf == null ? service.report() : service.report(asOf);
    }
}
