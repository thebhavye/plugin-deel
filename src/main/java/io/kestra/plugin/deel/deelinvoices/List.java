package io.kestra.plugin.deel.deelinvoices;

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
import io.kestra.plugin.deel.model.DeelDeelInvoice;
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
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "List Deel Invoices",
    description = "List invoices for Deel platform fees. Requires the accounting:read scope. Supports offset-based pagination."
)
@Plugin(
    examples = {
        @Example(
            title = "List Deel platform-fee invoices",
            full = true,
            code = """
                id: list_deel_invoices
                namespace: company.team
                tasks:
                  - id: list_deel_invoices
                    type: io.kestra.plugin.deel.deelinvoices.List
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    limit: 25
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractDeelConnection implements RunnableTask<List.Output> {

    @Schema(
        title = "Maximum number of invoices per page",
        description = "Number of invoices to return per page."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Integer> limit = Property.ofValue(25);

    @Schema(
        title = "Offset for pagination",
        description = "Index of the first record to return. Default is 0."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Integer> offset = Property.ofValue(0);

    @Schema(
        title = "Result handling mode",
        description = "Controls how hits are exposed in outputs. FETCH returns all hits in the response. FETCH_ONE returns only the first hit. STORE writes hits to Kestra storage and returns a URI. NONE leaves outputs empty."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    private static final TypeReference<DeelPage<DeelDeelInvoice>> DEEL_INVOICES_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        int renderedLimit = runContext.render(this.limit).as(Integer.class).orElse(25);
        int renderedOffset = runContext.render(this.offset).as(Integer.class).orElse(0);

        Map<String, Object> params = new HashMap<>();
        params.put("limit", renderedLimit);
        params.put("offset", renderedOffset);

        DeelPage<DeelDeelInvoice> page = request(
            runContext,
            "/invoices/deel",
            "GET",
            params,
            DEEL_INVOICES_PAGE_TYPE_REF
        );

        java.util.List<DeelDeelInvoice> invoices = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
        DeelPagination pagination = page != null ? page.getPage() : null;

        logger.debug("Retrieved {} Deel invoices (total: {})", invoices.size(), pagination != null ? pagination.getTotalRows() : "unknown");

        FetchType resolvedFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        return handleFetch(runContext, invoices, pagination, resolvedFetchType);
    }

    private Output handleFetch(RunContext runContext, java.util.List<DeelDeelInvoice> invoices, DeelPagination pagination, FetchType fetchType) throws Exception {
        java.util.List<Map<String, Object>> mapped = invoices.stream()
            .map(this::invoiceToMap)
            .toList();

        return switch (fetchType) {
            case FETCH -> Output.builder()
                .rows(mapped)
                .size(mapped.size())
                .total(pagination != null ? pagination.getTotalRows() : (long) mapped.size())
                .build();
            case FETCH_ONE -> Output.builder()
                .row(mapped.isEmpty() ? null : mapped.getFirst())
                .size(mapped.isEmpty() ? 0 : 1)
                .total(pagination != null ? pagination.getTotalRows() : (long) mapped.size())
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
                    .build();
            }
            case NONE -> Output.builder()
                .size(0)
                .total(pagination != null ? pagination.getTotalRows() : 0L)
                .build();
        };
    }

    private Map<String, Object> invoiceToMap(DeelDeelInvoice invoice) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", invoice.getId());
        map.put("label", invoice.getLabel());
        map.put("total", invoice.getTotal());
        map.put("status", invoice.getStatus());
        map.put("currency", invoice.getCurrency());
        map.put("created_at", invoice.getCreatedAt());
        return map;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Number of invoices returned in this response",
            description = "Number of invoices included in outputs for the selected fetch type."
        )
        private Integer size;

        @Schema(
            title = "Total number of invoices",
            description = "Total invoices reported by the API, regardless of pagination."
        )
        private Long total;

        @Schema(
            title = "Fetched Deel invoices",
            description = "Available only when fetchType=FETCH; contains Deel invoices for the current response page."
        )
        private java.util.List<Map<String, Object>> rows;

        @Schema(
            title = "First Deel invoice",
            description = "Available only when fetchType=FETCH_ONE; contains the first Deel invoice."
        )
        private Map<String, Object> row;

        @Schema(
            title = "Stored Deel invoices URI",
            description = "Available only when fetchType=STORE; Kestra internal storage path to the Ion file."
        )
        private URI uri;
    }
}
