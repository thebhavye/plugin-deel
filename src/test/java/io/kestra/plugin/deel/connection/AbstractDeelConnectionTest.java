package io.kestra.plugin.deel.connection;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.deel.AbstractDeelTest;
import io.kestra.plugin.deel.MockDeelController;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractDeelConnectionTest extends AbstractDeelTest {

    @lombok.experimental.SuperBuilder
    static class TestConnection extends AbstractDeelConnection {
        public Object fetch(RunContext runContext) throws Exception {
            return request(runContext, "/contracts", "GET", Map.of("limit", 1), new TypeReference<Map<String, Object>>() {});
        }
    }

    private TestConnection connection() {
        return TestConnection.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:" + embeddedServer.getPort() + "/mock"))
            .build();
    }

    @Test
    void testDefaultBaseUrl() {
        // AbstractDeelConnection is abstract, but we can test defaults via subclass
        // The defaults are set in the field declarations with @Builder.Default
        assertThat(AbstractDeelConnection.DEFAULT_BASE_URL, is("https://api.letsdeel.com/rest"));
        assertThat(AbstractDeelConnection.DEFAULT_API_VERSION, is("2026-01-01"));
    }

    @Test
    void testAuthenticationFailedMapping() {
        MockDeelController.stubError(401, "Unauthorized");
        RunContext runContext = applicationContext.getBean(RunContextFactory.class).of();
        Exception e = assertThrows(Exception.class, () -> connection().fetch(runContext));
        assertThat(e.getMessage(), containsString("Authentication failed"));
    }

    @Test
    void testAccessForbiddenMapping() {
        MockDeelController.stubError(403, "Forbidden");
        RunContext runContext = applicationContext.getBean(RunContextFactory.class).of();
        Exception e = assertThrows(Exception.class, () -> connection().fetch(runContext));
        assertThat(e.getMessage(), containsString("Access forbidden"));
    }

    @Test
    void testRateLimitedMapping() {
        MockDeelController.stubError(429, "Too Many Requests");
        RunContext runContext = applicationContext.getBean(RunContextFactory.class).of();
        Exception e = assertThrows(Exception.class, () -> connection().fetch(runContext));
        assertThat(e.getMessage(), containsString("Rate limited"));
    }

    @Test
    void testServerErrorMapping() {
        MockDeelController.stubError(500, "Internal Server Error");
        RunContext runContext = applicationContext.getBean(RunContextFactory.class).of();
        Exception e = assertThrows(Exception.class, () -> connection().fetch(runContext));
        assertThat(e.getMessage(), containsString("Server error"));
    }
}