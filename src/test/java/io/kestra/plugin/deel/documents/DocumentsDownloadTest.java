package io.kestra.plugin.deel.documents;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.deel.AbstractDeelTest;
import io.kestra.plugin.deel.MockDeelController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentsDownloadTest extends AbstractDeelTest {

    @BeforeAll
    static void startServer() {
    }

    @AfterAll
    static void stopServer() {
    }

    private static final String CONTRACT_ID = "123E4567";
    private static final String DOCUMENT_ID = "123e4567-e89b-12d3-a456-426614174000";

    private Download buildTask() {
        return Download.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:" + embeddedServer.getPort() + "/mock"))
            .contractId(Property.ofValue(CONTRACT_ID))
            .documentId(Property.ofValue(DOCUMENT_ID))
            .build();
    }

    @Test
    void testDownloadDocumentSuccess() throws Exception {
        byte[] pdfContent = "%PDF-1.4 fake pdf content".getBytes(StandardCharsets.UTF_8);
        String fileUrl = "http://localhost:" + embeddedServer.getPort() + "/mock/files/doc.pdf";

        String response = """
            {
                "data": {
                    "url": "%s",
                    "created_at": "2025-09-16T11:54:06.763Z",
                    "updated_at": "2025-09-16T11:54:50.168Z"
                }
            }
            """.formatted(fileUrl);

        MockDeelController.stubResponse(response);
        MockDeelController.stubBinary(pdfContent);

        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        RunContext runContext = factory.of();

        Download.Output output = buildTask().run(runContext);

        assertThat(output.getUri(), notNullValue());
        assertThat(output.getContractId(), is(CONTRACT_ID));
        assertThat(output.getDocumentId(), is(DOCUMENT_ID));
        assertThat(output.getSize(), is((long) pdfContent.length));
        assertThat(output.getUri().toString(), containsString(".pdf"));

        byte[] stored = runContext.storage().getFile(output.getUri()).readAllBytes();
        assertThat(stored, is(pdfContent));
        assertThat(MockDeelController.binaryRequested, is(true));

        assertThat(MockDeelController.headers.get("authorization"), is("Bearer test-token"));
        assertThat(MockDeelController.headers.get("x-version"), is("2026-01-01"));
    }

    @Test
    void testDownloadDocumentNotFound() {
        MockDeelController.stubError(404, "Not Found");

        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        RunContext runContext = factory.of();

        Exception e = assertThrows(Exception.class, () -> buildTask().run(runContext));
        assertThat(e.getMessage(), containsString("404"));
    }

    @Test
    void testDownloadDocumentUnauthorized() {
        MockDeelController.stubError(401, "Unauthorized");

        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        RunContext runContext = factory.of();

        Exception e = assertThrows(Exception.class, () -> buildTask().run(runContext));
        assertThat(e.getMessage(), containsString("Authentication failed"));
    }

    @Test
    void testDownloadDocumentForbidden() {
        MockDeelController.stubError(403, "Forbidden");

        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        RunContext runContext = factory.of();

        Exception e = assertThrows(Exception.class, () -> buildTask().run(runContext));
        assertThat(e.getMessage(), containsString("Access forbidden"));
    }

    @Test
    void testExtensionForContentType() {
        assertThat(Download.extensionForContentType("application/pdf"), is("pdf"));
        assertThat(Download.extensionForContentType("application/pdf; charset=utf-8"), is("pdf"));
        assertThat(Download.extensionForContentType("application/octet-stream"), is("bin"));
        assertThat(Download.extensionForContentType(null), is("bin"));
        assertThat(Download.extensionForContentType("application/unknown-type"), is("bin"));
    }
}
