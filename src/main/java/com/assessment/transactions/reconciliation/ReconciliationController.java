package com.assessment.transactions.reconciliation;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reconciliation")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    /**
     * @param asOf optional ISO-8601 instant to evaluate the report at (simulates elapsed time);
     *             defaults to the current time
     */
    @GetMapping("/report")
    public ReconciliationReport report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        return asOf == null ? service.report() : service.report(asOf);
    }
}
