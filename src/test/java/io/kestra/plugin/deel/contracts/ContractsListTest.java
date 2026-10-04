package io.kestra.plugin.deel.contracts;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.deel.AbstractDeelTest;
import io.kestra.plugin.deel.MockDeelController;
import io.kestra.plugin.deel.model.DeelContract;
import io.kestra.plugin.deel.model.DeelPage;
import io.kestra.plugin.deel.model.DeelPagination;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ContractsListTest extends AbstractDeelTest {

    @BeforeAll
    static void startServer() {
    }

    @AfterAll
    static void stopServer() {
    }

    @Test
    void testListContractsSuccess() throws Exception {
        String response = """
            {
                "data": [
                    {
                        "id": "550e8400-e29b-41d4-a716-446655440000",
                        "title": "Contract A",
                        "contract_type": "open",
                        "status": "active",
                        "legal_entity_id": "le-123",
                        "team_id": "team-456",
                        "start_date": "2024-01-15",
                        "end_date": "2025-01-15",
                        "created_at": "2024-01-15T10:00:00Z",
                        "updated_at": "2024-06-15T10:00:00Z"
                    },
                    {
                        "id": "550e8400-e29b-41d4-a716-446655440001",
                        "title": "Contract B",
                        "contract_type": "terminated",
                        "status": "terminated",
                        "legal_entity_id": "le-456",
                        "team_id": "team-789",
                        "start_date": "2023-06-01",
                        "end_date": "2024-06-01",
                        "created_at": "2023-06-01T10:00:00Z",
                        "updated_at": "2024-06-01T10:00:00Z"
                    }
                ],
                "page": {
                    "offset": 0,
                    "total_rows": 2,
                    "items_per_page": 25,
                    "cursor": null
                }
            }
            """;

        MockDeelController.stubResponse(response);

        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        RunContext runContext = factory.of();

        ContractsList task = ContractsList.builder()
            .apiToken(io.kestra.core.models.property.Property.ofValue("test-token"))
            .baseUrl(io.kestra.core.models.property.Property.ofValue("http://localhost:" + embeddedServer.getPort() + "/mock"))
            .limit(io.kestra.core.models.property.Property.ofValue(25))
            .fetchType(io.kestra.core.models.property.Property.ofValue(FetchType.FETCH))
            .build();

        ContractsList.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getTotal(), is(2L));
        assertThat(output.getRows(), notNullValue());
        assertThat(output.getRows().size(), is(2));

        Map<String, Object> firstContract = output.getRows().get(0);
        assertThat(firstContract.get("id"), is("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(firstContract.get("title"), is("Contract A"));
        assertThat(firstContract.get("contract_type"), is("open"));
        assertThat(firstContract.get("status"), is("active"));

        Map<String, Object> secondContract = output.getRows().get(1);
        assertThat(secondContract.get("id"), is("550e8400-e29b-41d4-a716-446655440001"));
        assertThat(secondContract.get("title"), is("Contract B"));
    }

    @Test
    void testListContractsFetchOne() throws Exception {
        String response = """
            {
                "data": [
                    {
                        "id": "1",
                        "title": "Contract A",
                        "contract_type": "open",
                        "status": "active"
                    }
                ],
                "page": {
                    "offset": 0,
                    "total_rows": 1,
                    "items_per_page": 25
                }
            }
            """;

        MockDeelController.stubResponse(response);

        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        RunContext runContext = factory.of();

        ContractsList task = ContractsList.builder()
            .apiToken(io.kestra.core.models.property.Property.ofValue("test-token"))
            .baseUrl(io.kestra.core.models.property.Property.ofValue("http://localhost:" + embeddedServer.getPort() + "/mock"))
            .fetchType(io.kestra.core.models.property.Property.ofValue(FetchType.FETCH_ONE))
            .build();

        ContractsList.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().get("id"), is("1"));
        assertThat(output.getRows(), nullValue());
    }

    @Test
    void testListContractsFetchNone() throws Exception {
        String response = """
            {
                "data": [
                    {
                        "id": "1",
                        "title": "Contract A"
                    }
                ],
                "page": {
                    "offset": 0,
                    "total_rows": 1,
                    "items_per_page": 25
                }
            }
            """;

        MockDeelController.stubResponse(response);

        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        RunContext runContext = factory.of();

        ContractsList task = ContractsList.builder()
            .apiToken(io.kestra.core.models.property.Property.ofValue("test-token"))
            .baseUrl(io.kestra.core.models.property.Property.ofValue("http://localhost:" + embeddedServer.getPort() + "/mock"))
            .fetchType(io.kestra.core.models.property.Property.ofValue(FetchType.NONE))
            .build();

        ContractsList.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        assertThat(output.getTotal(), is(1L));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());
    }

    @Test
    void testListContractsEmptyResult() throws Exception {
        String response = """
            {
                "data": [],
                "page": {
                    "offset": 0,
                    "total_rows": 0,
                    "items_per_page": 25
                }
            }
            """;

        MockDeelController.stubResponse(response);

        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        RunContext runContext = factory.of();

        ContractsList task = ContractsList.builder()
            .apiToken(io.kestra.core.models.property.Property.ofValue("test-token"))
            .baseUrl(io.kestra.core.models.property.Property.ofValue("http://localhost:" + embeddedServer.getPort() + "/mock"))
            .fetchType(io.kestra.core.models.property.Property.ofValue(FetchType.FETCH))
            .build();

        ContractsList.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        assertThat(output.getTotal(), is(0L));
        assertThat(output.getRows(), empty());
    }

    @Test
    void testListContractsWithFilters() throws Exception {
        String response = """
            {
                "data": [
                    {
                        "id": "550e8400-e29b-41d4-a716-446655440000",
                        "title": "Contract A",
                        "contract_type": "open",
                        "status": "active"
                    }
                ],
                "page": {
                    "offset": 0,
                    "total_rows": 1,
                    "items_per_page": 25
                }
            }
            """;

        MockDeelController.stubResponse(response);

        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        RunContext runContext = factory.of();

        ContractsList task = ContractsList.builder()
            .apiToken(io.kestra.core.models.property.Property.ofValue("test-token"))
            .baseUrl(io.kestra.core.models.property.Property.ofValue("http://localhost:" + embeddedServer.getPort() + "/mock"))
            .status(io.kestra.core.models.property.Property.ofValue("active"))
            .contractType(io.kestra.core.models.property.Property.ofValue("open"))
            .fetchType(io.kestra.core.models.property.Property.ofValue(FetchType.FETCH))
            .build();

        ContractsList.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows().get(0).get("status"), is("active"));
        assertThat(output.getRows().get(0).get("contract_type"), is("open"));

        assertThat(MockDeelController.queryParameters.get("status"), is("active"));
        assertThat(MockDeelController.queryParameters.get("contract_type"), is("open"));
    }

    @Test
    void testListContractsStoreUsesIon() throws Exception {
        String response = """
            {
                "data": [
                    {
                        "id": "1",
                        "title": "Contract A",
                        "contract_type": "open",
                        "status": "active"
                    }
                ],
                "page": {
                    "offset": 0,
                    "total_rows": 1,
                    "items_per_page": 25,
                    "cursor": "next-cursor"
                }
            }
            """;

        MockDeelController.stubResponse(response);

        RunContextFactory factory = applicationContext.getBean(RunContextFactory.class);
        RunContext runContext = factory.of();

        ContractsList task = ContractsList.builder()
            .apiToken(io.kestra.core.models.property.Property.ofValue("test-token"))
            .baseUrl(io.kestra.core.models.property.Property.ofValue("http://localhost:" + embeddedServer.getPort() + "/mock"))
            .fetchType(io.kestra.core.models.property.Property.ofValue(FetchType.STORE))
            .build();

        ContractsList.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getTotal(), is(1L));
        assertThat(output.getNextCursor(), is("next-cursor"));
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getUri().toString(), containsString(".ion"));
    }
}