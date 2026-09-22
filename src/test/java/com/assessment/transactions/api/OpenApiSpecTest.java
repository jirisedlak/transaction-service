package com.assessment.transactions.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

/**
 * The OpenAPI spec is generated from the running app (springdoc) and written to docs/openapi.yaml
 * by the Maven build. This checks the live spec describes every endpoint and stays in sync with
 * the domain enums.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMapping;

    @Test
    @SuppressWarnings("unchecked")
    void liveSpecCoversAllEndpointsAndMatchesDomainEnums() throws Exception {
        String yaml = mvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        Map<String, Object> spec = new Yaml().load(yaml);
        assertThat(spec.get("openapi").toString()).startsWith("3.");
        assertThat(((Map<String, Object>) spec.get("info")).get("title")).isEqualTo("transaction-service");

        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
        assertThat(paths.get("/transactions")).containsKey("post");
        assertThat(paths.get("/transactions/{id}")).containsKey("get");
        assertThat(paths.get("/transactions/{id}/events")).containsKey("get");
        assertThat(paths.get("/transactions/{id}/replay")).containsKey("post");
        assertThat(paths.get("/events")).containsKey("post");
        assertThat(paths.get("/reconciliation/report")).containsKey("get");
        assertThat(paths.get("/dead-letters")).containsKey("get");
        assertThat(paths.get("/dead-letters/{eventId}")).containsKey("get");
        assertThat(paths.get("/dead-letters/{eventId}/redeliver")).containsKey("post");

        // every mapped endpoint is in the spec: compare against the actual handler mappings
        java.util.Set<String> mapped = new java.util.TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (info.getPathPatternsCondition() != null && method.getBeanType().getPackageName().startsWith("com.assessment")) {
                info.getPathPatternsCondition().getPatternValues().forEach(mapped::add);
            }
        });
        assertThat(paths.keySet()).containsAll(mapped);

        Map<String, Object> post = (Map<String, Object>) paths.get("/events").get("post");
        assertThat(((Map<String, Object>) post.get("responses")).keySet()).contains("202", "400", "404", "409", "422");
        List<Map<String, Object>> params = (List<Map<String, Object>>) post.get("parameters");
        assertThat(params).anySatisfy(p -> {
            assertThat(p.get("name")).isEqualTo("Idempotency-Key");
            assertThat(p.get("in")).isEqualTo("header");
            assertThat(p.get("required")).isEqualTo(true);
        });

        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        assertThat(schemas.keySet()).containsAll(Set.of(
                "Transaction", "Event", "CreateTransactionRequest", "PostEventRequest",
                "ReconciliationReport", "ProblemDetail"));
        // the validation-only helper must not leak into the request schema
        Map<String, Object> postEvent = (Map<String, Object>) schemas.get("PostEventRequest");
        assertThat(((Map<String, Object>) postEvent.get("properties")).keySet())
                .containsExactlyInAnyOrder("transactionId", "type", "payload", "occurredAt");

        // enums stay in sync with the code
        Map<String, Object> transaction = (Map<String, Object>) schemas.get("Transaction");
        Map<String, Object> statusProp = (Map<String, Object>) ((Map<String, Object>) transaction.get("properties")).get("status");
        assertThat((List<String>) statusProp.get("enum"))
                .containsExactlyInAnyOrder(Arrays.stream(TransactionStatus.values()).map(Enum::name).toArray(String[]::new));
        Map<String, Object> event = (Map<String, Object>) schemas.get("Event");
        Map<String, Object> typeProp = (Map<String, Object>) ((Map<String, Object>) event.get("properties")).get("type");
        assertThat((List<String>) typeProp.get("enum"))
                .containsExactlyInAnyOrder(Arrays.stream(EventType.values()).map(Enum::name).toArray(String[]::new));
    }
}
