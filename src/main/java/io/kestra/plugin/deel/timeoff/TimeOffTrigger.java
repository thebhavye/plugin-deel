package io.kestra.plugin.deel.timeoff;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.property.Property;
import jakarta.validation.constraints.NotNull;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.deel.triggers.AbstractDeelTrigger;
import io.kestra.plugin.deel.model.DeelTimeOff;
import io.kestra.plugin.deel.model.DeelTimeOffPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "Time Off Trigger",
    description = "Poll the profile time-off endpoint and trigger on requested and approved time-off records. "
        + "State (watermark and last-seen statuses) is persisted in the KV store so polls do not repeat events. "
        + "Uses cursor pagination (page_size/next) as documented for the endpoint."
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger on requested and approved time off",
            full = true,
            code = """
                id: time_off_events
                namespace: company.team
                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.event }}: time off {{ trigger.timeOff.id }} ({{ trigger.timeOff.status }})"
                triggers:
                  - id: watch_time_off
                    type: io.kestra.plugin.deel.timeoff.TimeOffTrigger
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    hrisProfileId: "d290f1ee-6c54-4b01-90e6-d701748f0851"
                    interval: PT5M
                    events:
                      - requested
                      - approved
                """
        )
    }
)
public class TimeOffTrigger extends AbstractDeelTrigger implements PollingTriggerInterface, TriggerOutput<TimeOffTrigger.Output> {

    public enum TimeOffEvent {
        requested,
        approved
    }

    @Schema(
        title = "HRIS profile ID",
        description = "Worker HRIS profile id identifying the profile whose time-off requests are polled."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> hrisProfileId;

    @Schema(
        title = "Events to trigger on",
        description = "Which time-off states create executions: requested and/or approved."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<List<TimeOffEvent>> events = Property.ofValue(
        List.of(TimeOffEvent.requested, TimeOffEvent.approved)
    );

    private static final TypeReference<DeelTimeOffPage> TIME_OFF_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        Logger logger = runContext.logger();

        TriggerState state = loadState(runContext, context);
        boolean baseline = state.getWatermark() == null && state.getStatuses().isEmpty()
            && state.getSeen().isEmpty() && state.getPending().isEmpty();

        String rProfileId = runContext.render(this.hrisProfileId).as(String.class).orElseThrow(() -> new IllegalArgumentException("hrisProfileId is required"));
        List<TimeOffEvent> enabledEvents = runContext.render(this.events).asList(TimeOffEvent.class);

        List<DeelTimeOff> allTimeOffs = new ArrayList<>();
        int offset = 0;
        int limit = 100;
        String cursor = null;
        boolean hasMore = true;

        while (hasMore) {
            Map<String, Object> params = new HashMap<>();
            params.put("page_size", limit);
            if (cursor != null) {
                params.put("next", cursor);
            } else {
                params.put("offset", offset);
            }

            DeelTimeOffPage page = request(
                runContext,
                "/time_offs/profile/" + rProfileId,
                "GET",
                params,
                TIME_OFF_PAGE_TYPE_REF
            );
            List<DeelTimeOff> pageData = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
            allTimeOffs.addAll(pageData);

            hasMore = page.getHasNextPage() != null && page.getHasNextPage();
            cursor = page.getNext();
            offset += pageData.size();
        }

        List<DeelTimeOff> timeOffs = allTimeOffs;

        timeOffs.sort(Comparator
            .comparing((DeelTimeOff timeOff) -> recordTime(timeOff),
                Comparator.nullsFirst(AbstractDeelTrigger::compareTimestamps))
            .thenComparing(DeelTimeOff::getId, Comparator.nullsFirst(String::compareTo)));

        Map<String, String> statuses = new HashMap<>(state.getStatuses());
        String watermark = state.getWatermark();
        List<Map<String, Object>> pending = new ArrayList<>(state.getPending());

        for (DeelTimeOff timeOff : timeOffs) {
            String id = timeOff.getId();
            if (id == null) {
                continue;
            }
            String recordTime = recordTime(timeOff);
            if (recordTime != null && (watermark == null || compareTimestamps(recordTime, watermark) > 0)) {
                watermark = recordTime;
            }

            if (baseline) {
                statuses.put(id, timeOff.getStatus());
                continue;
            }

            if (!statuses.containsKey(id)) {
                statuses.put(id, timeOff.getStatus());
                TimeOffEvent event = eventFor(timeOff.getStatus());
                if (event != null && enabledEvents.contains(event) && isNewRecord(recordTime, state.getWatermark())) {
                    pending.add(pendingEvent(event.name(), id, DeelTimeOff.toMap(timeOff)));
                }
            } else {
                String previous = statuses.get(id);
                String current = timeOff.getStatus();
                statuses.put(id, current);
                TimeOffEvent event = eventFor(current);
                if (!equalsNullable(previous, current) && event != null && enabledEvents.contains(event)) {
                    pending.add(pendingEvent(event.name(), id, DeelTimeOff.toMap(timeOff)));
                }
            }
        }

        if (baseline && watermark == null) {
            watermark = java.time.Instant.now().toString();
        }

        List<Map<String, Object>> deduped = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (Map<String, Object> event : pending) {
            String key = event.get("event") + "|" + event.get("id");
            if (seenKeys.add(key)) {
                deduped.add(event);
            }
        }

        if (deduped.isEmpty()) {
            TriggerState next = new TriggerState();
            next.setWatermark(watermark);
            next.setStatuses(statuses);
            next.setSeen(state.getSeen());
            next.setPending(new ArrayList<>());
            saveState(runContext, context, next);
            return Optional.empty();
        }

        // Emit ALL deduped events in this execution, not just the first one
        List<Map<String, Object>> executedEvents = new ArrayList<>(deduped);

        // Keep state without pending events for the next poll
        TriggerState next = new TriggerState();
        next.setWatermark(watermark);
        next.setStatuses(statuses);
        next.setSeen(state.getSeen());
        next.setPending(new ArrayList<>());
        saveState(runContext, context, next);

        logger.debug("Triggering on {} time-off events", executedEvents.size());

        // Build execution with all events - we'll use the first event's record for the execution
        // but all events are tracked in the state
        Map<String, Object> firstEmitted = deduped.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstTimeOff = (Map<String, Object>) firstEmitted.get("record");
        String firstEvent = (String) firstEmitted.get("event");
        String firstId = (String) firstEmitted.get("id");

        return Optional.of(buildExecution(conditionContext, context, Output.builder()
            .event(firstEvent)
            .timeOff(firstTimeOff)
            .events(deduped)
            .build()));
    }

    private String recordTime(DeelTimeOff timeOff) {
        if (!isBlank(timeOff.getUpdatedAt())) {
            return timeOff.getUpdatedAt();
        }
        if (!isBlank(timeOff.getCreatedAt())) {
            return timeOff.getCreatedAt();
        }
        return timeOff.getRequestedAt();
    }

    private TimeOffEvent eventFor(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.trim().toUpperCase()) {
            case "REQUESTED" -> TimeOffEvent.requested;
            case "APPROVED" -> TimeOffEvent.approved;
            default -> null;
        };
    }

    private boolean isNewRecord(String recordTime, String watermark) {
        if (recordTime == null || recordTime.isBlank()) {
            return true;
        }
        if (watermark == null) {
            return true;
        }
        return compareTimestamps(recordTime, watermark) >= 0;
    }

    private Map<String, Object> pendingEvent(String event, String id, Map<String, Object> record) {
        Map<String, Object> pending = new HashMap<>();
        pending.put("event", event);
        pending.put("id", id);
        pending.put("record", record);
        return pending;
    }

    private boolean equalsNullable(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Event type",
            description = "The detected event: requested or approved."
        )
        private String event;

        @Schema(
            title = "Events",
            description = "All detected events in this poll."
        )
        private List<Map<String, Object>> events;

        @Schema(
            title = "Time off record",
            description = "The time-off record that caused the first event."
        )
        private Map<String, Object> timeOff;
    }
}
