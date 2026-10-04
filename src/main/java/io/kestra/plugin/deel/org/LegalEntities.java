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
import io.kestra.plugin.deel.model.DeelLegalEntity;
import io.kestra.plugin.deel.model.DeelPage;
import io.kestra.plugin.deel.model.DeelPagination;
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
    title = "List Legal Entities",
    description = "List legal entities in the account. Requires the organizations:read scope. Supports cursor-based pagination."
)
@Plugin(
    examples = {
        @Example(
            title = "List legal entities",
            full = true,
            code = """
                id: list_legal_entities
                namespace: company.team
                tasks:
                  - id: list_legal_entities
                    type: io.kestra.plugin.deel.org.LegalEntities
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    limit: 100
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "List legal entities filtered by country",
            full = true,
            code = """
                id: list_us_entities
                namespace: company.team
                tasks:
                  - id: list_legal_entities
                    type: io.kestra.plugin.deel.org.LegalEntities
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    country: "US"
                    fetchType: STORE
                """
        )
    }
)
public class LegalEntities extends AbstractDeelConnection implements RunnableTask<LegalEntities.Output> {

    @Schema(
        title = "Maximum number of legal entities per page",
        description = "The number of results to return per page. Default is 100."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Integer> limit = Property.ofValue(100);

    @Schema(
        title = "Cursor for pagination",
        description = "Cursor for pagination, as returned by a previous response."
    )
    @PluginProperty(group = "main")
    private Property<String> cursor;

    @Schema(
        title = "Filter by country",
        description = "Filter by country."
    )
    @PluginProperty(group = "main")
    private Property<String> country;

    @Schema(
        title = "Filter by entity type",
        description = "Filter by entity type."
    )
    @PluginProperty(group = "main")
    private Property<String> entityType;

    @Schema(
        title = "Filter by legal entity ID",
        description = "Filter by specific legal entity ID."
    )
    @PluginProperty(group = "main")
    private Property<String> legalEntityId;

    @Schema(
        title = "Filter by global payroll flag",
        description = "Filter by global payroll flag."
    )
    @PluginProperty(group = "main")
    private Property<Boolean> globalPayroll;

    @Schema(
        title = "Include archived legal entities",
        description = "Whether to include archived legal entities in the results."
    )
    @PluginProperty(group = "main")
    private Property<Boolean> includeArchived;

    @Schema(
        title = "Result handling mode",
        description = "Controls how hits are exposed in outputs. FETCH returns all hits in the response. FETCH_ONE returns only the first hit. STORE writes hits to Kestra storage and returns a URI. NONE leaves outputs empty."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    private static final TypeReference<DeelPage<DeelLegalEntity>> LEGAL_ENTITIES_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Map<String, Object> params = buildQueryParams(runContext);

        int renderedLimit = runContext.render(this.limit).as(Integer.class).orElse(100);
        params.put("limit", renderedLimit);

        DeelPage<DeelLegalEntity> page = request(
            runContext,
            "/legal-entities",
            "GET",
            params,
            LEGAL_ENTITIES_PAGE_TYPE_REF
        );

        List<DeelLegalEntity> entities = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
        DeelPagination pagination = page != null ? page.getPage() : null;

        logger.debug("Retrieved {} legal entities", entities.size());

        FetchType resolvedFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        return handleFetch(runContext, entities, pagination, resolvedFetchType);
    }

    private Map<String, Object> buildQueryParams(RunContext runContext) throws Exception {
        Map<String, Object> params = new HashMap<>();

        runContext.render(this.cursor).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("cursor", v));
        runContext.render(this.country).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("country", v));
        runContext.render(this.entityType).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("type", v));
        runContext.render(this.legalEntityId).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("legal_entity_id", v));
        runContext.render(this.globalPayroll).as(Boolean.class).ifPresent(v -> params.put("global_payroll", v));
        runContext.render(this.includeArchived).as(Boolean.class).ifPresent(v -> params.put("include_archived", v));

        return params;
    }

    private Output handleFetch(RunContext runContext, List<DeelLegalEntity> entities, DeelPagination pagination, FetchType fetchType) throws Exception {
        List<Map<String, Object>> mapped = entities.stream()
            .map(this::entityToMap)
            .toList();

        long total = pagination != null && pagination.getTotalRows() != null
            ? pagination.getTotalRows()
            : mapped.size();

        return switch (fetchType) {
            case FETCH -> Output.builder()
                .rows(mapped)
                .size(mapped.size())
                .total(total)
                .build();
            case FETCH_ONE -> Output.builder()
                .row(mapped.isEmpty() ? null : mapped.getFirst())
                .size(mapped.isEmpty() ? 0 : 1)
                .total(total)
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
                    .total(total)
                    .build();
            }
            case NONE -> Output.builder()
                .size(0)
                .total(total)
                .build();
        };
    }

    private Map<String, Object> entityToMap(DeelLegalEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("name", entity.getName());
        map.put("phone", entity.getPhone());
        map.put("vat_id", entity.getVatId());
        map.put("address", entity.getAddress());
        map.put("country", entity.getCountry());
        map.put("created_at", entity.getCreatedAt());
        map.put("sic_number", entity.getSicNumber());
        map.put("updated_at", entity.getUpdatedAt());
        map.put("archived_at", entity.getArchivedAt());
        map.put("entity_type", entity.getEntityType());
        map.put("industry_name", entity.getIndustryName());
        map.put("entity_subtype", entity.getEntitySubtype());
        map.put("registrationNumber", entity.getRegistrationNumber());
        return map;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Number of legal entities returned in this response",
            description = "Number of legal entities included in outputs for the selected fetch type."
        )
        private Integer size;

        @Schema(
            title = "Total number of legal entities",
            description = "Total legal entities reported by the API, regardless of pagination."
        )
        private Long total;

        @Schema(
            title = "Fetched legal entities",
            description = "Available only when fetchType=FETCH; contains legal entities for the current response page."
        )
        private List<Map<String, Object>> rows;

        @Schema(
            title = "First legal entity",
            description = "Available only when fetchType=FETCH_ONE; contains the first legal entity."
        )
        private Map<String, Object> row;

        @Schema(
            title = "Stored legal entities URI",
            description = "Available only when fetchType=STORE; Kestra internal storage path to the Ion file."
        )
        private URI uri;
    }
}
