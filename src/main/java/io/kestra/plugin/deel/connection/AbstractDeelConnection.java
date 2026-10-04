package io.kestra.plugin.deel.connection;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.BearerAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import io.kestra.core.models.annotations.PluginProperty;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractDeelConnection extends Task {

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
    private Property<String> apiToken;

    @Schema(
        title = "API version",
        description = "API version header value. Defaults to 2026-01-01."
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> apiVersion = Property.ofValue(DEFAULT_API_VERSION);

    protected HttpClient createClient(RunContext runContext) throws Exception {
        String renderedBaseUrl = runContext.render(this.baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
        String renderedToken = runContext.render(this.apiToken).as(String.class).orElseThrow();
        String renderedApiVersion = runContext.render(this.apiVersion).as(String.class).orElse(DEFAULT_API_VERSION);

        HttpConfiguration httpConfiguration = HttpConfiguration.builder()
            .auth(BearerAuthConfiguration.builder()
                .token(Property.ofValue(renderedToken))
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

    /**
     * Execute an HTTP request and deserialize the response using a TypeReference to preserve generic type information.
     * This method avoids type erasure issues with generic types like DeelPage<DeelPerson>.
     */
    @SuppressWarnings("unchecked")
    protected <T> T request(RunContext runContext, String path, String method, Map<String, Object> queryParams, TypeReference<T> typeRef) throws Exception {
        try (HttpClient client = createClient(runContext)) {
            String renderedBaseUrl = runContext.render(this.baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
            String renderedApiVersion = runContext.render(this.apiVersion).as(String.class).orElse(DEFAULT_API_VERSION);
            URI uri = buildUri(renderedBaseUrl, path, queryParams);

            HttpRequest request = HttpRequest.builder()
                .method(method)
                .uri(uri)
                .addHeader(API_VERSION_HEADER, renderedApiVersion)
                .build();

            try {
                // Use the HttpClient overload that returns raw response, then deserialize with TypeReference
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
        } else if (statusCode >= 500 && statusCode <= 599) {
            return new IllegalStateException("Server error: " + e.getMessage(), e);
        }

        return new IllegalStateException(e.getMessage(), e);
    }

    private static int extractStatusCode(HttpClientException e) {
        if (e instanceof HttpClientResponseException responseException
            && responseException.getResponse() != null
            && responseException.getResponse().getStatus() != null) {
            return responseException.getResponse().getStatus().getCode();
        }
        return 0;
    }

    }