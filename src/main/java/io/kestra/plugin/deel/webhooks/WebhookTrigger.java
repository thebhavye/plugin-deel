package io.kestra.plugin.deel.webhooks;

import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.plugin.core.trigger.AbstractWebhookTrigger;
import io.kestra.plugin.core.trigger.WebhookContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@SuperBuilder
@ToString
@Getter
@NoArgsConstructor
@Schema(
    title = "Deel Webhook Trigger",
    description = "Accept incoming Deel webhook requests and trigger on verified events. "
        + "Each request is verified using HMAC-SHA256 over the documented message (the POST method "
        + "concatenated with the raw request body) with the webhook signing key, using constant-time "
        + "comparison. Requests with missing or invalid signatures are rejected without triggering a flow. "
        + "Only the exact raw body is ever verified, never reserialized JSON: when Kestra delivers an "
        + "already-parsed body (JSON payloads), the raw bytes are unrecoverable and the request is "
        + "rejected. Uses Kestra's webhook trigger infrastructure (AbstractWebhookTrigger)."
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger on Deel contract events",
            full = true,
            code = """
                id: deel_webhook_events
                namespace: company.team
                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Deel event {{ trigger.event }}: {{ trigger.payload }}"
                triggers:
                  - id: deel_webhook
                    type: io.kestra.plugin.deel.webhooks.WebhookTrigger
                    key: "deel-webhook"
                    secretSigningKey: "{{ secret('DEEL_WEBHOOK_SIGNING_KEY') }}"
                    events:
                      - contract.created
                      - contract.signed
                """
        )
    }
)
public class WebhookTrigger extends AbstractWebhookTrigger implements TriggerOutput<WebhookTrigger.Output> {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookTrigger.class);

    public static final String SIGNATURE_HEADER = "x-deel-signature";

    @Schema(
        title = "Webhook signing key",
        description = "Signing key of the Deel webhook subscription (returned when the subscription is created). "
            + "Used as UTF-8 bytes for HMAC-SHA256 verification, matching the official Deel examples."
    )
    @PluginProperty(group = "connection", secret = true)
    @NotNull
    // NOTE: no @NotBlank here, see AbstractDeelTrigger for why trigger beans avoid it.
    private Property<String> secretSigningKey;

    @Schema(
        title = "Events to trigger on",
        description = "Optional exact-match filter on the webhook event type (e.g., contract.created). "
            + "When empty, every verified webhook triggers an execution."
    )
    @PluginProperty(group = "main")
    private Property<List<String>> events;

    @Override
    public Mono<HttpResponse<?>> evaluate(WebhookContext context) throws Exception {
        RunContext runContext = context.webhookService().runContext(context.flow(), this);
        Logger logger = runContext.logger();

        String rawBody = rawBody(context);
        String signature = context.request().getHeaders().firstValue(SIGNATURE_HEADER).orElse(null);
        String secret = runContext.render(this.secretSigningKey).as(String.class).orElseThrow();

        if (signature == null || signature.isBlank()) {
            logger.warn("Rejecting Deel webhook without {} header", SIGNATURE_HEADER);
            return Mono.just(HttpResponse.of(HttpResponse.Status.UNAUTHORIZED, Map.of("error", "Missing signature")));
        }

        if (!verifySignature(rawBody, signature, secret)) {
            logger.warn("Rejecting Deel webhook with invalid signature");
            return Mono.just(HttpResponse.of(HttpResponse.Status.UNAUTHORIZED, Map.of("error", "Invalid signature")));
        }

        Map<String, Object> payload;
        try {
            payload = JacksonMapper.ofJson().readValue(rawBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.warn("Rejecting Deel webhook with malformed payload");
            return Mono.just(HttpResponse.of(HttpResponse.Status.BAD_REQUEST, Map.of("error", "Malformed payload")));
        }

        String eventType = extractEventType(payload);

        List<String> allowedEvents = runContext.render(this.events).asList(String.class);
        if (allowedEvents != null && !allowedEvents.isEmpty()
            && (eventType == null || allowedEvents.stream().noneMatch(eventType::equals))) {
            logger.debug("Ignoring Deel webhook event '{}' not in events filter", eventType);
            return Mono.just(HttpResponse.of(HttpResponse.Status.OK, Map.of("ignored", true)));
        }

        Output output = Output.builder()
            .event(eventType)
            .payload(payload)
            .build();

        Optional<Execution> execution = context.webhookService().newExecution(context, context.flow(), this, output);
        if (execution.isEmpty()) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.OK, Map.of("ignored", true)));
        }

        return context.webhookService()
            .startExecution(execution.get())
            .doOnSuccess(ignored -> logger.debug("Triggering on Deel webhook event '{}'", eventType))
            .thenReturn(HttpResponse.of(HttpResponse.Status.OK, Map.of("id", execution.get().getId())));
    }

    /**
     * Verify a Deel webhook signature: HMAC-SHA256 over "POST" + raw body, hex-encoded,
     * compared in constant time. The raw body must be used as received, never reserialized JSON.
     */
    public static boolean verifySignature(String rawBody, String signature, String secret) {
        if (rawBody == null || signature == null || signature.isBlank() || secret == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(("POST" + rawBody).getBytes(StandardCharsets.UTF_8));
            byte[] provided = HexFormat.of().parseHex(signature.trim());
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception e) {
            LOG.debug("Failed to verify Deel webhook signature", e);
            return false;
        }
    }

    /**
     * Extract the event type from a Deel webhook payload ({@code meta} metadata, e.g. contract.created).
     */
    public static String extractEventType(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object meta = payload.get("meta");
        if (meta instanceof Map<?, ?> metaMap) {
            Object type = metaMap.get("type");
            if (type == null) {
                type = metaMap.get("event_type");
            }
            if (type instanceof String stringType && !stringType.isBlank()) {
                return stringType;
            }
        }
        Object type = payload.get("type");
        if (type instanceof String stringType && !stringType.isBlank()) {
            return stringType;
        }
        Object event = payload.get("event");
        if (event instanceof String stringEvent && !stringEvent.isBlank()) {
            return stringEvent;
        }
        return null;
    }

    private String rawBody(WebhookContext context) throws Exception {
        Object content = context.request().getBody() != null ? context.request().getBody().getContent() : null;
        if (content == null) {
            return "";
        }
        if (content instanceof byte[] bytes) {
            return new String(bytes, charsetOf(context));
        }
        if (content instanceof String string) {
            return string;
        }
        if (content instanceof java.nio.ByteBuffer buffer) {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return new String(bytes, charsetOf(context));
        }
        // Already-parsed bodies (e.g., JSON payloads parsed by Kestra's webhook layer) no longer
        // carry the exact raw bytes, so no trustworthy signature check is possible. Returning
        // null forces rejection instead of verifying over reserialized content.
        LOG.warn(
            "Rejecting Deel webhook: body arrived as {} so the raw bytes required for "
                + "HMAC verification are unavailable",
            content.getClass().getSimpleName()
        );
        return null;
    }

    private java.nio.charset.Charset charsetOf(WebhookContext context) {
        try {
            if (context.request().getBody() != null && context.request().getBody().getCharset() != null) {
                return context.request().getBody().getCharset();
            }
        } catch (Exception e) {
            LOG.debug("Falling back to UTF-8 for webhook body", e);
        }
        return StandardCharsets.UTF_8;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Event type",
            description = "The verified Deel webhook event type (e.g., contract.created)."
        )
        private String event;

        @Schema(
            title = "Payload",
            description = "The original verified webhook payload (meta, resource, timestamp)."
        )
        private Map<String, Object> payload;
    }
}
