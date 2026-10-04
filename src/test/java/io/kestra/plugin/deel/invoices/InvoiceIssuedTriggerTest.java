package io.kestra.plugin.deel.invoices;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.deel.AbstractDeelTriggerTest;
import io.kestra.plugin.deel.MockDeelController;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InvoiceIssuedTriggerTest extends AbstractDeelTriggerTest {

    private InvoiceIssuedTrigger buildTrigger() {
        return InvoiceIssuedTrigger.builder()
            .id("test-invoice-trigger")
            .type("io.kestra.plugin.deel.invoices.InvoiceIssuedTrigger")
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(baseUrl()))
            .build();
    }

    private RunContext runContext(InvoiceIssuedTrigger trigger) {
        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        return runContext(factory, trigger);
    }

    private String invoicesPage(String... invoices) {
        return """
            {
                "data": [%s],
                "page": {"offset": 0, "total_rows": %d, "items_per_page": 100}
            }
            """.formatted(String.join(",", invoices), invoices.length);
    }

    private String invoice(String id, String issuedAt) {
        return """
            {
                "id": "%s",
                "label": "INV-%s",
                "total": "1000",
                "status": "paid",
                "currency": "USD",
                "issued_at": "%s",
                "created_at": "2024-01-01T00:00:00Z",
                "contract_id": "contract-1"
            }
            """.formatted(id, id, issuedAt);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variablesOf(Execution execution) {
        return execution.getTrigger().getVariables();
    }

    @Test
    void testInitialPollEstablishesBaselineWithoutEmitting() throws Exception {
        MockDeelController.stubResponse(invoicesPage(invoice("inv1", "2024-06-01T00:00:00Z")));

        InvoiceIssuedTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("invoice-flow", "invoice-baseline");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(invoicesPage(invoice("inv1", "2024-06-01T00:00:00Z")));
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testIssuedEvent() throws Exception {
        MockDeelController.stubResponse(invoicesPage(invoice("inv1", "2024-06-01T00:00:00Z")));

        InvoiceIssuedTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("invoice-flow", "invoice-issued");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(invoicesPage(
            invoice("inv1", "2024-06-01T00:00:00Z"),
            invoice("inv2", "2024-07-01T00:00:00Z")
        ));

        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);
        assertThat(execution.isPresent(), is(true));

        Map<String, Object> variables = variablesOf(execution.get());
        assertThat(((Map<String, Object>) variables.get("invoice")).get("id"), is("inv2"));
        assertThat(((Map<String, Object>) variables.get("invoice")).get("issued_at"), is("2024-07-01T00:00:00Z"));

        // Repeated polls must not re-emit.
        MockDeelController.stubResponse(invoicesPage(
            invoice("inv1", "2024-06-01T00:00:00Z"),
            invoice("inv2", "2024-07-01T00:00:00Z")
        ));
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testMissingTimestampsEmittedExactlyOnce() throws Exception {
        MockDeelController.stubResponse(invoicesPage());

        InvoiceIssuedTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("invoice-flow", "invoice-no-ts");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        // Invoice without issued_at and created_at: emitted once via the seen-ids set.
        MockDeelController.stubResponse(invoicesPage("""
            {
                "id": "invX",
                "label": "INV-invX",
                "total": "50",
                "status": "paid",
                "currency": "USD"
            }
            """));

        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);
        assertThat(execution.isPresent(), is(true));
        assertThat(((Map<String, Object>) variablesOf(execution.get()).get("invoice")).get("id"), is("invX"));

        MockDeelController.stubResponse(invoicesPage("""
            {
                "id": "invX",
                "label": "INV-invX",
                "total": "50",
                "status": "paid",
                "currency": "USD"
            }
            """));
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testEmptyResponse() throws Exception {
        MockDeelController.stubResponse(invoicesPage());

        InvoiceIssuedTrigger trigger = buildTrigger();

        assertThat(
            trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("invoice-flow", "invoice-empty")).isPresent(),
            is(false)
        );
    }

    @Test
    void testAuthenticationFailure() {
        MockDeelController.stubError(401, "Unauthorized");

        InvoiceIssuedTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("invoice-flow", "invoice-401")));
        assertThat(e.getMessage(), containsString("Authentication failed"));
    }

    @Test
    void testAccessForbidden() {
        MockDeelController.stubError(403, "Forbidden");

        InvoiceIssuedTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("invoice-flow", "invoice-403")));
        assertThat(e.getMessage(), containsString("Access forbidden"));
    }

    @Test
    void testRateLimited() {
        MockDeelController.stubError(429, "Too Many Requests");

        InvoiceIssuedTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("invoice-flow", "invoice-429")));
        assertThat(e.getMessage(), containsString("Rate limited"));
    }

    @Test
    void testServerError() {
        MockDeelController.stubError(500, "Internal Server Error");

        InvoiceIssuedTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("invoice-flow", "invoice-500")));
        assertThat(e.getMessage(), containsString("Server error"));
    }

    @Test
    void testSeenIdsTrimOldestFirstDeterministically() {
        // LinkedHashSet preserves insertion order so trimming keeps the newest IDs.
        java.util.Set<String> ordered = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 2005; i++) {
            ordered.add("inv-" + i);
        }
        java.util.List<String> seen = new java.util.ArrayList<>(ordered);
        if (seen.size() > 2000) {
            seen = seen.subList(seen.size() - 1000, seen.size());
        }
        assertThat(seen.size(), is(1000));
        assertThat(seen.get(0), is("inv-1005"));
        assertThat(seen, not(hasItem("inv-0")));
        assertThat(seen, hasItem("inv-2004"));
    }
}
