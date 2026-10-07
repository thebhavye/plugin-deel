package io.kestra.plugin.deel.contracts;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.deel.connection.AbstractDeelConnection;
import io.kestra.plugin.deel.model.DeelContract;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "Get Contract",
    description = "Retrieve a single contract by its ID."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a contract by ID",
            full = true,
            code = """
                id: get_contract
                namespace: company.team
                tasks:
                  - id: get_contract
                    type: io.kestra.plugin.deel.contracts.ContractsGet
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    contractId: "550e8400-e29b-41d4-a716-446655440000"
                """
        )
    }
)
public class ContractsGet extends AbstractDeelConnection implements RunnableTask<ContractsGet.Output> {

    @Schema(
        title = "Contract ID",
        description = "Deel contract ID (UUID from List Contracts)."
    )
    @PluginProperty(group = "main")
    private Property<String> contractId;

    // TypeReference for DeelContract to preserve generic type information
    private static final TypeReference<DeelContract> CONTRACT_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String rContractId = runContext.render(this.contractId).as(String.class).orElseThrow(() -> new IllegalArgumentException("contractId is required"));

        DeelContract contract = request(
            runContext,
            "/v2/contracts/" + rContractId,
            "GET",
            Map.of(),
            CONTRACT_TYPE_REF
        );

        if (contract == null) {
            throw new IllegalStateException("Contract not found: " + rContractId);
        }

        logger.debug("Retrieved contract: {}", contract.getTitle());

        return Output.builder()
            .contract(contractToMap(contract))
            .build();
    }

    private Map<String, Object> contractToMap(DeelContract contract) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", contract.getId());
        map.put("title", contract.getTitle());
        map.put("contract_type", contract.getContractType());
        map.put("status", contract.getStatus());
        map.put("legal_entity_id", contract.getLegalEntityId());
        map.put("team_id", contract.getTeamId());
        map.put("start_date", contract.getStartDate());
        map.put("end_date", contract.getEndDate());
        map.put("created_at", contract.getCreatedAt());
        map.put("updated_at", contract.getUpdatedAt());
        return map;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Contract details",
            description = "The retrieved contract with all available fields."
        )
        private Map<String, Object> contract;
    }
}