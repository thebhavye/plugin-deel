package io.kestra.plugin.deel.invoices;

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
import io.kestra.plugin.deel.model.DeelInvoice;
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
    title = "List Invoices",
    description = "List workforce invoices. Requires the accounting:read scope. Supports offset- and cursor-based pagination."
)
@Plugin(
    examples = {
        @Example(
            title = "List paid invoices",
            full = true,
            code = """
                id: list_invoices
                namespace: company.team
                tasks:
                  - id: list_invoices
                    type: io.kestra.plugin.deel.invoices.List
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    limit: 25
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "List all invoices issued in a date range",
            full = true,
            code = """
                id: list_all_invoices
                namespace: company.team
                tasks:
                  - id: list_invoices
                    type: io.kestra.plugin.deel.invoices.List
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    status: "all"
                    issuedFrom: "2024-01-01"
                    issuedTo: "2024-12-31"
                    limit: 50
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractDeelConnection implements RunnableTask<List.Output> {

    @Schema(
        title = "Maximum number of invoices per page",
        description = "Number of invoices to return per page. Default is 25."
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
        title = "Cursor for pagination",
        description = "Return the next page of results after the given cursor."
    )
    @PluginProperty(group = "main")
    private Property<String> cursor;

    @Schema(
        title = "Filter by status",
        description = "By default only paid invoices are returned. Use 'all' to return invoices in all statuses."
    )
    @PluginProperty(group = "main")
    private Property<String> status;

    @Schema(
        title = "Filter by contract ID",
        description = "Filter invoices by related contract ID."
    )
    @PluginProperty(group = "main")
    private Property<String> contractId;

    @Schema(
        title = "Filter by issued-from date",
        description = "Filter invoices issued on or after the specified date."
    )
    @PluginProperty(group = "main")
    private Property<String> issuedFrom;

    @Schema(
        title = "Filter by issued-to date",
        description = "Filter invoices issued before the specified date."
    )
    @PluginProperty(group = "main")
    private Property<String> issuedTo;

    @Schema(
        title = "Result handling mode",
        description = "Controls how hits are exposed in outputs. FETCH returns all hits in the response. FETCH_ONE returns only the first hit. STORE writes hits to Kestra storage and returns a URI. NONE leaves outputs empty."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    private static final TypeReference<DeelPage<DeelInvoice>> INVOICES_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Map<String, Object> params = buildQueryParams(runContext);

        int rLimit = runContext.render(this.limit).as(Integer.class).orElse(25);
        int rOffset = runContext.render(this.offset).as(Integer.class).orElse(0);
        String rCursor = runContext.render(this.cursor).as(String.class).orElse(null);

        params.put("limit", rLimit);
        params.put("offset", rOffset);
        if (rCursor != null && !rCursor.isBlank()) {
            params.put("cursor", rCursor);
        }

        DeelPage<DeelInvoice> page = request(
            runContext,
            "/invoices",
            "GET",
            params,
            INVOICES_PAGE_TYPE_REF
        );

        java.util.List<DeelInvoice> invoices = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
        DeelPagination pagination = page != null ? page.getPage() : null;

        logger.debug("Retrieved {} invoices (total: {})", invoices.size(), pagination != null ? pagination.getTotalRows() : "unknown");

        // Extract next cursor for pagination
        String nextCursor = pagination != null && pagination.getCursor() != null ? pagination.getCursor() : null;

        FetchType resolvedFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        return handleFetch(runContext, invoices, pagination, resolvedFetchType, nextCursor);
    }

    private Map<String, Object> buildQueryParams(RunContext runContext) throws Exception {
        Map<String, Object> params = new HashMap<>();

        runContext.render(this.status).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("status", v));
        runContext.render(this.contractId).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("contract_id", v));
        runContext.render(this.issuedFrom).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("issued_from_date", v));
        runContext.render(this.issuedTo).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("issued_to_date", v));

        return params;
    }

    private Output handleFetch(RunContext runContext, java.util.List<DeelInvoice> invoices, DeelPagination pagination, FetchType fetchType, String nextCursor) throws Exception {
        java.util.List<Map<String, Object>> mapped = invoices.stream()
            .map(this::invoiceToMap)
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

    private Map<String, Object> invoiceToMap(DeelInvoice invoice) {
        return DeelInvoice.toMap(invoice);
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
            title = "Fetched invoices",
            description = "Available only when fetchType=FETCH; contains invoices for the current response page."
        )
        private java.util.List<Map<String, Object>> rows;

        @Schema(
            title = "First invoice",
            description = "Available only when fetchType=FETCH_ONE; contains the first invoice."
        )
        private Map<String, Object> row;

        @Schema(
            title = "Stored invoices URI",
            description = "Available only when fetchType=STORE; Kestra internal storage path to the Ion file."
        )
        private URI uri;

        @Schema(
            title = "Next page cursor",
            description = "Cursor for the next page of results. Returned when more pages are available."
        )
        private String nextCursor;
    }
}
