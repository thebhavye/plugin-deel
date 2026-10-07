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
import io.kestra.plugin.deel.model.DeelManager;
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
    title = "List Managers",
    description = "List managers in the organization. Requires the organizations:read scope. Supports offset-based pagination."
)
@Plugin(
    examples = {
        @Example(
            title = "List managers",
            full = true,
            code = """
                id: list_managers
                namespace: company.team
                tasks:
                  - id: list_managers
                    type: io.kestra.plugin.deel.org.Managers
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    limit: 25
                    fetchType: FETCH
                """
        )
    }
)
public class Managers extends AbstractDeelConnection implements RunnableTask<Managers.Output> {

    @Schema(
        title = "Maximum number of managers per page",
        description = "The number of records to return in the response."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Integer> limit = Property.ofValue(25);

    @Schema(
        title = "Offset for pagination",
        description = "The offset or starting point for pagination. Default is 0."
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

    private static final TypeReference<DeelPage<DeelManager>> MANAGERS_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        int rLimit = runContext.render(this.limit).as(Integer.class).orElse(25);
        int rOffset = runContext.render(this.offset).as(Integer.class).orElse(0);

        Map<String, Object> params = new HashMap<>();
        params.put("limit", rLimit);
        params.put("offset", rOffset);

        DeelPage<DeelManager> page = request(
            runContext,
            "/managers",
            "GET",
            params,
            MANAGERS_PAGE_TYPE_REF
        );

        List<DeelManager> managers = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
        DeelPagination pagination = page != null ? page.getPage() : null;

        logger.debug("Retrieved {} managers (total: {})", managers.size(), pagination != null ? pagination.getTotalRows() : "unknown");

        FetchType resolvedFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        return handleFetch(runContext, managers, pagination, resolvedFetchType);
    }

    private Output handleFetch(RunContext runContext, List<DeelManager> managers, DeelPagination pagination, FetchType fetchType) throws Exception {
        List<Map<String, Object>> mapped = managers.stream()
            .map(manager -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", manager.getId());
                map.put("email", manager.getEmail());
                map.put("first_name", manager.getFirstName());
                map.put("last_name", manager.getLastName());
                return map;
            })
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

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Number of managers returned in this response",
            description = "Number of managers included in outputs for the selected fetch type."
        )
        private Integer size;

        @Schema(
            title = "Total number of managers",
            description = "Total managers reported by the API, regardless of pagination."
        )
        private Long total;

        @Schema(
            title = "Fetched managers",
            description = "Available only when fetchType=FETCH; contains managers for the current response page."
        )
        private List<Map<String, Object>> rows;

        @Schema(
            title = "First manager",
            description = "Available only when fetchType=FETCH_ONE; contains the first manager."
        )
        private Map<String, Object> row;

        @Schema(
            title = "Stored managers URI",
            description = "Available only when fetchType=STORE; Kestra internal storage path to the Ion file."
        )
        private URI uri;
    }
}
