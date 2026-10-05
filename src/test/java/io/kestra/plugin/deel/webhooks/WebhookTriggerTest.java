package io.kestra.plugin.deel.webhooks;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Output;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.plugin.core.trigger.AbstractWebhookTrigger;
import io.kestra.plugin.core.trigger.WebhookContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.services.WebhookService;
import io.kestra.plugin.deel.AbstractDeelTest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class WebhookTriggerTest extends AbstractDeelTest {

    private static final String SECRET = "test-signing-key";

    private static String sign(String rawBody, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(("POST" + rawBody).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static final String PAYLOAD = """
        {"meta":{"type":"contract.created","organization_id":"org-1"},"resource":{"id":"c1"},"timestamp":"2024-06-01T00:00:00Z"}
        """.trim();

    /** WebhookService double that records executions without needing server-side queues. */
    static class FakeWebhookService extends WebhookService {
        final RunContext runContext;
        Output capturedOutput;
        boolean executionStarted;

        FakeWebhookService(RunContext runContext) {
            this.runContext = runContext;
        }

        @Override
        public RunContext runContext(Flow flow, AbstractTrigger trigger) {
            return runContext;
        }

        @Override
        public Optional<Execution> newExecution(WebhookContext context, Flow flow, AbstractWebhookTrigger trigger, Output output) {
            capturedOutput = output;
            return Optional.of(Execution.builder()
                .id("test-execution")
                .namespace("company.team")
                .flowId("webhook-flow")
                .state(new io.kestra.core.models.flows.State())
                .trigger(io.kestra.core.models.executions.ExecutionTrigger.of(trigger, output))
                .build());
        }

        @Override
        public reactor.core.publisher.Mono<io.kestra.core.async.AsyncOperationProcessedEvent> startExecution(Execution execution) {
            executionStarted = true;
            return reactor.core.publisher.Mono.empty();
        }
    }

    private WebhookTrigger buildTrigger(List<String> events) {
        WebhookTrigger.WebhookTriggerBuilder<?, ?> builder = WebhookTrigger.builder()
            .secretSigningKey(Property.ofValue(SECRET));
        if (events != null) {
            builder.events(Property.ofValue(events));
        }
        return builder.build();
    }

    private WebhookContext webhookContext(String rawBody, String signature, FakeWebhookService service, WebhookTrigger trigger) throws Exception {
        // StringRequestBody preserves the exact raw bytes, mirroring what signature
        // verification requires. (RequestBody.from would parse JSON payloads.)
        HttpRequest.RequestBody body = HttpRequest.StringRequestBody.of(rawBody);
        Map<String, java.util.List<String>> headers = signature == null
            ? Map.of()
            : Map.of(WebhookTrigger.SIGNATURE_HEADER, List.of(signature));
        HttpRequest request = HttpRequest.of(
            URI.create("http://localhost/webhooks/deel"),
            "POST",
            body,
            headers
        );
        return new WebhookContext(request, "/webhooks/deel", null, trigger, service, null, null);
    }

    private RunContext runContext() {
        return applicationContext.getBean(RunContextFactory.class).of();
    }

    @Test
    void testValidSignatureTriggersExecution() throws Exception {
        String signature = sign(PAYLOAD, SECRET);

        RunContext runContext = runContext();
        FakeWebhookService service = new FakeWebhookService(runContext);
        WebhookTrigger trigger = buildTrigger(null);

        HttpResponse<?> response = trigger.evaluate(webhookContext(PAYLOAD, signature, service, trigger)).block();

        assertThat(response.getStatus().getCode(), is(200));
        assertThat(service.executionStarted, is(true));
        // The HTTP response is chained after the execution is accepted and carries its id.
        assertThat(response.getBody(), instanceOf(Map.class));
        assertThat(((Map<?, ?>) response.getBody()).get("id"), is("test-execution"));
        assertThat(service.capturedOutput, instanceOf(WebhookTrigger.Output.class));

        WebhookTrigger.Output output = (WebhookTrigger.Output) service.capturedOutput;
        assertThat(output.getEvent(), is("contract.created"));
        assertThat(output.getPayload().get("timestamp"), is("2024-06-01T00:00:00Z"));
        assertThat(((Map<String, Object>) output.getPayload().get("meta")).get("type"), is("contract.created"));
    }

    @Test
    void testInvalidSignatureRejected() throws Exception {
        RunContext runContext = runContext();
        FakeWebhookService service = new FakeWebhookService(runContext);
        WebhookTrigger trigger = buildTrigger(null);

        HttpResponse<?> response = trigger.evaluate(webhookContext(PAYLOAD, "0".repeat(64), service, trigger)).block();

        assertThat(response.getStatus().getCode(), is(401));
        assertThat(service.executionStarted, is(false));
        assertThat(service.capturedOutput, nullValue());
    }

    @Test
    void testMissingSignatureRejected() throws Exception {
        RunContext runContext = runContext();
        FakeWebhookService service = new FakeWebhookService(runContext);
        WebhookTrigger trigger = buildTrigger(null);

        HttpResponse<?> response = trigger.evaluate(webhookContext(PAYLOAD, null, service, trigger)).block();

        assertThat(response.getStatus().getCode(), is(401));
        assertThat(service.executionStarted, is(false));
    }

    @Test
    void testModifiedBodyRejected() throws Exception {
        // Signature computed over the original body, but a tampered body is delivered.
        String signature = sign(PAYLOAD, SECRET);
        String tampered = PAYLOAD.replace("contract.created", "contract.signed");

        RunContext runContext = runContext();
        FakeWebhookService service = new FakeWebhookService(runContext);
        WebhookTrigger trigger = buildTrigger(null);

        HttpResponse<?> response = trigger.evaluate(webhookContext(tampered, signature, service, trigger)).block();

        assertThat(response.getStatus().getCode(), is(401));
        assertThat(service.executionStarted, is(false));
    }

    @Test
    void testEventFilterMismatchIgnored() throws Exception {
        String signature = sign(PAYLOAD, SECRET);

        RunContext runContext = runContext();
        FakeWebhookService service = new FakeWebhookService(runContext);
        WebhookTrigger trigger = buildTrigger(List.of("contract.signed"));

        HttpResponse<?> response = trigger.evaluate(webhookContext(PAYLOAD, signature, service, trigger)).block();

        assertThat(response.getStatus().getCode(), is(200));
        assertThat(service.executionStarted, is(false));
        assertThat(service.capturedOutput, nullValue());
    }

    @Test
    void testMalformedPayloadRejected() throws Exception {
        String malformed = "{not valid json";
        String signature = sign(malformed, SECRET);

        RunContext runContext = runContext();
        FakeWebhookService service = new FakeWebhookService(runContext);
        WebhookTrigger trigger = buildTrigger(null);

        HttpResponse<?> response = trigger.evaluate(webhookContext(malformed, signature, service, trigger)).block();

        assertThat(response.getStatus().getCode(), is(400));
        assertThat(service.executionStarted, is(false));
    }

    @Test
    void testParsedBodyRejectedWithoutReserializedVerification() throws Exception {
        // Kestra parses inbound JSON bodies before triggers run. The exact raw bytes are then
        // unrecoverable, so the trigger must reject rather than verify over reserialized JSON —
        // even when the provided signature would match the reserialization.
        String reserialized = "{\"meta\":{\"type\":\"contract.created\"}}";
        String signature = sign(reserialized, SECRET);

        HttpRequest.RequestBody parsedBody = HttpRequest.RequestBody.from(
            new StringEntity(PAYLOAD, ContentType.APPLICATION_JSON)
        );

        RunContext runContext = runContext();
        FakeWebhookService service = new FakeWebhookService(runContext);
        WebhookTrigger trigger = buildTrigger(null);

        HttpRequest request = HttpRequest.of(
            URI.create("http://localhost/webhooks/deel"),
            "POST",
            parsedBody,
            Map.of(WebhookTrigger.SIGNATURE_HEADER, List.of(signature))
        );
        WebhookContext context = new WebhookContext(request, "/webhooks/deel", null, trigger, service, null, null);

        HttpResponse<?> response = trigger.evaluate(context).block();

        assertThat(response.getStatus().getCode(), is(401));
        assertThat(service.executionStarted, is(false));
    }

    @Test
    void testVerifySignatureVectors() throws Exception {
        assertThat(WebhookTrigger.verifySignature(PAYLOAD, sign(PAYLOAD, SECRET), SECRET), is(true));
        assertThat(WebhookTrigger.verifySignature(PAYLOAD, sign(PAYLOAD, SECRET).toUpperCase(), SECRET), is(true));
        assertThat(WebhookTrigger.verifySignature(PAYLOAD, sign(PAYLOAD, "other-secret"), SECRET), is(false));
        assertThat(WebhookTrigger.verifySignature("tampered", sign(PAYLOAD, SECRET), SECRET), is(false));
        assertThat(WebhookTrigger.verifySignature(PAYLOAD, "not-hex!!", SECRET), is(false));
        assertThat(WebhookTrigger.verifySignature(PAYLOAD, null, SECRET), is(false));
        assertThat(WebhookTrigger.verifySignature(null, sign(PAYLOAD, SECRET), SECRET), is(false));
        assertThat(WebhookTrigger.verifySignature(PAYLOAD, sign(PAYLOAD, SECRET), null), is(false));
        // Signature must cover the POST prefix, not the raw body alone.
        assertThat(WebhookTrigger.verifySignature("POST" + PAYLOAD, sign(PAYLOAD, SECRET), SECRET), is(false));
    }

    @Test
    void testExtractEventType() {
        assertThat(WebhookTrigger.extractEventType(Map.of("meta", Map.of("type", "contract.created"))), is("contract.created"));
        assertThat(WebhookTrigger.extractEventType(Map.of("meta", Map.of("event_type", "invoice.paid"))), is("invoice.paid"));
        assertThat(WebhookTrigger.extractEventType(Map.of("type", "custom.event")), is("custom.event"));
        assertThat(WebhookTrigger.extractEventType(Map.of("event", "custom.event")), is("custom.event"));
        assertThat(WebhookTrigger.extractEventType(Map.of("meta", Map.of())), nullValue());
        assertThat(WebhookTrigger.extractEventType(Map.of()), nullValue());
        assertThat(WebhookTrigger.extractEventType(null), nullValue());
    }
}
