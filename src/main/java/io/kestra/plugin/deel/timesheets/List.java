package io.kestra.plugin.deel.timesheets;

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
import io.kestra.plugin.deel.model.DeelPage;
import io.kestra.plugin.deel.model.DeelPagination;
import io.kestra.plugin.deel.model.DeelTimesheet;
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
    title = "List Timesheets",
    description = "List timesheets in the account. Requires the timesheets:read scope. Supports offset-based pagination."
)
@Plugin(
    examples = {
        @Example(
            title = "List timesheets",
            full = true,
            code = """
                id: list_timesheets
                namespace: company.team
                tasks:
                  - id: list_timesheets
                    type: io.kestra.plugin.deel.timesheets.List
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    limit: 25
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "List approved timesheets for a contract in a date range",
            full = true,
            code = """
                id: list_contract_timesheets
                namespace: company.team
                tasks:
                  - id: list_timesheets
                    type: io.kestra.plugin.deel.timesheets.List
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    contractId: "contract_abc123"
                    status: "approved"
                    dateFrom: "2024-01-01"
                    dateTo: "2024-12-31"
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractDeelConnection implements RunnableTask<List.Output> {

    @Schema(
        title = "Maximum number of timesheets per page",
        description = "Maximum number of records to return per page."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Integer> limit = Property.ofValue(25);

    @Schema(
        title = "Offset for pagination",
        description = "Number of records to skip before starting to return results. Default is 0."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Integer> offset = Property.ofValue(0);

    @Schema(
        title = "Filter by contract ID",
        description = "Filter results to timesheets belonging to this Deel contract ID."
    )
    @PluginProperty(group = "main")
    private Property<String> contractId;

    @Schema(
        title = "Filter by status",
        description = "Filter results to timesheets with the specified status (approved, declined, not_payable, paid, pending, processing)."
    )
    @PluginProperty(group = "main")
    private Property<String> status;

    @Schema(
        title = "Filter by submission start date",
        description = "Filter results to timesheets submitted on or after this date (YYYY-MM-DD)."
    )
    @PluginProperty(group = "main")
    private Property<String> dateFrom;

    @Schema(
        title = "Filter by submission end date",
        description = "Filter results to timesheets submitted before this date (YYYY-MM-DD)."
    )
    @PluginProperty(group = "main")
    private Property<String> dateTo;

    @Schema(
        title = "Result handling mode",
        description = "Controls how hits are exposed in outputs. FETCH returns all hits in the response. FETCH_ONE returns only the first hit. STORE writes hits to Kestra storage and returns a URI. NONE leaves outputs empty."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    private static final TypeReference<DeelPage<DeelTimesheet>> TIMESHEETS_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Map<String, Object> params = buildQueryParams(runContext);

        int renderedLimit = runContext.render(this.limit).as(Integer.class).orElse(25);
        int renderedOffset = runContext.render(this.offset).as(Integer.class).orElse(0);

        params.put("limit", renderedLimit);
        params.put("offset", renderedOffset);

        DeelPage<DeelTimesheet> page = request(
            runContext,
            "/timesheets",
            "GET",
            params,
            TIMESHEETS_PAGE_TYPE_REF
        );

        java.util.List<DeelTimesheet> timesheets = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
        DeelPagination pagination = page != null ? page.getPage() : null;

        logger.debug("Retrieved {} timesheets (total: {})", timesheets.size(), pagination != null ? pagination.getTotalRows() : "unknown");

        FetchType resolvedFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        return handleFetch(runContext, timesheets, pagination, resolvedFetchType);
    }

    private Map<String, Object> buildQueryParams(RunContext runContext) throws Exception {
        Map<String, Object> params = new HashMap<>();

        runContext.render(this.contractId).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("contract_id", v));
        runContext.render(this.status).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("statuses", v));
        runContext.render(this.dateFrom).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("date_from", v));
        runContext.render(this.dateTo).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("date_to", v));

        return params;
    }

    private Output handleFetch(RunContext runContext, java.util.List<DeelTimesheet> timesheets, DeelPagination pagination, FetchType fetchType) throws Exception {
        java.util.List<Map<String, Object>> mapped = timesheets.stream()
            .map(this::timesheetToMap)
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

    private Map<String, Object> timesheetToMap(DeelTimesheet timesheet) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", timesheet.getId());
        map.put("type", timesheet.getType());
        map.put("status", timesheet.getStatus());
        map.put("contract", timesheet.getContract());
        map.put("quantity", timesheet.getQuantity());
        map.put("worksheet", timesheet.getWorksheet());
        map.put("created_at", timesheet.getCreatedAt());
        map.put("description", timesheet.getDescription());
        map.put("reported_by", timesheet.getReportedBy());
        map.put("total_amount", timesheet.getTotalAmount());
        map.put("currency_code", timesheet.getCurrencyCode());
        map.put("date_submitted", timesheet.getDateSubmitted());
        map.put("scale", timesheet.getScale());
        map.put("attachment", timesheet.getAttachment());
        map.put("reviewed_by", timesheet.getReviewedBy());
        map.put("custom_scale", timesheet.getCustomScale());
        map.put("payment_cycle", timesheet.getPaymentCycle());
        map.put("hourly_report_preset", timesheet.getHourlyReportPreset());
        return map;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Number of timesheets returned in this response",
            description = "Number of timesheets included in outputs for the selected fetch type."
        )
        private Integer size;

        @Schema(
            title = "Total number of timesheets",
            description = "Total timesheets reported by the API, regardless of pagination."
        )
        private Long total;

        @Schema(
            title = "Fetched timesheets",
            description = "Available only when fetchType=FETCH; contains timesheets for the current response page."
        )
        private java.util.List<Map<String, Object>> rows;

        @Schema(
            title = "First timesheet",
            description = "Available only when fetchType=FETCH_ONE; contains the first timesheet."
        )
        private Map<String, Object> row;

        @Schema(
            title = "Stored timesheets URI",
            description = "Available only when fetchType=STORE; Kestra internal storage path to the Ion file."
        )
        private URI uri;
    }
}
