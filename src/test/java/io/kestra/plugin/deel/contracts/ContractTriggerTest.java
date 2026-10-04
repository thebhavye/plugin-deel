package io.kestra.plugin.deel.contracts;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.deel.AbstractDeelTriggerTest;
import io.kestra.plugin.deel.MockDeelController;
import io.kestra.plugin.deel.contracts.ContractTrigger.ContractEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContractTriggerTest extends AbstractDeelTriggerTest {

    private ContractTrigger buildTrigger() {
        return ContractTrigger.builder()
            .id("test-contract-trigger")
            .type("io.kestra.plugin.deel.contracts.ContractTrigger")
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(baseUrl()))
            .build();
    }

    private RunContext runContext(ContractTrigger trigger) {
        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        return runContext(factory, trigger);
    }

    private String contractsPage(String... contracts) {
        return """
            {
                "data": [%s],
                "page": {"total_rows": %d}
            }
            """.formatted(String.join(",", contracts), contracts.length);
    }

    private String contract(String id, String status, String updatedAt) {
        return """
            {
                "id": "%s",
                "title": "Contract %s",
                "contract_type": "open",
                "status": "%s",
                "created_at": "%s",
                "updated_at": "%s"
            }
            """.formatted(id, id, status, updatedAt, updatedAt);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variablesOf(Execution execution) {
        return execution.getTrigger().getVariables();
    }

    @Test
    void testInitialPollEstablishesBaselineWithoutEmitting() throws Exception {
        MockDeelController.stubResponse(contractsPage(contract("c1", "in_progress", "2024-06-01T00:00:00Z")));

        ContractTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("contract-flow", "contract-baseline");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(contractsPage(contract("c1", "in_progress", "2024-06-01T00:00:00Z")));
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testCreatedEvent() throws Exception {
        MockDeelController.stubResponse(contractsPage(contract("c1", "new", "2024-06-01T00:00:00Z")));

        ContractTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("contract-flow", "contract-created");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(contractsPage(
            contract("c1", "new", "2024-06-01T00:00:00Z"),
            """
            {
                "id": "c2",
                "title": "Contract c2",
                "contract_type": "open",
                "status": "new",
                "created_at": "2024-07-01T00:00:00Z",
                "updated_at": "2024-07-01T00:00:00Z"
            }
            """
        ));

        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);
        assertThat(execution.isPresent(), is(true));

        Map<String, Object> variables = variablesOf(execution.get());
        assertThat(variables.get("event"), is("created"));
        assertThat(((Map<String, Object>) variables.get("contract")).get("id"), is("c2"));
        assertThat(variables.get("previousStatus"), nullValue());
    }

    @Test
    void testSignedEvent() throws Exception {
        MockDeelController.stubResponse(contractsPage(contract("c1", "waiting_for_client_sign", "2024-06-01T00:00:00Z")));

        ContractTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("contract-flow", "contract-signed");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(contractsPage(contract("c1", "in_progress", "2024-07-01T00:00:00Z")));
        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);

        assertThat(execution.isPresent(), is(true));
        Map<String, Object> variables = variablesOf(execution.get());
        assertThat(variables.get("event"), is("signed"));
        assertThat(((Map<String, Object>) variables.get("contract")).get("status"), is("in_progress"));
        assertThat(variables.get("previousStatus"), is("waiting_for_client_sign"));
    }

    @Test
    void testTerminatedEvent() throws Exception {
        MockDeelController.stubResponse(contractsPage(contract("c1", "in_progress", "2024-06-01T00:00:00Z")));

        ContractTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("contract-flow", "contract-terminated");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(contractsPage(contract("c1", "terminated", "2024-07-01T00:00:00Z")));
        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);

        assertThat(execution.isPresent(), is(true));
        Map<String, Object> variables = variablesOf(execution.get());
        assertThat(variables.get("event"), is("terminated"));
        assertThat(variables.get("previousStatus"), is("in_progress"));
    }

    @Test
    void testEventFiltering() throws Exception {
        MockDeelController.stubResponse(contractsPage(contract("c1", "waiting_for_client_sign", "2024-06-01T00:00:00Z")));

        ContractTrigger trigger = ContractTrigger.builder()
            .id("test-contract-trigger")
            .type("io.kestra.plugin.deel.contracts.ContractTrigger")
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(baseUrl()))
            .events(Property.ofValue(List.of(ContractEvent.terminated)))
            .build();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("contract-flow", "contract-filter");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        // Signed transition must be ignored when only terminated events are enabled.
        MockDeelController.stubResponse(contractsPage(contract("c1", "in_progress", "2024-07-01T00:00:00Z")));
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        // Termination must still trigger afterwards with the correct previous status.
        MockDeelController.stubResponse(contractsPage(contract("c1", "terminated", "2024-08-01T00:00:00Z")));
        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);
        assertThat(execution.isPresent(), is(true));
        assertThat(variablesOf(execution.get()).get("event"), is("terminated"));
        assertThat(variablesOf(execution.get()).get("previousStatus"), is("in_progress"));
    }

    @Test
    void testEmptyResponse() throws Exception {
        MockDeelController.stubResponse(contractsPage());

        ContractTrigger trigger = buildTrigger();

        assertThat(
            trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("contract-flow", "contract-empty")).isPresent(),
            is(false)
        );
    }

    @Test
    void testAuthenticationFailure() {
        MockDeelController.stubError(401, "Unauthorized");

        ContractTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("contract-flow", "contract-401")));
        assertThat(e.getMessage(), containsString("Authentication failed"));
    }

    @Test
    void testAccessForbidden() {
        MockDeelController.stubError(403, "Forbidden");

        ContractTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("contract-flow", "contract-403")));
        assertThat(e.getMessage(), containsString("Access forbidden"));
    }

    @Test
    void testRateLimited() {
        MockDeelController.stubError(429, "Too Many Requests");

        ContractTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("contract-flow", "contract-429")));
        assertThat(e.getMessage(), containsString("Rate limited"));
    }

    @Test
    void testServerError() {
        MockDeelController.stubError(500, "Internal Server Error");

        ContractTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("contract-flow", "contract-500")));
        assertThat(e.getMessage(), containsString("Server error"));
    }

    @Test
    void testCursorPaginationAggregatesAllPages() throws Exception {
        String pageOne = """
            {
                "data": [%s],
                "page": {"cursor": "cursor-page-2", "total_rows": 2}
            }
            """.formatted(contract("c1", "new", "2024-06-01T00:00:00Z"));
        String pageTwo = """
            {
                "data": [%s],
                "page": {"cursor": null, "total_rows": 2}
            }
            """.formatted(contract("c2", "new", "2024-07-01T00:00:00Z"));
        MockDeelController.stubSequentialResponses(pageOne, pageTwo);

        ContractTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("contract-flow", "contract-cursor");

        // Baseline consumes both pages without emitting.
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        String pageOneAgain = """
            {
                "data": [%s],
                "page": {"cursor": "cursor-page-2", "total_rows": 3}
            }
            """.formatted(contract("c1", "new", "2024-06-01T00:00:00Z"));
        String pageTwoWithNew = """
            {
                "data": [%s,%s],
                "page": {"cursor": null, "total_rows": 3}
            }
            """.formatted(
                contract("c2", "new", "2024-07-01T00:00:00Z"),
                contract("c3", "new", "2024-08-01T00:00:00Z"));
        MockDeelController.stubSequentialResponses(pageOneAgain, pageTwoWithNew);

        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);
        assertThat(execution.isPresent(), is(true));
        assertThat(((Map<String, Object>) variablesOf(execution.get()).get("contract")).get("id"), is("c3"));
    }
}
