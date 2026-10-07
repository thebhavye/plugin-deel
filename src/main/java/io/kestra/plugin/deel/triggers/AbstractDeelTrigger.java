package io.kestra.plugin.deel.triggers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.BearerAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Output;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared base class for Deel polling triggers.
 *
 * <p>Triggers cannot extend {@link io.kestra.plugin.deel.connection.AbstractDeelConnection}
 * (Java single inheritance requires triggers to extend {@link AbstractTrigger}), so the
 * connection properties and HTTP behavior are mirrored here with identical semantics:
 * Bearer authentication, {@code X-Version} header and the same error mapping.
 *
 * <p>Polling state (watermark, last-seen statuses and queued events) is persisted in the
 * Kestra KV store, scoped to the flow namespace, so it survives restarts.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractDeelTrigger extends AbstractTrigger {

    public static final String DEFAULT_BASE_URL = "https://api.letsdeel.com/rest";
    public static final String DEFAULT_API_VERSION = "2026-01-01";
    public static final String API_VERSION_HEADER = "X-Version";

    @Schema(
        title = "Deel API base URL",
        description = "Base URL for the Deel API. Use https://api-sandbox.demo.deel.com/rest for sandbox environment."
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(
        title = "Deel API token",
        description = "API token for authentication. Can be an organization token, personal token, or worker token."
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @NotNull
    // NOTE: no @NotBlank here (unlike tasks). Trigger-bound RunContexts validate the whole
    // trigger bean on every property render, and the unit-test validator cannot apply
    // CharSequence constraints to Property fields. Nulls are still rejected.
    private Property<String> apiToken;

    @Schema(
        title = "API version",
        description = "API version header value. Defaults to 2026-01-01."
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> apiVersion = Property.ofValue(DEFAULT_API_VERSION);

    @Schema(
        title = "Polling interval",
        description = "How often the Deel API is polled for new events."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Duration interval = Duration.ofMinutes(5);

    /**
     * Polling interval, as required by {@link io.kestra.core.models.triggers.PollingTriggerInterface}.
     */
    public Duration getInterval() {
        return this.interval;
    }

    protected HttpClient createClient(RunContext runContext) throws Exception {
        String rToken = runContext.render(this.apiToken).as(String.class).orElseThrow(() -> new IllegalArgumentException("apiToken is required"));

        HttpConfiguration httpConfiguration = HttpConfiguration.builder()
            .auth(BearerAuthConfiguration.builder()
                .token(Property.ofValue(rToken))
                .build())
            .build();

        return new HttpClient(runContext, httpConfiguration);
    }

    protected URI buildUri(String baseUrl, String path, Map<String, Object> queryParams) throws URISyntaxException {
        StringBuilder uriBuilder = new StringBuilder(baseUrl);
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            uriBuilder.append("/");
        }
        uriBuilder.append(path);

        if (queryParams != null && !queryParams.isEmpty()) {
            uriBuilder.append("?");
            boolean first = true;
            for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
                if (entry.getValue() != null) {
                    if (!first) {
                        uriBuilder.append("&");
                    }
                    uriBuilder.append(entry.getKey())
                        .append("=")
                        .append(encodeValue(entry.getValue().toString()));
                    first = false;
                }
            }
        }

        return new URI(uriBuilder.toString());
    }

    private String encodeValue(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 encoding not supported", e);
        }
    }

    @SuppressWarnings("unchecked")
    protected <T> T request(RunContext runContext, String path, String method, Map<String, Object> queryParams, TypeReference<T> typeRef) throws Exception {
        try (HttpClient client = createClient(runContext)) {
            String rBaseUrl = runContext.render(this.baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
            String rApiVersion = runContext.render(this.apiVersion).as(String.class).orElse(DEFAULT_API_VERSION);
            URI uri = buildUri(rBaseUrl, path, queryParams);

            HttpRequest request = HttpRequest.builder()
                .method(method)
                .uri(uri)
                .addHeader(API_VERSION_HEADER, rApiVersion)
                .build();

            try {
                HttpResponse<String> response = client.request(request, String.class);
                return handleResponse(response, typeRef);
            } catch (HttpClientException e) {
                throw handleErrorResponse(e);
            }
        }
    }

    private <T> T handleResponse(HttpResponse<String> response, TypeReference<T> typeRef) throws IOException {
        int statusCode = response.getStatus().getCode();

        if (statusCode >= 200 && statusCode < 300) {
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                return null;
            }
            return JacksonMapper.ofJson().readValue(body, typeRef);
        }

        String body = response.getBody() != null ? response.getBody() : "";
        throw new IllegalStateException("HTTP " + statusCode + (body.isBlank() ? "" : ": " + body));
    }

    private Exception handleErrorResponse(HttpClientException e) {
        int statusCode = extractStatusCode(e);

        if (statusCode == 401) {
            return new IllegalStateException("Authentication failed (401): Invalid or expired API token", e);
        } else if (statusCode == 403) {
            return new IllegalStateException("Access forbidden (403): Token may not have required scopes or permissions", e);
        } else if (statusCode == 429) {
            return new IllegalStateException("Rate limited (429): Too many requests. Implement backoff.", e);
        } else if (statusCode == 404) {
            return new IllegalStateException("Not found (404): the requested Deel resource does not exist or is not visible to this token", e);
        } else if (statusCode >= 500 && statusCode <= 599) {
            return new IllegalStateException("Server error: " + e.getMessage(), e);
        }

        return new IllegalStateException(extractMessage(e), e);
    }

    private static int extractStatusCode(HttpClientException e) {
        if (e instanceof HttpClientResponseException responseException
            && responseException.getResponse() != null
            && responseException.getResponse().getStatus() != null) {
            return responseException.getResponse().getStatus().getCode();
        }
        return 0;
    }

    private static String extractMessage(HttpClientException e) {
        return e.getMessage();
    }

    /**
     * Persisted polling state: watermark plus per-record bookkeeping.
     * Stored as JSON in the namespace KV store so it survives restarts.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    @NoArgsConstructor
    public static class TriggerState {
        /** Highest record timestamp observed so far (ISO-8601). Null until the first poll. */
        private String watermark;
        /** Last emitted status per record id, used for status-change detection. */
        private Map<String, String> statuses = new HashMap<>();
        /** Record ids already emitted (for records without usable timestamps). */
        private List<String> seen = new ArrayList<>();
        /** Detected events not yet emitted as executions. */
        private List<Map<String, Object>> pending = new ArrayList<>();

        public void setWatermark(String watermark) {
            this.watermark = watermark;
        }

        public void setStatuses(Map<String, String> statuses) {
            this.statuses = statuses != null ? statuses : new HashMap<>();
        }

        public void setSeen(List<String> seen) {
            this.seen = seen != null ? seen : new ArrayList<>();
        }

        public void setPending(List<Map<String, Object>> pending) {
            this.pending = pending != null ? pending : new ArrayList<>();
        }
    }

    /**
     * KV key holding this trigger's state. The flow id is length-prefixed so that ids containing
     * the "-" separator can never collide (e.g. flow "a-b" + trigger "c" vs flow "a" + trigger "b-c").
     */
    protected String stateKey(TriggerContext context) {
        String flowId = context.getFlowId();
        return "deel-trigger-" + flowId.length() + "-" + flowId + "-" + context.getTriggerId();
    }

    protected TriggerState loadState(RunContext runContext, TriggerContext context) throws Exception {
        KVStore kvStore = runContext.namespaceKv(context.getNamespace());
        Optional<KVValue> stored = kvStore.getValue(stateKey(context));
        if (stored.isEmpty() || stored.get().value() == null) {
            return new TriggerState();
        }
        return JacksonMapper.ofJson().readValue(
            stored.get().value().toString(),
            new TypeReference<TriggerState>() {}
        );
    }

    protected void saveState(RunContext runContext, TriggerContext context, TriggerState state) throws Exception {
        KVStore kvStore = runContext.namespaceKv(context.getNamespace());
        String json = JacksonMapper.ofJson().writeValueAsString(state);
        kvStore.put(
            stateKey(context),
            new KVValueAndMetadata(new KVMetadata("Deel trigger polling state", (Duration) null), json)
        );
    }

    protected Execution buildExecution(ConditionContext conditionContext, TriggerContext context, Output output) {
        return TriggerService.generateExecution(this, conditionContext, context, output);
    }

    /**
     * Compare ISO-8601 timestamps. Supports both date-time and date-only values.
     * Nulls sort last; unparseable values fall back to lexicographic comparison.
     */
    protected static int compareTimestamps(String left, String right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        Long leftEpoch = toEpochMilliLenient(left);
        Long rightEpoch = toEpochMilliLenient(right);
        if (leftEpoch != null && rightEpoch != null) {
            return leftEpoch.compareTo(rightEpoch);
        }
        return left.compareTo(right);
    }

    protected static Long toEpochMilliLenient(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value.trim()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (DateTimeParseException ex) {
                return null;
            }
        }
    }

    protected static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
