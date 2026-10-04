package io.kestra.plugin.deel.org;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.deel.connection.AbstractDeelConnection;
import io.kestra.plugin.deel.model.DeelCostCenter;
import io.kestra.plugin.deel.model.DeelListResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "List Cost Centers",
    description = "List cost centers associated with a legal entity. Requires the legal-entity:read scope."
)
@Plugin(
    examples = {
        @Example(
            title = "List cost centers for a legal entity",
            full = true,
            code = """
                id: list_cost_centers
                namespace: company.team
                tasks:
                  - id: list_cost_centers
                    type: io.kestra.plugin.deel.org.CostCenters
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    legalEntityId: "ce652eb3-d49c-3675-8184-d873544eedf7"
                    fetchType: FETCH
                """
        )
    }
)
public class CostCenters extends AbstractDeelConnection implements RunnableTask<CostCenters.Output> {

    @Schema(
        title = "Legal entity ID",
        description = "Legal entity id whose cost centers are returned."
    )
    @PluginProperty(group = "main")
    private Property<String> legalEntityId;

    @Schema(
        title = "Result handling mode",
        description = "Controls how hits are exposed in outputs. FETCH returns all hits in the response. FETCH_ONE returns only the first hit. STORE writes hits to Kestra storage and returns a URI. NONE leaves outputs empty."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    private static final TypeReference<DeelListResponse<DeelCostCenter>> COST_CENTERS_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String renderedLegalEntityId = runContext.render(this.legalEntityId).as(String.class).orElseThrow();

        DeelListResponse<DeelCostCenter> response = request(
            runContext,
            "/legal-entities/" + renderedLegalEntityId + "/cost-centers",
            "GET",
            Map.of(),
            COST_CENTERS_TYPE_REF
        );

        List<DeelCostCenter> costCenters = response != null && response.getData() != null
            ? response.getData()
            : List.of();

        logger.debug("Retrieved {} cost centers for legal entity {}", costCenters.size(), renderedLegalEntityId);

        FetchType resolvedFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        return handleFetch(runContext, costCenters, resolvedFetchType);
    }

    private Output handleFetch(RunContext runContext, List<DeelCostCenter> costCenters, FetchType fetchType) throws Exception {
        List<Map<String, Object>> mapped = costCenters.stream()
            .map(costCenter -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", costCenter.getId());
                map.put("created_at", costCenter.getCreatedAt());
                map.put("updated_at", costCenter.getUpdatedAt());
                map.put("cost_center_name", costCenter.getCostCenterName());
                map.put("cost_center_number", costCenter.getCostCenterNumber());
                return map;
            })
            .toList();

        return switch (fetchType) {
            case FETCH -> Output.builder()
                .rows(mapped)
                .size(mapped.size())
                .total((long) mapped.size())
                .build();
            case FETCH_ONE -> Output.builder()
                .row(mapped.isEmpty() ? null : mapped.getFirst())
                .size(mapped.isEmpty() ? 0 : 1)
                .total((long) mapped.size())
                .build();
            case STORE -> {
                File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
                    for (Map<String, Object> item : mapped) {
                        FileSerde.write(output, item);
                    }
                }
                URI uri = runContext.storage().putFile(tempFile);
                yield Output.builder()
                    .uri(uri)
                    .size(mapped.size())
                    .total((long) mapped.size())
                    .build();
            }
            case NONE -> Output.builder()
                .size(0)
                .total((long) mapped.size())
                .build();
        };
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Number of cost centers returned in this response",
            description = "Number of cost centers included in outputs for the selected fetch type."
        )
        private Integer size;

        @Schema(
            title = "Total number of cost centers",
            description = "Total cost centers reported by the API."
        )
        private Long total;

        @Schema(
            title = "Fetched cost centers",
            description = "Available only when fetchType=FETCH; contains the cost centers."
        )
        private List<Map<String, Object>> rows;

        @Schema(
            title = "First cost center",
            description = "Available only when fetchType=FETCH_ONE; contains the first cost center."
        )
        private Map<String, Object> row;

        @Schema(
            title = "Stored cost centers URI",
            description = "Available only when fetchType=STORE; Kestra internal storage path to the Ion file."
        )
        private URI uri;
    }
}
