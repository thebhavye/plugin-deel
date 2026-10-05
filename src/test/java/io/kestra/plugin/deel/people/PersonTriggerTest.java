package io.kestra.plugin.deel.people;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.deel.AbstractDeelTriggerTest;
import io.kestra.plugin.deel.MockDeelController;
import io.kestra.plugin.deel.people.PersonTrigger.PersonEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonTriggerTest extends AbstractDeelTriggerTest {

    private PersonTrigger buildTrigger() {
        return PersonTrigger.builder()
            .id("test-person-trigger")
            .type("io.kestra.plugin.deel.people.PersonTrigger")
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(baseUrl()))
            .build();
    }

    private RunContext runContext(PersonTrigger trigger) {
        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        return runContext(factory, trigger);
    }

    private String peoplePage(String... people) {
        return """
            {
                "data": [%s],
                "page": {"offset": 0, "total_rows": %d, "items_per_page": 100}
            }
            """.formatted(String.join(",", people), people.length);
    }

    private String peoplePageWithTotal(String peopleJson, int totalRows) {
        return """
            {
                "data": [%s],
                "page": {"offset": 0, "total_rows": %d, "items_per_page": 100}
            }
            """.formatted(peopleJson, totalRows);
    }

    private String person(String id, String status, String updatedAt) {
        return """
            {
                "id": "%s",
                "first_name": "John",
                "last_name": "Doe",
                "hiring_status": "%s",
                "created_at": "2024-01-01T00:00:00Z",
                "updated_at": "%s"
            }
            """.formatted(id, status, updatedAt);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variablesOf(Execution execution) {
        return execution.getTrigger().getVariables();
    }

    @Test
    void testInitialPollEstablishesBaselineWithoutEmitting() throws Exception {
        MockDeelController.stubResponse(peoplePage(person("p1", "active", "2024-06-01T00:00:00Z")));

        PersonTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("person-flow", "person-baseline");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        // Same data on the next poll must not emit either.
        MockDeelController.stubResponse(peoplePage(person("p1", "active", "2024-06-01T00:00:00Z")));
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testCreatedEvent() throws Exception {
        MockDeelController.stubResponse(peoplePage(person("p1", "active", "2024-06-01T00:00:00Z")));

        PersonTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("person-flow", "person-created");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(peoplePage(
            person("p1", "active", "2024-06-01T00:00:00Z"),
            """
            {
                "id": "p2",
                "first_name": "Jane",
                "last_name": "Smith",
                "hiring_status": "onboarding",
                "created_at": "2024-07-01T00:00:00Z",
                "updated_at": "2024-07-01T00:00:00Z"
            }
            """
        ));

        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);
        assertThat(execution.isPresent(), is(true));

        Map<String, Object> variables = variablesOf(execution.get());
        assertThat(variables.get("event"), is("created"));
        assertThat(((Map<String, Object>) variables.get("person")).get("id"), is("p2"));
        assertThat(variables.get("previousStatus"), nullValue());

        // A repeated poll must not emit the same person again.
        MockDeelController.stubResponse(peoplePage(
            person("p1", "active", "2024-06-01T00:00:00Z"),
            person("p2", "onboarding", "2024-07-01T00:00:00Z")
        ));
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testHiringStatusChangedEvent() throws Exception {
        MockDeelController.stubResponse(peoplePage(person("p1", "onboarding", "2024-06-01T00:00:00Z")));

        PersonTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("person-flow", "person-status");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(peoplePage(person("p1", "active", "2024-07-01T00:00:00Z")));
        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);

        assertThat(execution.isPresent(), is(true));
        Map<String, Object> variables = variablesOf(execution.get());
        assertThat(variables.get("event"), is("hiring_status_changed"));
        assertThat(((Map<String, Object>) variables.get("person")).get("id"), is("p1"));
        assertThat(((Map<String, Object>) variables.get("person")).get("hiring_status"), is("active"));
        assertThat(variables.get("previousStatus"), is("onboarding"));
    }

    @Test
    void testEventFiltering() throws Exception {
        MockDeelController.stubResponse(peoplePage(person("p1", "onboarding", "2024-06-01T00:00:00Z")));

        PersonTrigger trigger = PersonTrigger.builder()
            .id("test-person-trigger")
            .type("io.kestra.plugin.deel.people.PersonTrigger")
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(baseUrl()))
            .events(Property.ofValue(List.of(PersonEvent.created)))
            .build();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("person-flow", "person-filter");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        // Status change must be ignored when only created events are enabled.
        MockDeelController.stubResponse(peoplePage(person("p1", "active", "2024-07-01T00:00:00Z")));
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testStateSurvivesRestart() throws Exception {
        MockDeelController.stubResponse(peoplePage(person("p1", "active", "2024-06-01T00:00:00Z")));

        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        PersonTrigger trigger = buildTrigger();
        TriggerContext context = triggerContext("person-flow", "person-restart");

        assertThat(trigger.evaluate(conditionContext(runContext(factory, trigger)), context).isPresent(), is(false));

        // Simulate a restart with a brand-new RunContext: the stored watermark must still apply.
        MockDeelController.stubResponse(peoplePage(person("p1", "active", "2024-06-01T00:00:00Z")));
        assertThat(trigger.evaluate(conditionContext(runContext(factory, trigger)), context).isPresent(), is(false));

        MockDeelController.stubResponse(peoplePage(
            person("p1", "active", "2024-06-01T00:00:00Z"),
            """
            {
                "id": "p9",
                "first_name": "New",
                "last_name": "Hire",
                "hiring_status": "active",
                "created_at": "2024-08-01T00:00:00Z",
                "updated_at": "2024-08-01T00:00:00Z"
            }
            """
        ));
        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext(factory, trigger)), context);
        assertThat(execution.isPresent(), is(true));
        assertThat(((Map<String, Object>) variablesOf(execution.get()).get("person")).get("id"), is("p9"));
    }

    @Test
    void testEmptyResponse() throws Exception {
        MockDeelController.stubResponse(peoplePage());

        PersonTrigger trigger = buildTrigger();

        assertThat(
            trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("person-flow", "person-empty")).isPresent(),
            is(false)
        );
    }

    @Test
    void testAuthenticationFailure() {
        MockDeelController.stubError(401, "Unauthorized");

        PersonTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("person-flow", "person-401")));
        assertThat(e.getMessage(), containsString("Authentication failed"));
    }

    @Test
    void testRateLimited() {
        MockDeelController.stubError(429, "Too Many Requests");

        PersonTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("person-flow", "person-429")));
        assertThat(e.getMessage(), containsString("Rate limited"));
    }

    @Test
    void testNotFound() {
        MockDeelController.stubError(404, "Not Found");

        PersonTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("person-flow", "person-404")));
        assertThat(e.getMessage(), containsString("Not found (404)"));
    }

    @Test
    void testNullPageTerminatesLoopSafely() throws Exception {
        MockDeelController.stubResponse("null");

        PersonTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("person-flow", "person-null-page");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    void testEmptyPageTerminatesLoopSafely() throws Exception {
        // The API reports more total rows than it returns; the empty second page
        // must terminate pagination instead of looping indefinitely.
        String partialPage = peoplePageWithTotal(
            String.join(",", person("p1", "active", "2024-06-01T00:00:00Z"), person("p2", "active", "2024-06-01T00:00:00Z")),
            250);
        String emptyPage = """
            {
                "data": [],
                "page": {"offset": 2, "total_rows": 250, "items_per_page": 100}
            }
            """;
        MockDeelController.stubSequentialResponses(partialPage, emptyPage);

        PersonTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("person-flow", "person-empty-page");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testPartialPageAdvancesByPageSize() throws Exception {
        // A page may hold fewer than `limit` items without being the last page:
        // the next offset must advance by the received page size, not by limit.
        String pageOne = peoplePageWithTotal(
            String.join(",", person("p1", "active", "2024-06-01T00:00:00Z"), person("p2", "active", "2024-06-01T00:00:00Z")),
            3);
        String pageTwo = peoplePageWithTotal(person("p3", "active", "2024-07-01T00:00:00Z"), 3);
        MockDeelController.stubSequentialResponses(pageOne, pageTwo);

        PersonTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("person-flow", "person-partial-page");

        // Baseline aggregates both pages without emitting.
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
        assertThat(MockDeelController.requestedOffsets, is(List.of(0, 2)));

        // A new person arriving on the second page must still be detected.
        MockDeelController.requestedOffsets.clear();
        String pageTwoWithNew = String.join(",",
            person("p3", "active", "2024-07-01T00:00:00Z"),
            """
            {
                "id": "p4",
                "first_name": "New",
                "last_name": "Hire",
                "hiring_status": "active",
                "created_at": "2024-08-01T00:00:00Z",
                "updated_at": "2024-08-01T00:00:00Z"
            }
            """.trim());
        MockDeelController.stubSequentialResponses(pageOne, peoplePageWithTotal(pageTwoWithNew, 4));

        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);
        assertThat(execution.isPresent(), is(true));
        assertThat(((Map<String, Object>) variablesOf(execution.get()).get("person")).get("id"), is("p4"));
        assertThat(MockDeelController.requestedOffsets, is(List.of(0, 2)));
    }
}
