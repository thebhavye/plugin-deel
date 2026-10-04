package io.kestra.plugin.deel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.serializers.JacksonMapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Controller("/mock")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MockDeelController {

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    public static String data;
    public static Integer errorStatus;
    public static byte[] binaryData;
    public static boolean binaryRequested;
    public static Map<String, String> headers = new HashMap<>();
    public static Map<String, String> queryParameters = new HashMap<>();
    public static java.util.Queue<String> responseQueue = new java.util.ArrayDeque<>();

    private void capture(HttpRequest<?> request) {
        headers = new HashMap<>();
        request.getHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
        queryParameters = new HashMap<>();
        request.getParameters().forEach((name, values) -> queryParameters.put(name, values.getFirst()));
    }

    private HttpResponse<?> respond() {
        Integer status = errorStatus;
        // NOTE: errorStatus is intentionally sticky (not cleared) so that
        // client retries (e.g. on HTTP 429) keep receiving the error status.
        if (!responseQueue.isEmpty()) {
            String queued = responseQueue.poll();
            if (status != null) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.valueOf(status), queued);
            }
            return HttpResponse.ok(queued);
        }
        String responseData = data;
        data = null;
        if (status != null) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.valueOf(status), responseData);
        }
        return HttpResponse.ok(responseData);
    }

    @Get("/v2/people")
    public HttpResponse<?> listPeople(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/v2/people/{personId}")
    public HttpResponse<?> getPerson(HttpRequest<?> request, String personId) {
        capture(request);
        return respond();
    }

    @Get("/contracts")
    public HttpResponse<?> listContracts(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/v2/contracts/{contractId}")
    public HttpResponse<?> getContract(HttpRequest<?> request, String contractId) {
        capture(request);
        return respond();
    }

    @Get("/v2/invoices")
    public HttpResponse<?> listInvoicesLegacy(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/invoices")
    public HttpResponse<?> listInvoices(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/invoices/deel")
    public HttpResponse<?> listDeelInvoices(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/payments/statements/{paymentStatementId}")
    public HttpResponse<?> getPaymentStatement(HttpRequest<?> request, String paymentStatementId) {
        capture(request);
        return respond();
    }

    @Get("/timesheets")
    public HttpResponse<?> listTimesheets(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/time_offs/profile/{hrisProfileId}")
    public HttpResponse<?> listTimeOffs(HttpRequest<?> request, String hrisProfileId) {
        capture(request);
        return respond();
    }

    @Get("/organizations")
    public HttpResponse<?> getOrganizations(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/legal-entities")
    public HttpResponse<?> listLegalEntities(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/legal-entities/{legalEntityId}/cost-centers")
    public HttpResponse<?> listCostCenters(HttpRequest<?> request, String legalEntityId) {
        capture(request);
        return respond();
    }

    @Get("/departments")
    public HttpResponse<?> listDepartments(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/teams")
    public HttpResponse<?> listTeams(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/managers")
    public HttpResponse<?> listManagers(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/lookups/countries")
    public HttpResponse<?> listCountries(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/lookups/currencies")
    public HttpResponse<?> listCurrencies(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/lookups/job-titles")
    public HttpResponse<?> listJobTitles(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/lookups/seniorities")
    public HttpResponse<?> listSeniorities(HttpRequest<?> request) {
        capture(request);
        return respond();
    }

    @Get("/eor/contracts/{contractId}/hrx-documents")
    public HttpResponse<?> listHrxDocuments(HttpRequest<?> request, String contractId) {
        capture(request);
        return respond();
    }

    @Get("/eor/contracts/{contractId}/hrx-documents/{documentId}")
    public HttpResponse<?> downloadHrxDocument(HttpRequest<?> request, String contractId, String documentId) {
        capture(request);
        return respond();
    }

    @Get(value = "/files/{name}", produces = MediaType.APPLICATION_OCTET_STREAM)
    public HttpResponse<?> serveFile(HttpRequest<?> request, String name) {
        // NOTE: intentionally does not capture headers — the pre-signed file
        // download must not carry the Deel API token.
        binaryRequested = true;
        byte[] content = binaryData;
        binaryData = null;
        if (content == null) {
            return HttpResponse.notFound();
        }
        if (name != null && name.endsWith(".pdf")) {
            return HttpResponse.ok(content).contentType(MediaType.APPLICATION_PDF);
        }
        return HttpResponse.ok(content).contentType(MediaType.APPLICATION_OCTET_STREAM);
    }

    public static void reset() {
        data = null;
        errorStatus = null;
        binaryData = null;
        binaryRequested = false;
        headers.clear();
        queryParameters.clear();
        responseQueue.clear();
    }

    public static void stubResponse(String body) {
        data = body;
        errorStatus = null;
        responseQueue.clear();
    }

    public static void stubSequentialResponses(String... bodies) {
        responseQueue.clear();
        data = null;
        errorStatus = null;
        for (String body : bodies) {
            responseQueue.add(body);
        }
    }

    public static void stubBinary(byte[] content) {
        binaryData = content;
    }

    public static void stubError(int statusCode, String errorMessage) {
        data = "{\"error\": \"" + errorMessage + "\"}";
        errorStatus = statusCode;
    }
}