package io.kestra.plugin.deel;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.GenericFlow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.plugins.PluginRegistry;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.tenant.TenantService;
import io.kestra.plugin.deel.contracts.ContractsList;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Real-Kestra regression test proving the Deel plugin is discovered through
 * Kestra's actual plugin mechanism (not by directly instantiating classes).
 *
 * <p>Covers two past runtime failures:
 * <ul>
 *   <li>tasks not extending {@code Task} were invisible to plugin scanning, so a
 *   real instance registered only triggers;</li>
 *   <li>an invalid {@code @NotBlank} constraint on {@code Property<String>} made
 *   task validation fail once tasks were registered.</li>
 * </ul>
 */
class PluginRegistrationTest extends AbstractDeelTest {

    @Inject
    protected FlowRepositoryInterface flowRepository;

    private PluginRegistry pluginRegistry() {
        return applicationContext.getBean(PluginRegistry.class);
    }

    @Test
    void testDeelTasksAreRegistered() {
        List<String> taskTypes = List.of(
            "io.kestra.plugin.deel.contracts.ContractsList",
            "io.kestra.plugin.deel.contracts.ContractsGet",
            "io.kestra.plugin.deel.people.PeopleList",
            "io.kestra.plugin.deel.people.PeopleGet",
            "io.kestra.plugin.deel.invoices.List",
            "io.kestra.plugin.deel.documents.List",
            "io.kestra.plugin.deel.documents.Download",
            "io.kestra.plugin.deel.timesheets.List",
            "io.kestra.plugin.deel.timeoff.List",
            "io.kestra.plugin.deel.payments.GetStatement",
            "io.kestra.plugin.deel.lookups.Get"
        );

        for (String type : taskTypes) {
            Class<?> clazz = pluginRegistry().findClassByIdentifier(type);
            assertThat("task registered: " + type, clazz, notNullValue());
            assertThat("task is a Task: " + type, Task.class.isAssignableFrom(clazz), is(true));
        }
    }

    @Test
    void testDeelTriggersAreRegistered() {
        List<String> triggerTypes = List.of(
            "io.kestra.plugin.deel.contracts.ContractTrigger",
            "io.kestra.plugin.deel.invoices.InvoiceIssuedTrigger",
            "io.kestra.plugin.deel.people.PersonTrigger",
            "io.kestra.plugin.deel.timeoff.TimeOffTrigger",
            "io.kestra.plugin.deel.webhooks.WebhookTrigger"
        );

        for (String type : triggerTypes) {
            Class<?> clazz = pluginRegistry().findClassByIdentifier(type);
            assertThat("trigger registered: " + type, clazz, notNullValue());
            assertThat("trigger is an AbstractTrigger: " + type, AbstractTrigger.class.isAssignableFrom(clazz), is(true));
        }

        assertThat(PollingTriggerInterface.class.isAssignableFrom(
            pluginRegistry().findClassByIdentifier("io.kestra.plugin.deel.contracts.ContractTrigger")), is(true));
    }

    @Test
    void testFlowWithDeelTaskAndTriggerValidatesAndRuns() throws Exception {
        ContractsList task = ContractsList.builder()
            .id("list_contracts")
            .type("io.kestra.plugin.deel.contracts.ContractsList")
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:" + embeddedServer.getPort() + "/mock"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        io.kestra.plugin.deel.contracts.ContractTrigger trigger = io.kestra.plugin.deel.contracts.ContractTrigger.builder()
            .id("watch_contracts")
            .type("io.kestra.plugin.deel.contracts.ContractTrigger")
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:" + embeddedServer.getPort() + "/mock"))
            .build();

        Flow flow = Flow.builder()
            .id("deel-plugin-registration")
            .namespace("company.team")
            .tenantId(TenantService.MAIN_TENANT)
            .tasks(List.of(task))
            .triggers(List.of(trigger))
            .build();

        // Validates through Kestra's real validation, including the apiToken constraints.
        ModelValidator validator = applicationContext.getBean(ModelValidator.class);
        assertThat(validator.isValid(flow).isEmpty(), is(true));

        // Persists through Kestra's flow repository, proving the flow loads.
        flowRepository.create(GenericFlow.of(flow));

        // Runs the task through Kestra's RunContext against the mocked API.
        MockDeelController.stubResponse("""
            {
                "data": [{"id": "1", "title": "Contract A", "contract_type": "open", "status": "active"}],
                "page": {"offset": 0, "total_rows": 1, "items_per_page": 25}
            }
            """);
        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        RunContext runContext = factory.of();
        ContractsList.Output output = task.run(runContext);
        assertThat(output.getSize(), is(1));
    }
}
