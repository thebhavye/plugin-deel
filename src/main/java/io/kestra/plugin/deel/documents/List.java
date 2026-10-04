package io.kestra.plugin.deel.documents;

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
import io.kestra.plugin.deel.model.DeelHrxDocument;
import io.kestra.plugin.deel.model.DeelHrxDocumentPage;
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
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "List EOR Contract Documents",
    description = "List HRX documents shared with an employee under an EOR contract. Requires the worker:read and contracts:read scopes. Supports cursor-based pagination."
)
@Plugin(
    examples = {
        @Example(
            title = "List documents for an EOR contract",
            full = true,
            code = """
                id: list_documents
                namespace: company.team
                tasks:
                  - id: list_documents
                    type: io.kestra.plugin.deel.documents.List
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    contractId: "5B74FnLM"
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractDeelConnection implements RunnableTask<List.Output> {

    @Schema(
        title = "Contract ID",
        description = "The unique identifier of the EOR employee contract."
    )
    @PluginProperty(group = "main")
    private Property<String> contractId;

    @Schema(
        title = "Maximum number of documents per page",
        description = "Number of items to return per page. Maximum is 100, default is 20."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Integer> limit = Property.ofValue(20);

    @Schema(
        title = "Cursor for pagination",
        description = "Cursor for pagination. Use the cursor from the previous response to get the next page of results."
    )
    @PluginProperty(group = "main")
    private Property<String> cursor;

    @Schema(
        title = "Result handling mode",
        description = "Controls how hits are exposed in outputs. FETCH returns all hits in the response. FETCH_ONE returns only the first hit. STORE writes hits to Kestra storage and returns a URI. NONE leaves outputs empty."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    private static final TypeReference<DeelHrxDocumentPage> DOCUMENTS_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String renderedContractId = runContext.render(this.contractId).as(String.class).orElseThrow();
        Map<String, Object> params = buildQueryParams(runContext);

        DeelHrxDocumentPage page = request(
            runContext,
            "/eor/contracts/" + renderedContractId + "/hrx-documents",
            "GET",
            params,
            DOCUMENTS_PAGE_TYPE_REF
        );

        java.util.List<DeelHrxDocument> documents = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
        long total = page != null && page.getTotalCount() != null ? page.getTotalCount().longValue() : documents.size();

        logger.debug("Retrieved {} documents for contract {} (total: {})", documents.size(), renderedContractId, total);

        FetchType resolvedFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        return handleFetch(runContext, documents, total, resolvedFetchType);
    }

    private Map<String, Object> buildQueryParams(RunContext runContext) throws Exception {
        Map<String, Object> params = new HashMap<>();

        runContext.render(this.limit).as(Integer.class).ifPresent(v -> params.put("limit", v));
        runContext.render(this.cursor).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("cursor", v));

        return params;
    }

    private Output handleFetch(RunContext runContext, java.util.List<DeelHrxDocument> documents, long total, FetchType fetchType) throws Exception {
        java.util.List<Map<String, Object>> mapped = documents.stream()
            .map(this::documentToMap)
            .toList();

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

    private Map<String, Object> documentToMap(DeelHrxDocument document) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", document.getId());
        map.put("name", document.getName());
        map.put("category", document.getCategory());
        map.put("created_at", document.getCreatedAt());
        map.put("updated_at", document.getUpdatedAt());
        map.put("category_id", document.getCategoryId());
        map.put("category_type", document.getCategoryType());
        map.put("category_description", document.getCategoryDescription());
        return map;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Number of documents returned in this response",
            description = "Number of documents included in outputs for the selected fetch type."
        )
        private Integer size;

        @Schema(
            title = "Total number of documents",
            description = "Total documents reported by the API, regardless of pagination."
        )
        private Long total;

        @Schema(
            title = "Fetched documents",
            description = "Available only when fetchType=FETCH; contains documents for the current response page."
        )
        private java.util.List<Map<String, Object>> rows;

        @Schema(
            title = "First document",
            description = "Available only when fetchType=FETCH_ONE; contains the first document."
        )
        private Map<String, Object> row;

        @Schema(
            title = "Stored documents URI",
            description = "Available only when fetchType=STORE; Kestra internal storage path to the Ion file."
        )
        private URI uri;
    }
}
