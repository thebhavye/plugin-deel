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
    void testSeenIdsRetainedBeyondOldCapWithoutReEmit() throws Exception {
        // Regression test: the trigger polls the full invoice list every time, so
        // seen IDs must never be trimmed. With the old 2000->1000 cap, 2105 invoices
        // produced ~1105 duplicate events on the next poll. Now the second poll
        // must be silent.
        String bigPage = invoicesPage(generateInvoices(2105, "2024-06-01T00:00:00Z"), 2105);
        MockDeelController.stubResponse(bigPage);

        InvoiceIssuedTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("invoice-flow", "invoice-no-trim");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(bigPage);
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    void testEmptyPageTerminatesLoopSafely() throws Exception {
        // Regression test: if the API reports more total rows than it returns, an
        // empty page must terminate pagination instead of looping indefinitely.
        // The second page below claims total_rows=250 but carries no data.
        String partialPage = """
            {
                "data": [%s,%s],
                "page": {"offset": 0, "total_rows": 250, "items_per_page": 100}
            }
            """.formatted(invoice("inv1", "2024-06-01T00:00:00Z"), invoice("inv2", "2024-06-01T00:00:00Z"));
        String emptyPage = """
            {
                "data": [],
                "page": {"offset": 100, "total_rows": 250, "items_per_page": 100}
            }
            """;
        MockDeelController.stubSequentialResponses(partialPage, emptyPage);

        InvoiceIssuedTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("invoice-flow", "invoice-empty-page");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testNullPageTerminatesLoopSafely() throws Exception {
        MockDeelController.stubResponse("null");

        InvoiceIssuedTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("invoice-flow", "invoice-null-page");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testMultiPagePaginationAggregates() throws Exception {
        String pageOne = """
            {
                "data": [%s],
                "page": {"offset": 0, "total_rows": 2, "items_per_page": 100}
            }
            """.formatted(invoice("inv1", "2024-06-01T00:00:00Z"));
        String pageTwo = """
            {
                "data": [%s],
                "page": {"offset": 100, "total_rows": 2, "items_per_page": 100}
            }
            """.formatted(invoice("inv2", "2024-07-01T00:00:00Z"));
        MockDeelController.stubSequentialResponses(pageOne, pageTwo);

        InvoiceIssuedTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("invoice-flow", "invoice-multi-page");

        // Baseline consumes both pages without emitting.
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        String pageTwoWithNew = """
            {
                "data": [%s,%s],
                "page": {"offset": 100, "total_rows": 3, "items_per_page": 100}
            }
            """.formatted(invoice("inv2", "2024-07-01T00:00:00Z"), invoice("inv3", "2024-08-01T00:00:00Z"));
        MockDeelController.stubSequentialResponses(pageOne, pageTwoWithNew);

        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);
        assertThat(execution.isPresent(), is(true));
        assertThat(((Map<String, Object>) variablesOf(execution.get()).get("invoice")).get("id"), is("inv3"));
    }

    @Test
    void testPartialPageAdvancesByPageSize() throws Exception {
        // A page may hold fewer than `limit` items without being the last page:
        // the next offset must advance by the received page size, not by 100.
        String pageOne = invoicesPage(
            String.join(",", invoice("inv1", "2024-06-01T00:00:00Z"), invoice("inv2", "2024-06-01T00:00:00Z")), 3);
        String pageTwo = invoicesPage(invoice("inv3", "2024-07-01T00:00:00Z"), 3);
        MockDeelController.stubSequentialResponses(pageOne, pageTwo);

        InvoiceIssuedTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("invoice-flow", "invoice-partial-page");

        // Baseline aggregates both pages without emitting.
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
        assertThat(MockDeelController.requestedOffsets, is(java.util.List.of(0, 2)));
    }

    private String generateInvoices(int count, String issuedAt) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(invoice("bulk-" + i, issuedAt));
        }
        return sb.toString();
    }

    private String invoicesPage(String invoicesJson, int totalRows) {
        return """
            {
                "data": [%s],
                "page": {"offset": 0, "total_rows": %d, "items_per_page": 100}
            }
            """.formatted(invoicesJson, totalRows);
    }
}
