package io.kestra.plugin.deel.timeoff;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.deel.AbstractDeelTriggerTest;
import io.kestra.plugin.deel.MockDeelController;
import io.kestra.plugin.deel.timeoff.TimeOffTrigger.TimeOffEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeOffTriggerTest extends AbstractDeelTriggerTest {

    private static final String PROFILE_ID = "d290f1ee-6c54-4b01-90e6-d701748f0851";

    private TimeOffTrigger buildTrigger() {
        return TimeOffTrigger.builder()
            .id("test-timeoff-trigger")
            .type("io.kestra.plugin.deel.timeoff.TimeOffTrigger")
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(baseUrl()))
            .hrisProfileId(Property.ofValue(PROFILE_ID))
            .build();
    }

    private RunContext runContext(TimeOffTrigger trigger) {
        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        return runContext(factory, trigger);
    }

    private String timeOffPage(String... records) {
        return """
            {
                "data": [%s],
                "page_size": 100,
                "has_next_page": false,
                "count": %d
            }
            """.formatted(String.join(",", records), records.length);
    }

    private String timeOff(String id, String status, String updatedAt) {
        return """
            {
                "id": "%s",
                "amount": 1.0,
                "is_paid": true,
                "end_date": "2024-06-05",
                "created_at": "2024-01-01T00:00:00Z",
                "start_date": "2024-06-01",
                "updated_at": "%s",
                "requested_at": "2024-01-01",
                "half_end_date": false,
                "half_start_date": false,
                "entitlement_unit": "CALENDAR_DAY",
                "time_off_type_id": "type-1",
                "status": "%s"
            }
            """.formatted(id, updatedAt, status);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variablesOf(Execution execution) {
        return execution.getTrigger().getVariables();
    }

    @Test
    void testInitialPollEstablishesBaselineWithoutEmitting() throws Exception {
        MockDeelController.stubResponse(timeOffPage(timeOff("t1", "REQUESTED", "2024-06-01T00:00:00Z")));

        TimeOffTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("timeoff-flow", "timeoff-baseline");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(timeOffPage(timeOff("t1", "REQUESTED", "2024-06-01T00:00:00Z")));
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testRequestedEvent() throws Exception {
        MockDeelController.stubResponse(timeOffPage(timeOff("t1", "REQUESTED", "2024-06-01T00:00:00Z")));

        TimeOffTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("timeoff-flow", "timeoff-requested");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(timeOffPage(
            timeOff("t1", "REQUESTED", "2024-06-01T00:00:00Z"),
            """
            {
                "id": "t2",
                "amount": 2.0,
                "is_paid": true,
                "end_date": "2024-07-05",
                "created_at": "2024-07-01T00:00:00Z",
                "start_date": "2024-07-01",
                "updated_at": "2024-07-01T00:00:00Z",
                "requested_at": "2024-07-01",
                "half_end_date": false,
                "half_start_date": false,
                "entitlement_unit": "CALENDAR_DAY",
                "time_off_type_id": "type-1",
                "status": "REQUESTED"
            }
            """
        ));

        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);
        assertThat(execution.isPresent(), is(true));

        Map<String, Object> variables = variablesOf(execution.get());
        assertThat(variables.get("event"), is("requested"));
        assertThat(((Map<String, Object>) variables.get("timeOff")).get("id"), is("t2"));
    }

    @Test
    void testApprovedEvent() throws Exception {
        MockDeelController.stubResponse(timeOffPage(timeOff("t1", "REQUESTED", "2024-06-01T00:00:00Z")));

        TimeOffTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("timeoff-flow", "timeoff-approved");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        MockDeelController.stubResponse(timeOffPage(timeOff("t1", "APPROVED", "2024-07-01T00:00:00Z")));
        Optional<Execution> execution = trigger.evaluate(conditionContext(runContext), context);

        assertThat(execution.isPresent(), is(true));
        Map<String, Object> variables = variablesOf(execution.get());
        assertThat(variables.get("event"), is("approved"));
        assertThat(((Map<String, Object>) variables.get("timeOff")).get("status"), is("APPROVED"));
    }

    @Test
    void testEventFiltering() throws Exception {
        MockDeelController.stubResponse(timeOffPage(timeOff("t1", "REQUESTED", "2024-06-01T00:00:00Z")));

        TimeOffTrigger trigger = TimeOffTrigger.builder()
            .id("test-timeoff-trigger")
            .type("io.kestra.plugin.deel.timeoff.TimeOffTrigger")
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(baseUrl()))
            .hrisProfileId(Property.ofValue(PROFILE_ID))
            .events(Property.ofValue(List.of(TimeOffEvent.approved)))
            .build();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("timeoff-flow", "timeoff-filter");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        // New REQUESTED record must be ignored when only approved events are enabled.
        MockDeelController.stubResponse(timeOffPage(
            timeOff("t1", "REQUESTED", "2024-06-01T00:00:00Z"),
            timeOff("t2", "REQUESTED", "2024-07-01T00:00:00Z")
        ));
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testEmptyResponse() throws Exception {
        MockDeelController.stubResponse(timeOffPage());

        TimeOffTrigger trigger = buildTrigger();

        assertThat(
            trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("timeoff-flow", "timeoff-empty")).isPresent(),
            is(false)
        );
    }

    @Test
    void testAuthenticationFailure() {
        MockDeelController.stubError(401, "Unauthorized");

        TimeOffTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("timeoff-flow", "timeoff-401")));
        assertThat(e.getMessage(), containsString("401"));
    }

    @Test
    void testAccessForbidden() {
        MockDeelController.stubError(403, "Forbidden");

        TimeOffTrigger trigger = buildTrigger();

        Exception e = assertThrows(Exception.class,
            () -> trigger.evaluate(conditionContext(runContext(trigger)), triggerContext("timeoff-flow", "timeoff-403")));
        assertThat(e.getMessage(), containsString("403"));
    }

    @Test
    void testNullPageTerminatesLoopSafely() throws Exception {
        MockDeelController.stubResponse("null");

        TimeOffTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("timeoff-flow", "timeoff-null-page");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    void testEmptyPageTerminatesLoopSafely() throws Exception {
        // The API reports more records than it returns; the empty page
        // must terminate pagination instead of looping indefinitely.
        String partialPage = """
            {
                "data": [%s],
                "page_size": 100,
                "has_next_page": true,
                "count": 1,
                "next": "cursor-page-2"
            }
            """.formatted(timeOff("t1", "REQUESTED", "2024-06-01T00:00:00Z"));
        String emptyPage = """
            {
                "data": [],
                "page_size": 100,
                "has_next_page": false,
                "count": 0,
                "next": null
            }
            """;
        MockDeelController.stubSequentialResponses(partialPage, emptyPage);

        TimeOffTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("timeoff-flow", "timeoff-empty-page");

        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }

    @Test
    void testRepeatedCursorTerminatesLoopSafely() throws Exception {
        // Regression test: if the API returns the same next cursor repeatedly,
        // pagination must terminate instead of looping indefinitely.
        String pageWithRepeatedCursor = """
            {
                "data": [%s],
                "page_size": 100,
                "has_next_page": true,
                "count": 1,
                "next": "stuck-cursor"
            }
            """.formatted(timeOff("t1", "REQUESTED", "2024-06-01T00:00:00Z"));
        MockDeelController.stubSequentialResponses(pageWithRepeatedCursor, pageWithRepeatedCursor);

        TimeOffTrigger trigger = buildTrigger();
        RunContext runContext = runContext(trigger);
        TriggerContext context = triggerContext("timeoff-flow", "timeoff-repeated-cursor");

        // Baseline consumes the page without emitting.
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));

        // Second poll with same cursor must not hang and must not emit duplicates.
        assertThat(trigger.evaluate(conditionContext(runContext), context).isPresent(), is(false));
    }
}
