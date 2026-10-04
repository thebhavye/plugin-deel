package io.kestra.plugin.deel.contracts;

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
import io.kestra.plugin.deel.model.DeelContract;
import io.kestra.plugin.deel.model.DeelPage;
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
    title = "Contract Trigger",
    description = "Poll the Contracts endpoint and trigger on created, signed and terminated contracts. "
        + "State (watermark and last-seen statuses) is persisted in the KV store so polls do not repeat events. "
        + "Deel exposes no single canonical signed or terminated status, so the matching status sets are configurable."
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger on contract lifecycle events",
            full = true,
            code = """
                id: contract_events
                namespace: company.team
                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.event }}: {{ trigger.contract.title }} ({{ trigger.contract.status }})"
                triggers:
                  - id: watch_contracts
                    type: io.kestra.plugin.deel.contracts.ContractTrigger
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    interval: PT5M
                    events:
                      - created
                      - signed
                      - terminated
                """
        )
    }
)
public class ContractTrigger extends AbstractDeelTrigger implements PollingTriggerInterface, TriggerOutput<ContractTrigger.Output> {

    public enum ContractEvent {
        created,
        signed,
        terminated
    }

    @Schema(
        title = "Events to trigger on",
        description = "Which contract events create executions."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<List<ContractEvent>> events = Property.ofValue(
        List.of(ContractEvent.created, ContractEvent.signed, ContractEvent.terminated)
    );

    @Schema(
        title = "Signed statuses",
        description = "Contract statuses treated as signed. Deel exposes no single signed status; defaults to in_progress."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<List<String>> signedStatuses = Property.ofValue(List.of("in_progress"));

    @Schema(
        title = "Terminated statuses",
        description = "Contract statuses treated as terminated. Deel exposes no single terminated status."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<List<String>> terminatedStatuses = Property.ofValue(
        List.of("terminated", "cancelled", "user_cancelled")
    );

    private static final TypeReference<DeelPage<DeelContract>> CONTRACTS_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        Logger logger = runContext.logger();

        TriggerState state = loadState(runContext, context);
        boolean baseline = state.getWatermark() == null && state.getStatuses().isEmpty()
            && state.getSeen().isEmpty() && state.getPending().isEmpty();

        List<ContractEvent> enabledEvents = runContext.render(this.events).asList(ContractEvent.class);
        List<String> signed = runContext.render(this.signedStatuses).asList(String.class);
        List<String> terminated = runContext.render(this.terminatedStatuses).asList(String.class);

        List<DeelContract> allContracts = new ArrayList<>();
        int limit = 100;
        String afterCursor = null;
        boolean hasMore = true;

        while (hasMore) {
            Map<String, Object> params = new HashMap<>();
            params.put("limit", limit);
            if (state.getWatermark() != null) {
                params.put("updated_since", state.getWatermark());
            }
            if (afterCursor != null && !afterCursor.isBlank()) {
                params.put("after_cursor", afterCursor);
            }

            DeelPage<DeelContract> page = request(runContext, "/contracts", "GET", params, CONTRACTS_PAGE_TYPE_REF);
            if (page == null || page.getData() == null || page.getData().isEmpty()) {
                break;
            }
            allContracts.addAll(page.getData());

            String nextCursor = page.getPage() != null ? page.getPage().getCursor() : null;
            if (nextCursor == null || nextCursor.isBlank()) {
                hasMore = false;
            } else {
                afterCursor = nextCursor;
            }
        }

        List<DeelContract> contracts = allContracts;

        contracts.sort(Comparator
            .comparing((DeelContract contract) -> contract.getUpdatedAt() != null ? contract.getUpdatedAt() : contract.getCreatedAt(),
                Comparator.nullsFirst(AbstractDeelTrigger::compareTimestamps))
            .thenComparing(DeelContract::getId, Comparator.nullsFirst(String::compareTo)));

        Map<String, String> statuses = new HashMap<>(state.getStatuses());
        String watermark = state.getWatermark();
        List<Map<String, Object>> pending = new ArrayList<>(state.getPending());

        for (DeelContract contract : contracts) {
            String id = contract.getId();
            if (id == null) {
                continue;
            }
            String recordTime = contract.getUpdatedAt() != null ? contract.getUpdatedAt() : contract.getCreatedAt();
            if (recordTime != null && (watermark == null || compareTimestamps(recordTime, watermark) > 0)) {
                watermark = recordTime;
            }

            if (baseline) {
                statuses.put(id, contract.getStatus());
                continue;
            }

            if (!statuses.containsKey(id)) {
                statuses.put(id, contract.getStatus());
                if (enabledEvents.contains(ContractEvent.created) && isNewRecord(contract.getCreatedAt(), state.getWatermark())) {
                    pending.add(pendingEvent(ContractEvent.created.name(), id, null, DeelContract.toMap(contract)));
                }
            } else {
                String previous = statuses.get(id);
                String current = contract.getStatus();
                statuses.put(id, current);
                if (!equalsNullable(previous, current)) {
                    if (current != null && signed.contains(current) && enabledEvents.contains(ContractEvent.signed)) {
                        pending.add(pendingEvent(ContractEvent.signed.name(), id, previous, DeelContract.toMap(contract)));
                    } else if (current != null && terminated.contains(current) && enabledEvents.contains(ContractEvent.terminated)) {
                        pending.add(pendingEvent(ContractEvent.terminated.name(), id, previous, DeelContract.toMap(contract)));
                    }
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

        // Emit ALL deduped events in this execution
        // Clear pending list to prevent KV store growth
        TriggerState next = new TriggerState();
        next.setWatermark(watermark);
        next.setStatuses(statuses);
        next.setSeen(state.getSeen());
        next.setPending(new ArrayList<>());
        saveState(runContext, context, next);

        logger.debug("Triggering on {} contract events", deduped.size());

        // Use the first event for the execution output
        Map<String, Object> firstEmitted = deduped.get(0);
        String firstEvent = (String) firstEmitted.get("event");
        String firstId = (String) firstEmitted.get("id");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstContract = (Map<String, Object>) firstEmitted.get("record");
        String firstPreviousStatus = firstEmitted.containsKey("previousStatus")
            ? (String) firstEmitted.get("previousStatus")
            : null;

        return Optional.of(buildExecution(conditionContext, context, Output.builder()
            .event(firstEvent)
            .contract(firstContract)
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
            description = "The detected event: created, signed or terminated."
        )
        private String event;

        @Schema(
            title = "Events",
            description = "All detected events in this poll."
        )
        private List<Map<String, Object>> events;

        @Schema(
            title = "Contract",
            description = "The contract that caused the first event."
        )
        private Map<String, Object> contract;

        @Schema(
            title = "Previous status",
            description = "Contract status observed on the previous poll. Only set for signed and terminated events; "
                + "the Deel API does not provide it, it is tracked from earlier polls."
        )
        private String previousStatus;
    }
}
