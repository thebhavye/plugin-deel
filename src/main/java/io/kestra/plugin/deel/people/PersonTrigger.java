package io.kestra.plugin.deel.people;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.deel.triggers.AbstractDeelTrigger;
import io.kestra.plugin.deel.model.DeelPage;
import io.kestra.plugin.deel.model.DeelPerson;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "Person Trigger",
    description = "Poll the People endpoint and trigger on created people and hiring status changes. "
        + "State (watermark and last-seen hiring statuses) is persisted in the KV store so polls do not repeat events. "
        + "The Deel API does not return the previous hiring status; it is tracked client-side from earlier polls."
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger on new people and hiring status changes",
            full = true,
            code = """
                id: person_events
                namespace: company.team
                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.event }}: {{ trigger.person.full_name }} ({{ trigger.person.hiring_status }})"
                triggers:
                  - id: watch_people
                    type: io.kestra.plugin.deel.people.PersonTrigger
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    interval: PT5M
                    events:
                      - created
                      - hiring_status_changed
                """
        )
    }
)
public class PersonTrigger extends AbstractDeelTrigger implements PollingTriggerInterface, TriggerOutput<PersonTrigger.Output> {

    public enum PersonEvent {
        created,
        hiring_status_changed
    }

    @Schema(
        title = "Events to trigger on",
        description = "Which person events create executions."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<java.util.List<PersonEvent>> events = Property.ofValue(java.util.List.of(PersonEvent.created, PersonEvent.hiring_status_changed));

    private static final TypeReference<DeelPage<DeelPerson>> PEOPLE_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        Logger logger = runContext.logger();

        TriggerState state = loadState(runContext, context);
        boolean baseline = state.getWatermark() == null && state.getStatuses().isEmpty()
            && state.getSeen().isEmpty() && state.getPending().isEmpty();

        List<PersonEvent> enabledEvents = runContext.render(this.events).asList(PersonEvent.class);

        List<DeelPerson> allPeople = new ArrayList<>();
        int offset = 0;
        int limit = 100;
        boolean hasMore = true;

        while (hasMore) {
            Map<String, Object> params = new HashMap<>();
            params.put("limit", limit);
            params.put("offset", offset);
            if (state.getWatermark() != null) {
                params.put("updated_since", state.getWatermark());
            }

            DeelPage<DeelPerson> page = request(runContext, "/v2/people", "GET", params, PEOPLE_PAGE_TYPE_REF);
            List<DeelPerson> pageData = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
            allPeople.addAll(pageData);

            // Check if there are more pages
            // The API returns page with offset and total_rows; we stop when we've retrieved all records
            if (page.getPage() != null) {
                int totalRows = page.getPage().getTotalRows() != null ? page.getPage().getTotalRows().intValue() : allPeople.size();
                hasMore = allPeople.size() < totalRows;
            } else {
                // Fallback: stop after one page if no page metadata
                hasMore = false;
            }

            offset += limit;
        }

        List<DeelPerson> people = allPeople;

        people.sort(Comparator
            .comparing((DeelPerson person) -> person.getUpdatedAt() != null ? person.getUpdatedAt() : person.getCreatedAt(),
                Comparator.nullsFirst(AbstractDeelTrigger::compareTimestamps))
            .thenComparing(DeelPerson::getId, Comparator.nullsFirst(String::compareTo)));

        Map<String, String> statuses = new HashMap<>(state.getStatuses());
        String watermark = state.getWatermark();
        List<Map<String, Object>> pending = new ArrayList<>(state.getPending());

        for (DeelPerson person : people) {
            String id = person.getId();
            if (id == null) {
                continue;
            }
            String recordTime = person.getUpdatedAt() != null ? person.getUpdatedAt() : person.getCreatedAt();
            if (recordTime != null && (watermark == null || compareTimestamps(recordTime, watermark) > 0)) {
                watermark = recordTime;
            }

            if (baseline) {
                statuses.put(id, person.getHiringStatus());
                continue;
            }

            if (!statuses.containsKey(id)) {
                statuses.put(id, person.getHiringStatus());
                if (enabledEvents.contains(PersonEvent.created) && isNewRecord(person.getCreatedAt(), state.getWatermark())) {
                    pending.add(pendingEvent(PersonEvent.created.name(), id, null, DeelPerson.toMap(person)));
                }
            } else {
                String previous = statuses.get(id);
                String current = person.getHiringStatus();
                statuses.put(id, current);
                if (!equalsNullable(previous, current) && enabledEvents.contains(PersonEvent.hiring_status_changed)) {
                    pending.add(pendingEvent(PersonEvent.hiring_status_changed.name(), id, previous, DeelPerson.toMap(person)));
                }
            }
        }

        if (baseline && watermark == null) {
            watermark = java.time.Instant.now().toString();
        }

        // Deduplicate queued events by id + event type, keeping first occurrence order.
        List<Map<String, Object>> deduped = new ArrayList<>();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
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

        // Emit ALL deduped events in this execution
        // Clear pending list to prevent KV store growth
        TriggerState next = new TriggerState();
        next.setWatermark(watermark);
        next.setStatuses(statuses);
        next.setSeen(state.getSeen());
        next.setPending(new ArrayList<>());
        saveState(runContext, context, next);

        logger.debug("Triggering on {} person events", deduped.size());

        // Use the first event for the execution output
        Map<String, Object> firstEmitted = deduped.get(0);
        String firstEvent = (String) firstEmitted.get("event");
        String firstId = (String) firstEmitted.get("id");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstPerson = (Map<String, Object>) firstEmitted.get("record");
        String firstPreviousStatus = firstEmitted.containsKey("previousStatus")
            ? (String) firstEmitted.get("previousStatus")
            : null;

        return Optional.of(buildExecution(conditionContext, context, Output.builder()
            .event(firstEvent)
            .person(firstPerson)
            .previousStatus(firstPreviousStatus)
            .events(deduped)
            .build()));
    }

    private boolean isNewRecord(String createdAt, String watermark) {
        if (createdAt == null || createdAt.isBlank()) {
            return true;
        }
        if (watermark == null) {
            return true;
        }
        return compareTimestamps(createdAt, watermark) >= 0;
    }

    private Map<String, Object> pendingEvent(String event, String id, String previousStatus, Map<String, Object> record) {
        Map<String, Object> pending = new HashMap<>();
        pending.put("event", event);
        pending.put("id", id);
        pending.put("previousStatus", previousStatus);
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
            description = "The detected event: created or hiring_status_changed."
        )
        private String event;

        @Schema(
            title = "Events",
            description = "All detected events in this poll."
        )
        private List<Map<String, Object>> events;

        @Schema(
            title = "Person",
            description = "The person that caused the first event."
        )
        private Map<String, Object> person;

        @Schema(
            title = "Previous hiring status",
            description = "Hiring status observed on the previous poll. Only set for hiring_status_changed events; "
                + "the Deel API does not provide it, it is tracked from earlier polls."
        )
        private String previousStatus;
    }
}
