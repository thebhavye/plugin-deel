package io.kestra.plugin.deel.timeoff;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import jakarta.validation.constraints.NotNull;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.deel.connection.AbstractDeelConnection;
import io.kestra.plugin.deel.model.DeelTimeOff;
import io.kestra.plugin.deel.model.DeelTimeOffPage;
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
    title = "List Time Off Requests",
    description = "List time-off requests for a single worker profile. Requires the time-off:read scope. Supports cursor-based pagination via page size and next token."
)
@Plugin(
    examples = {
        @Example(
            title = "List time-off requests for a profile",
            full = true,
            code = """
                id: list_time_offs
                namespace: company.team
                tasks:
                  - id: list_time_offs
                    type: io.kestra.plugin.deel.timeoff.List
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    hrisProfileId: "d290f1ee-6c54-4b01-90e6-d701748f0851"
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "List approved time off in a date range",
            full = true,
            code = """
                id: list_approved_time_offs
                namespace: company.team
                tasks:
                  - id: list_time_offs
                    type: io.kestra.plugin.deel.timeoff.List
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    hrisProfileId: "d290f1ee-6c54-4b01-90e6-d701748f0851"
                    status: "APPROVED"
                    startDate: "2024-01-01"
                    endDate: "2024-12-31"
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractDeelConnection implements RunnableTask<List.Output> {

    @Schema(
        title = "HRIS profile ID",
        description = "Worker HRIS profile id identifying the profile whose time-off requests are returned."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> hrisProfileId;

    @Schema(
        title = "Filter by status",
        description = "Time off status (REQUESTED, APPROVED, REJECTED, USED, CANCELED)."
    )
    @PluginProperty(group = "main")
    private Property<String> status;

    @Schema(
        title = "Filter by start date",
        description = "Start date of time off."
    )
    @PluginProperty(group = "main")
    private Property<String> startDate;

    @Schema(
        title = "Filter by end date",
        description = "End date of time off."
    )
    @PluginProperty(group = "main")
    private Property<String> endDate;

    @Schema(
        title = "Page size",
        description = "Number of time-off requests to return per page."
    )
    @PluginProperty(group = "main")
    private Property<Integer> pageSize;

    @Schema(
        title = "Next page cursor",
        description = "Cursor for the next page, as returned by a previous response."
    )
    @PluginProperty(group = "main")
    private Property<String> next;

    @Schema(
        title = "Result handling mode",
        description = "Controls how hits are exposed in outputs. FETCH returns all hits in the response. FETCH_ONE returns only the first hit. STORE writes hits to Kestra storage and returns a URI. NONE leaves outputs empty."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    private static final TypeReference<DeelTimeOffPage> TIME_OFF_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String rProfileId = runContext.render(this.hrisProfileId).as(String.class).orElseThrow(() -> new IllegalArgumentException("hrisProfileId is required"));
        Map<String, Object> params = buildQueryParams(runContext);

        DeelTimeOffPage page = request(
            runContext,
            "/time_offs/profile/" + rProfileId,
            "GET",
            params,
            TIME_OFF_PAGE_TYPE_REF
        );

        java.util.List<DeelTimeOff> timeOffs = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
        Long total = page != null && page.getCount() != null ? page.getCount().longValue() : (long) timeOffs.size();

        // Extract next cursor for pagination
        String nextCursor = page.getNext();

        logger.debug("Retrieved {} time-off requests (total: {})", timeOffs.size(), total);

        FetchType resolvedFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        return handleFetch(runContext, timeOffs, total, resolvedFetchType, nextCursor);
    }

    private Map<String, Object> buildQueryParams(RunContext runContext) throws Exception {
        Map<String, Object> params = new HashMap<>();

        runContext.render(this.status).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("status", v));
        runContext.render(this.startDate).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("start_date", v));
        runContext.render(this.endDate).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("end_date", v));
        runContext.render(this.pageSize).as(Integer.class).ifPresent(v -> params.put("page_size", v));
        runContext.render(this.next).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("next", v));

        return params;
    }

    private Output handleFetch(RunContext runContext, java.util.List<DeelTimeOff> timeOffs, Long total, FetchType fetchType, String nextCursor) throws Exception {
        java.util.List<Map<String, Object>> mapped = timeOffs.stream()
            .map(this::timeOffToMap)
            .toList();

        return switch (fetchType) {
            case FETCH -> Output.builder()
                .rows(mapped)
                .size(mapped.size())
                .total(total)
                .nextCursor(nextCursor)
                .next(nextCursor)
                .build();
            case FETCH_ONE -> Output.builder()
                .row(mapped.isEmpty() ? null : mapped.getFirst())
                .size(mapped.isEmpty() ? 0 : 1)
                .total(total)
                .nextCursor(nextCursor)
                .next(nextCursor)
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
                    .nextCursor(nextCursor)
                    .next(nextCursor)
                    .build();
            }
            case NONE -> Output.builder()
                .size(0)
                .total(total)
                .nextCursor(nextCursor)
                .next(nextCursor)
                .build();
        };
    }

    private Map<String, Object> timeOffToMap(DeelTimeOff timeOff) {
        return DeelTimeOff.toMap(timeOff);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Number of time-off requests returned in this response",
            description = "Number of time-off requests included in outputs for the selected fetch type."
        )
        private Integer size;

        @Schema(
            title = "Total number of time-off requests",
            description = "Total time-off requests reported by the API, regardless of pagination."
        )
        private Long total;

        @Schema(
            title = "Fetched time-off requests",
            description = "Available only when fetchType=FETCH; contains time-off requests for the current response page."
        )
        private java.util.List<Map<String, Object>> rows;

        @Schema(
            title = "First time-off request",
            description = "Available only when fetchType=FETCH_ONE; contains the first time-off request."
        )
        private Map<String, Object> row;

        @Schema(
            title = "Stored time-off requests URI",
            description = "Available only when fetchType=STORE; Kestra internal storage path to the Ion file."
        )
        private URI uri;

        @Schema(
            title = "Next page cursor",
            description = "Cursor for the next page of results. Returned when more pages are available."
        )
        private String nextCursor;

        @Schema(
            title = "Next page token",
            description = "Token for the next page, as returned by the API."
        )
        private String next;
    }
}
