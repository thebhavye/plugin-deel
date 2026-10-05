package io.kestra.plugin.deel.lookups;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.deel.connection.AbstractDeelConnection;
import io.kestra.plugin.deel.model.DeelCountry;
import io.kestra.plugin.deel.model.DeelCurrency;
import io.kestra.plugin.deel.model.DeelJobTitle;
import io.kestra.plugin.deel.model.DeelListResponse;
import io.kestra.plugin.deel.model.DeelPage;
import io.kestra.plugin.deel.model.DeelSeniority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "Get Lookup Values",
    description = "Get platform reference data (countries, currencies, job titles, seniority levels). Each lookup type calls its documented endpoint."
)
@Plugin(
    examples = {
        @Example(
            title = "Get supported currencies",
            full = true,
            code = """
                id: get_currencies
                namespace: company.team
                tasks:
                  - id: get_currencies
                    type: io.kestra.plugin.deel.lookups.Get
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    lookupType: CURRENCIES
                """
        ),
        @Example(
            title = "Get seniority levels",
            full = true,
            code = """
                id: get_seniorities
                namespace: company.team
                tasks:
                  - id: get_seniorities
                    type: io.kestra.plugin.deel.lookups.Get
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    lookupType: SENIORITIES
                    isEorContract: false
                """
        )
    }
)
public class Get extends AbstractDeelConnection implements RunnableTask<Get.Output> {

    @Schema(
        title = "Lookup type",
        description = "Reference data to retrieve. Each type calls its own documented endpoint."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<LookupType> lookupType;

    @Schema(
        title = "EOR contract flag",
        description = "Applies only to SENIORITIES. When true, C-level seniorities are excluded. Defaults to true."
    )
    @PluginProperty(group = "main")
    private Property<Boolean> isEorContract;

    @Schema(
        title = "Cursor for pagination",
        description = "Applies only to JOB_TITLES, which paginates via after_cursor."
    )
    @PluginProperty(group = "main")
    private Property<String> afterCursor;

    public enum LookupType {
        COUNTRIES,
        CURRENCIES,
        JOB_TITLES,
        SENIORITIES
    }

    private static final TypeReference<DeelListResponse<DeelCountry>> COUNTRIES_TYPE_REF = new TypeReference<>() {};
    private static final TypeReference<DeelListResponse<DeelCurrency>> CURRENCIES_TYPE_REF = new TypeReference<>() {};
    private static final TypeReference<DeelPage<DeelJobTitle>> JOB_TITLES_TYPE_REF = new TypeReference<>() {};
    private static final TypeReference<DeelListResponse<DeelSeniority>> SENIORITIES_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        LookupType renderedType = runContext.render(this.lookupType).as(LookupType.class).orElseThrow();

        List<Map<String, Object>> rows = new ArrayList<>();
        String nextCursor = null;

        switch (renderedType) {
            case COUNTRIES -> rows = fetchCountries(runContext, logger);
            case CURRENCIES -> rows = fetchCurrencies(runContext, logger);
            case JOB_TITLES -> {
                JobTitlesResult result = fetchJobTitles(runContext, logger);
                rows = result.rows;
                nextCursor = result.nextCursor;
            }
            case SENIORITIES -> rows = fetchSeniorities(runContext, logger);
        }

        return Output.builder()
            .lookupType(renderedType)
            .rows(rows)
            .size(rows.size())
            .nextCursor(nextCursor)
            .build();
    }

    private List<Map<String, Object>> fetchCountries(RunContext runContext, Logger logger) throws Exception {
        DeelListResponse<DeelCountry> response = request(
            runContext,
            "/lookups/countries",
            "GET",
            Map.of(),
            COUNTRIES_TYPE_REF
        );

        List<DeelCountry> countries = response != null && response.getData() != null ? response.getData() : new ArrayList<>();
        logger.debug("Retrieved {} countries", countries.size());

        return countries.stream()
            .map(country -> {
                Map<String, Object> map = new HashMap<>();
                map.put("code", country.getCode());
                map.put("name", country.getName());
                map.put("states", country.getStates());
                map.put("state_type", country.getStateType());
                map.put("eor_support", country.getEorSupport());
                map.put("visa_support", country.getVisaSupport());
                map.put("default_currency", country.getDefaultCurrency());
                return map;
            })
            .toList();
    }

    private List<Map<String, Object>> fetchCurrencies(RunContext runContext, Logger logger) throws Exception {
        DeelListResponse<DeelCurrency> response = request(
            runContext,
            "/lookups/currencies",
            "GET",
            Map.of(),
            CURRENCIES_TYPE_REF
        );

        List<DeelCurrency> currencies = response != null && response.getData() != null ? response.getData() : new ArrayList<>();
        logger.debug("Retrieved {} currencies", currencies.size());

        return currencies.stream()
            .map(currency -> {
                Map<String, Object> map = new HashMap<>();
                map.put("code", currency.getCode());
                map.put("name", currency.getName());
                return map;
            })
            .toList();
    }

    private static class JobTitlesResult {
        List<Map<String, Object>> rows;
        String nextCursor;

        JobTitlesResult(List<Map<String, Object>> rows, String nextCursor) {
            this.rows = rows;
            this.nextCursor = nextCursor;
        }
    }

    private JobTitlesResult fetchJobTitles(RunContext runContext, Logger logger) throws Exception {
        Map<String, Object> params = new HashMap<>();
        runContext.render(this.afterCursor).as(String.class).filter(s -> !s.isBlank()).ifPresent(v -> params.put("after_cursor", v));

        DeelPage<DeelJobTitle> page = request(
            runContext,
            "/lookups/job-titles",
            "GET",
            params,
            JOB_TITLES_TYPE_REF
        );

        List<DeelJobTitle> jobTitles = page != null && page.getData() != null ? page.getData() : new ArrayList<>();
        logger.debug("Retrieved {} job titles", jobTitles.size());

        String nextCursor = null;
        if (page.getPage() != null && page.getPage().getCursor() != null) {
            nextCursor = page.getPage().getCursor();
        }

        List<Map<String, Object>> rows = jobTitles.stream()
            .map(jobTitle -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", jobTitle.getId());
                map.put("name", jobTitle.getName());
                return map;
            })
            .toList();

        return new JobTitlesResult(rows, nextCursor);
    }

    private List<Map<String, Object>> fetchSeniorities(RunContext runContext, Logger logger) throws Exception {
        Map<String, Object> params = new HashMap<>();
        runContext.render(this.isEorContract).as(Boolean.class).ifPresent(v -> params.put("is_eor_contract", v));

        DeelListResponse<DeelSeniority> response = request(
            runContext,
            "/lookups/seniorities",
            "GET",
            params,
            SENIORITIES_TYPE_REF
        );

        List<DeelSeniority> seniorities = response != null && response.getData() != null ? response.getData() : new ArrayList<>();
        logger.debug("Retrieved {} seniority levels", seniorities.size());

        return seniorities.stream()
            .map(seniority -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", seniority.getId());
                map.put("name", seniority.getName());
                map.put("level", seniority.getLevel());
                return map;
            })
            .toList();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Lookup type",
            description = "The reference data type that was retrieved."
        )
        private LookupType lookupType;

        @Schema(
            title = "Number of lookup values",
            description = "Number of lookup values included in the output."
        )
        private Integer size;

        @Schema(
            title = "Lookup values",
            description = "The retrieved reference data."
        )
        private List<Map<String, Object>> rows;

        @Schema(
            title = "Next page cursor",
            description = "Cursor for the next page of results. Returned when more pages are available. Only applicable to JOB_TITLES lookup type."
        )
        private String nextCursor;
    }
}
