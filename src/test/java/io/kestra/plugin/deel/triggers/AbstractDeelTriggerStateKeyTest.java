package io.kestra.plugin.deel.triggers;

import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.plugin.deel.contracts.ContractTrigger;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class AbstractDeelTriggerStateKeyTest {

    // NOTE: a concrete trigger is used instead of a test double, see AbstractDeelConnectionTest.
    private final ContractTrigger trigger = ContractTrigger.builder().id("watch").type(ContractTrigger.class.getName()).build();

    private static TriggerContext context(String flowId, String triggerId) {
        return TriggerContext.builder()
            .namespace("company.team")
            .flowId(flowId)
            .triggerId(triggerId)
            .build();
    }

    @Test
    void stateKeyDoesNotCollideWhenIdsContainSeparator() {
        String first = trigger.stateKey(context("a-b", "c"));
        String second = trigger.stateKey(context("a", "b-c"));

        assertThat(first, is("deel-trigger-3-a-b-c"));
        assertThat(second, is("deel-trigger-1-a-b-c"));
        assertThat(first, not(second));
    }

    @Test
    void stateKeyIsStableForSameIds() {
        assertThat(trigger.stateKey(context("flow", "trigger")), is(trigger.stateKey(context("flow", "trigger"))));
    }
}
