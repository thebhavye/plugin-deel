package io.kestra.plugin.deel.documents;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import jakarta.validation.constraints.NotNull;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.deel.connection.AbstractDeelConnection;
import io.kestra.plugin.deel.model.DeelHrxDownloadResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "Download EOR Contract Document",
    description = "Download an HRX document for an EOR contract. The API returns a pre-signed URL; the file content is stored in Kestra storage. Requires the contracts:read and worker:read scopes."
)
@Plugin(
    examples = {
        @Example(
            title = "Download a contract document",
            full = true,
            code = """
                id: download_document
                namespace: company.team
                tasks:
                  - id: download_document
                    type: io.kestra.plugin.deel.documents.Download
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    contractId: "123E4567"
                    documentId: "123e4567-e89b-12d3-a456-426614174000"
                """
        )
    }
)
public class Download extends AbstractDeelConnection implements RunnableTask<Download.Output> {

    @Schema(
        title = "Contract ID",
        description = "The unique identifier of the EOR employee contract."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> contractId;

    @Schema(
        title = "Document ID",
        description = "The unique identifier of the document to download."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> documentId;

    private static final TypeReference<DeelHrxDownloadResponse> DOWNLOAD_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String renderedContractId = runContext.render(this.contractId).as(String.class).orElseThrow();
        String renderedDocumentId = runContext.render(this.documentId).as(String.class).orElseThrow();

        DeelHrxDownloadResponse response = request(
            runContext,
            "/eor/contracts/" + renderedContractId + "/hrx-documents/" + renderedDocumentId,
            "GET",
            new java.util.HashMap<String, Object>(),
            DOWNLOAD_TYPE_REF
        );

        if (response == null || response.getData() == null || response.getData().getUrl() == null) {
            throw new IllegalStateException("Download URL not found for document: " + renderedDocumentId);
        }

        String downloadUrl = response.getData().getUrl();
        logger.debug("Downloading document {} for contract {}", renderedDocumentId, renderedContractId);

        String baseName = "contract-" + renderedDocumentId.replace("-", "");
        baseName = baseName.substring(0, Math.min(baseName.length(), 16));

        File stagingFile = runContext.workingDir().createTempFile(".tmp").toFile();
        Path stagingPath = stagingFile.toPath();

        try {
            // Download the file content using a streaming approach directly to a temp file
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

            HttpResponse<Path> httpResponse = client.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofFile(stagingPath)
            );

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new IllegalStateException("Document download failed with HTTP " + httpResponse.statusCode());
            }

            String contentType = httpResponse.headers().firstValue("Content-Type").orElse(null);
            String fileExtension = extensionForContentType(contentType);

            File tempFile = stagingFile;
            if (!stagingFile.getName().endsWith("." + fileExtension)) {
                File renamed = new File(stagingFile.getParentFile(), baseName + "." + fileExtension);
                Files.move(stagingPath, renamed.toPath(), StandardCopyOption.REPLACE_EXISTING);
                tempFile = renamed;
            }

            long size = tempFile.length();
            URI uri = runContext.storage().putFile(tempFile);

            return Output.builder()
                .uri(uri)
                .contractId(renderedContractId)
                .documentId(renderedDocumentId)
                .size(size)
                .build();
        } catch (Exception e) {
            // Clean up temp file on failure
            if (stagingFile.exists()) {
                stagingFile.delete();
            }
            throw e;
        }
    }

    static String extensionForContentType(String contentType) {
        if (contentType == null) {
            return "bin";
        }
        String mime = contentType.split(";")[0].trim().toLowerCase();
        return switch (mime) {
            case "application/pdf" -> "pdf";
            case "application/json" -> "json";
            case "text/csv" -> "csv";
            case "text/plain" -> "txt";
            case "application/xml", "text/xml" -> "xml";
            case "application/zip" -> "zip";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            default -> "bin";
        };
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Stored document URI",
            description = "Kestra internal storage path to the downloaded file."
        )
        private URI uri;

        @Schema(
            title = "Contract ID",
            description = "The EOR contract the document belongs to."
        )
        private String contractId;

        @Schema(
            title = "Document ID",
            description = "The downloaded document."
        )
        private String documentId;

        @Schema(
            title = "File size in bytes",
            description = "Size of the downloaded file."
        )
        private Long size;
    }
}