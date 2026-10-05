package io.kestra.plugin.deel.connection;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.deel.AbstractDeelTest;
import io.kestra.plugin.deel.MockDeelController;
import io.kestra.plugin.deel.contracts.ContractsList;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractDeelConnectionTest extends AbstractDeelTest {

    // NOTE: error mapping is exercised through the concrete ContractsList task.
    // A dedicated Task-subclass test double must NOT be declared here: the Kestra
    // annotation processor also runs on test sources and would register it as a
    // plugin without a public no-arg constructor, which aborts plugin scanning
    // (ServiceConfigurationError) and breaks the whole test context.
    private ContractsList connectionTask() {
        return ContractsList.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:" + embeddedServer.getPort() + "/mock"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();
    }

    private RunContext runContext() {
        return applicationContext.getBean(RunContextFactory.class).of();
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
        Exception e = assertThrows(Exception.class, () -> connectionTask().run(runContext()));
        assertThat(e.getMessage(), containsString("Authentication failed"));
    }

    @Test
    void testAccessForbiddenMapping() {
        MockDeelController.stubError(403, "Forbidden");
        Exception e = assertThrows(Exception.class, () -> connectionTask().run(runContext()));
        assertThat(e.getMessage(), containsString("Access forbidden"));
    }

    @Test
    void testRateLimitedMapping() {
        MockDeelController.stubError(429, "Too Many Requests");
        Exception e = assertThrows(Exception.class, () -> connectionTask().run(runContext()));
        assertThat(e.getMessage(), containsString("Rate limited"));
    }

    @Test
    void testNotFoundMapping() {
        MockDeelController.stubError(404, "Not Found");
        Exception e = assertThrows(Exception.class, () -> connectionTask().run(runContext()));
        assertThat(e.getMessage(), containsString("Not found (404)"));
    }

    @Test
    void testServerErrorMapping() {
        MockDeelController.stubError(500, "Internal Server Error");
        Exception e = assertThrows(Exception.class, () -> connectionTask().run(runContext()));
        assertThat(e.getMessage(), containsString("Server error"));
    }
}
