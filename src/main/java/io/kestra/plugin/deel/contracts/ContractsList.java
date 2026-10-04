package io.kestra.plugin.deel.contracts;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.deel.connection.AbstractDeelConnection;
import io.kestra.plugin.deel.model.DeelPage;
import io.kestra.plugin.deel.model.DeelContract;
import io.kestra.plugin.deel.model.DeelPagination;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.FileSerde;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "List Contracts",
    description = "List contracts with their details. Supports cursor-based pagination."
)
@Plugin(
    examples = {
        @Example(
            title = "List all contracts",
            full = true,
            code = """
                id: list_contracts
                namespace: company.team
                tasks:
                  - id: list_contracts
                    type: io.kestra.plugin.deel.contracts.ContractsList
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    limit: 50
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "List active contracts with filters",
            full = true,
            code = """
                id: list_active_contracts
                namespace: company.team
                tasks:
                  - id: list_contracts
                    type: io.kestra.plugin.deel.contracts.ContractsList
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    status: "active"
                    limit: 25
                    fetchType: STORE
                """
        )
    }
)
public class ContractsList extends AbstractDeelConnection implements RunnableTask<ContractsList.Output> {

    @Schema(
        title = "Maximum number of contracts per page",
        description = "Number of contracts to return per page (max 100). Default is 25."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Integer> limit = Property.ofValue(25);

    @Schema(
        title = "Cursor for pagination",
        description = "Cursor for the next page (cursor-based pagination)."
    )
    @PluginProperty(group = "main")
    private Property<String> afterCursor;

    @Schema(
        title = "Filter by contract type",
        description = "Filter contracts by type (e.g., open, terminated, expired)."
    )
    @PluginProperty(group = "main")
    private Property<String> contractType;

    @Schema(
        title = "Filter by status",
        description = "Filter contracts by status (e.g., active, terminated)."
    )
    @PluginProperty(group = "main")
    private Property<String> status;

    @Schema(
        title = "Filter by legal entity ID",
        description = "Filter contracts by legal entity ID."
    )
    @PluginProperty(group = "main")
    private Property<String> legalEntityId;

    @Schema(
        title = "Filter by team ID",
        description = "Filter contracts by team ID."
    )
    @PluginProperty(group = "main")
    private Property<String> teamId;

    @Schema(
        title = "Filter by updated since",
        description = "Filter contracts updated since the given timestamp (ISO 8601 format)."
    )
    @PluginProperty(group = "main")
    private Property<String> updatedSince;

    @Schema(
        title = "Result handling mode",
        description = "Controls how hits are exposed in outputs. FETCH returns all hits in the response. FETCH_ONE returns only the first hit. STORE writes hits to Kestra storage and returns a URI. NONE leaves outputs empty."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    // TypeReference for DeelPage<DeelContract> to preserve generic type information
    private static final TypeReference<DeelPage<DeelContract>> CONTRACTS_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Map<String, Object> params = buildQueryParams(runContext);

        String renderedAfterCursor = runContext.render(this.afterCursor).as(String.class).orElse(null);
        int renderedLimit = runContext.render(this.limit).as(Integer.class).orElse(25);

        if (renderedAfterCursor != null) {
            params.put("after_cursor", renderedAfterCursor);
        }
        params.put("limit", Math.min(renderedLimit, 100));

        DeelPage<DeelContract> page = request(
            runContext,
            "/contracts",
            "GET",
            params,
            CONTRACTS_PAGE_TYPE_REF
        );

        List<DeelContract> contracts = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
        DeelPagination pagination = page != null ? page.getPage() : null;

        logger.debug("Retrieved {} contracts (total: {})", contracts.size(), pagination != null ? pagination.getTotalRows() : "unknown");

        // Extract next cursor for pagination
        String nextCursor = pagination != null && pagination.getCursor() != null ? pagination.getCursor() : null;

        FetchType resolvedFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        return handleFetch(runContext, contracts, pagination, resolvedFetchType, nextCursor);
    }

    private Map<String, Object> buildQueryParams(RunContext runContext) throws Exception {
        Map<String, Object> params = new HashMap<>();

        runContext.render(this.contractType).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("contract_type", v));
        runContext.render(this.status).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("status", v));
        runContext.render(this.legalEntityId).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("legal_entity_id", v));
        runContext.render(this.teamId).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("team_id", v));
        runContext.render(this.updatedSince).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("updated_since", v));

        return params;
    }

    private Output handleFetch(RunContext runContext, List<DeelContract> contracts, DeelPagination pagination, FetchType fetchType, String nextCursor) throws Exception {
        List<Map<String, Object>> mapped = contracts.stream()
            .map(this::contractToMap)
            .toList();

        return switch (fetchType) {
            case FETCH -> Output.builder()
                .rows(mapped)
                .size(mapped.size())
                .total(pagination != null ? pagination.getTotalRows() : (long) mapped.size())
                .nextCursor(nextCursor)
                .build();
            case FETCH_ONE -> Output.builder()
                .row(mapped.isEmpty() ? null : mapped.getFirst())
                .size(mapped.isEmpty() ? 0 : 1)
                .total(pagination != null ? pagination.getTotalRows() : (long) mapped.size())
                .nextCursor(nextCursor)
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
                    .total(pagination != null ? pagination.getTotalRows() : (long) mapped.size())
                    .nextCursor(nextCursor)
                    .build();
            }
            case NONE -> Output.builder()
                .size(0)
                .total(pagination != null ? pagination.getTotalRows() : 0L)
                .nextCursor(nextCursor)
                .build();
        };
    }

    private Map<String, Object> contractToMap(DeelContract contract) {
        return DeelContract.toMap(contract);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Number of contracts returned in this response",
            description = "Number of contracts included in outputs for the selected fetch type."
        )
        private Integer size;

        @Schema(
            title = "Total number of contracts",
            description = "Total contracts reported by the API, regardless of pagination."
        )
        private Long total;

        @Schema(
            title = "Fetched contracts",
            description = "Available only when fetchType=FETCH; contains contracts for the current response page."
        )
        private List<Map<String, Object>> rows;

        @Schema(
            title = "First contract",
            description = "Available only when fetchType=FETCH_ONE; contains the first contract."
        )
        private Map<String, Object> row;

        @Schema(
            title = "Next page cursor",
            description = "Cursor for the next page of results. Returned when more pages are available."
        )
        private String nextCursor;

        @Schema(
            title = "Stored contracts URI",
            description = "Kestra internal storage path to the contracts file."
        )
        private URI uri;
    }
}