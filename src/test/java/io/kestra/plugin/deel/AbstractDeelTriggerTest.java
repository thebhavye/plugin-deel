package io.kestra.plugin.deel;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.tenant.TenantService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Base class for Deel trigger tests.
 *
 * <p>Trigger watermarks are persisted in the namespace KV store, which requires the
 * namespace to exist. A minimal flow is therefore created once per test class.
 * Each test must use a distinct trigger id so KV state never leaks between tests.
 */
public abstract class AbstractDeelTriggerTest extends AbstractDeelTest {

    protected static final String NAMESPACE = "company.team";

    @Inject
    protected FlowRepositoryInterface flowRepository;

    protected Flow triggerFlow;

    @BeforeAll
    void createNamespaceFlow() {
        triggerFlow = Flow.builder()
            .id("deel-trigger-tests")
            .namespace(NAMESPACE)
            .tenantId(TenantService.MAIN_TENANT)
            .tasks(List.of())
            .build();
        try {
            flowRepository.create(io.kestra.core.models.flows.GenericFlow.of(triggerFlow));
        } catch (Exception e) {
            // Namespace already initialized by another test class in the same JVM.
        }
    }

    protected RunContext runContext(RunContextFactory factory, AbstractTrigger trigger) {
        RunContext runContext = factory.of(triggerFlow, trigger);
        // Scheduler initialization, as done by the real scheduler before polling:
        // defines the trigger execution id required by TriggerService.generateExecution.
        factory.initializer().forScheduler(
            (io.kestra.core.runners.DefaultRunContext) runContext,
            TriggerContext.builder()
                .tenantId(TenantService.MAIN_TENANT)
                .namespace(NAMESPACE)
                .flowId(triggerFlow.getId())
                .triggerId(trigger.getId())
                .date(ZonedDateTime.now())
                .build(),
            trigger
        );
        return runContext;
    }

    protected ConditionContext conditionContext(RunContext runContext) {
        return new ConditionContext(triggerFlow, null, runContext, Map.of(), null);
    }

    protected TriggerContext triggerContext(String flowId, String triggerId) {
        return TriggerContext.builder()
            .tenantId(TenantService.MAIN_TENANT)
            .namespace(NAMESPACE)
            .flowId(flowId)
            .triggerId(triggerId)
            .date(ZonedDateTime.now())
            .build();
    }

    protected String baseUrl() {
        return "http://localhost:" + embeddedServer.getPort() + "/mock";
    }
}
