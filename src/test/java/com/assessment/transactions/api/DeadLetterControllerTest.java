package com.assessment.transactions.api;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.eventsourcing.DeadLetterStore;
import com.assessment.transactions.eventsourcing.EventBus;
import com.assessment.transactions.eventsourcing.EventStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"events.consumer.max-attempts=2", "events.consumer.retry-backoff=0ms"})
@AutoConfigureMockMvc
class DeadLetterControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    EventStore eventStore;
    @Autowired
    EventBus eventBus;
    @Autowired
    DeadLetterStore deadLetters;

    /** A CREATED event with a payload the projector cannot fold (non-numeric amount), appended behind the API's back. */
    private TransactionEvent poisonCreated() {
        return eventStore.append(UUID.randomUUID(), EventType.CREATED,
                Map.of("accountId", "acc", "amount", "not-a-number", "currency", "EUR"), Instant.now(), Instant.now());
    }

    @Test
    void poisonEventIsListedAndRedeliveryFailsAgain() throws Exception {
        TransactionEvent poison = poisonCreated();
        eventBus.publish(poison);
        await().atMost(Duration.ofSeconds(5)).until(() -> deadLetters.find(poison.id()).isPresent());
        String byEvent = "[?(@.eventId == '%s')]".formatted(poison.id());

        mvc.perform(get("/dead-letters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$" + byEvent + ".transactionId").value(poison.transactionId().toString()))
                .andExpect(jsonPath("$" + byEvent + ".sequence").value(1))
                .andExpect(jsonPath("$" + byEvent + ".type").value("CREATED"))
                .andExpect(jsonPath("$" + byEvent + ".attempts").value(2))
                .andExpect(jsonPath("$" + byEvent + ".error").value(hasItem(containsString("NumberFormatException"))));

        mvc.perform(get("/dead-letters/" + poison.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(poison.id().toString()));

        // the read model never appeared; reconciliation shows the dead letter
        mvc.perform(get("/transactions/" + poison.transactionId())).andExpect(status().isNotFound());
        mvc.perform(get("/reconciliation/report"))
                .andExpect(jsonPath("$.deadLetteredEvents" + byEvent + ".error").value(hasItem(containsString("NumberFormatException"))));

        // still poison: redelivery fails again and the event is parked again
        mvc.perform(post("/dead-letters/" + poison.id() + "/redeliver"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("failed again")));
        mvc.perform(get("/dead-letters/" + poison.id())).andExpect(status().isOk());
    }

    @Test
    void unknownDeadLetterIsNotFound() throws Exception {
        mvc.perform(get("/dead-letters/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(post("/dead-letters/" + UUID.randomUUID() + "/redeliver")).andExpect(status().isNotFound());
    }
}
