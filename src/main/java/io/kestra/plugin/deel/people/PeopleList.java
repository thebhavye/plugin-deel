package io.kestra.plugin.deel.people;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.deel.connection.AbstractDeelConnection;
import io.kestra.plugin.deel.model.DeelPage;
import io.kestra.plugin.deel.model.DeelPerson;
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
import java.util.function.Function;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "List People",
    description = "List people in the organization with their employment details. Supports offset-based pagination."
)
@Plugin(
    examples = {
        @Example(
            title = "List all people",
            full = true,
            code = """
                id: list_people
                namespace: company.team
                tasks:
                  - id: list_people
                    type: io.kestra.plugin.deel.people.PeopleList
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    limit: 50
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "List active people with search",
            full = true,
            code = """
                id: list_active_people
                namespace: company.team
                tasks:
                  - id: list_people
                    type: io.kestra.plugin.deel.people.PeopleList
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    hiringStatus: "active"
                    search: "John"
                    limit: 25
                    fetchType: STORE
                """
        )
    }
)
public class PeopleList extends AbstractDeelConnection implements RunnableTask<PeopleList.Output> {

    @Schema(
        title = "Maximum number of people per page",
        description = "Number of people to return per page (max 100). Default is 25."
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
        title = "Filter by hiring status",
        description = "Filter people by hiring status (e.g., active, onboarding, offboarding, inactive)."
    )
    @PluginProperty(group = "main")
    private Property<String> hiringStatus;

    @Schema(
        title = "Filter by hiring type",
        description = "Filter people by hiring type (e.g., employee, contractor, eor)."
    )
    @PluginProperty(group = "main")
    private Property<String> hiringType;

    @Schema(
        title = "Filter by legal entity ID",
        description = "Filter people by legal entity ID."
    )
    @PluginProperty(group = "main")
    private Property<String> legalEntityId;

    @Schema(
        title = "Search term",
        description = "Search people by name, email, or other fields."
    )
    @PluginProperty(group = "main")
    private Property<String> search;

    @Schema(
        title = "Filter by updated since",
        description = "Filter people updated since the given timestamp (ISO 8601 format)."
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

    // TypeReference for DeelPage<DeelPerson> to preserve generic type information
    private static final TypeReference<DeelPage<DeelPerson>> PEOPLE_PAGE_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Map<String, Object> params = buildQueryParams(runContext);

        int rLimit = runContext.render(this.limit).as(Integer.class).orElse(25);
        int rOffset = runContext.render(this.offset).as(Integer.class).orElse(0);

        params.put("limit", Math.min(rLimit, 100));
        params.put("offset", rOffset);

        DeelPage<DeelPerson> page = request(
            runContext,
            "/v2/people",
            "GET",
            params,
            PEOPLE_PAGE_TYPE_REF
        );

        List<DeelPerson> people = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
        DeelPagination pagination = page != null ? page.getPage() : null;

        logger.debug("Retrieved {} people (total: {})", people.size(), pagination != null ? pagination.getTotalRows() : "unknown");

        FetchType resolvedFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        return handleFetch(runContext, people, pagination, resolvedFetchType);
    }

    private Map<String, Object> buildQueryParams(RunContext runContext) throws Exception {
        Map<String, Object> params = new HashMap<>();

        runContext.render(this.hiringStatus).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("hiring_status", v));
        runContext.render(this.hiringType).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("hiring_type", v));
        runContext.render(this.legalEntityId).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("legal_entity_id", v));
        runContext.render(this.search).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("search", v));
        runContext.render(this.updatedSince).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("updated_since", v));

        return params;
    }

    private Output handleFetch(RunContext runContext, List<DeelPerson> people, DeelPagination pagination, FetchType fetchType) throws Exception {
        List<Map<String, Object>> mapped = people.stream()
            .map(this::personToMap)
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

    private Map<String, Object> personToMap(DeelPerson person) {
        return DeelPerson.toMap(person);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Number of people returned in this response",
            description = "Number of people included in outputs for the selected fetch type."
        )
        private Integer size;

        @Schema(
            title = "Total number of people",
            description = "Total people reported by the API, regardless of pagination."
        )
        private Long total;

        @Schema(
            title = "Fetched people",
            description = "Available only when fetchType=FETCH; contains people for the current response page."
        )
        private List<Map<String, Object>> rows;

        @Schema(
            title = "First person",
            description = "Available only when fetchType=FETCH_ONE; contains the first person."
        )
        private Map<String, Object> row;

        @Schema(
            title = "Stored people URI",
            description = "Available only when fetchType=STORE; Kestra internal storage path to the Ion file."
        )
        private URI uri;
    }
}