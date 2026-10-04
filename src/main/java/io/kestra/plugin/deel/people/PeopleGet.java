package io.kestra.plugin.deel.people;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.deel.connection.AbstractDeelConnection;
import io.kestra.plugin.deel.model.DeelPerson;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.util.Map;
import java.util.HashMap;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "Get Person",
    description = "Retrieve a single person (worker) with their employments, manager, direct reports, and other details."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a person by ID",
            full = true,
            code = """
                id: get_person
                namespace: company.team
                tasks:
                  - id: get_person
                    type: io.kestra.plugin.deel.people.PeopleGet
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    personId: "550e8400-e29b-41d4-a716-446655440000"
                """
        )
    }
)
public class PeopleGet extends AbstractDeelConnection implements RunnableTask<PeopleGet.Output> {

    @Schema(
        title = "Person ID",
        description = "Deel person ID (UUID from List People)."
    )
    @PluginProperty(group = "main")
    private Property<String> personId;

    // TypeReference for DeelPerson to preserve generic type information
    private static final TypeReference<DeelPerson> PERSON_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String renderedPersonId = runContext.render(this.personId).as(String.class).orElseThrow();

        DeelPerson person = request(
            runContext,
            "/v2/people/" + renderedPersonId,
            "GET",
            Map.of(),
            PERSON_TYPE_REF
        );

        if (person == null) {
            throw new IllegalStateException("Person not found: " + renderedPersonId);
        }

        logger.debug("Retrieved person: {} {}", person.getFirstName(), person.getLastName());

        return Output.builder()
            .person(personToMap(person))
            .build();
    }

    private Map<String, Object> personToMap(DeelPerson person) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", person.getId());
        map.put("first_name", person.getFirstName());
        map.put("last_name", person.getLastName());
        map.put("full_name", person.getFullName());
        map.put("email", person.getEmail());
        map.put("personal_email", person.getPersonalEmail());
        map.put("phone", person.getPhone());
        map.put("timezone", person.getTimezone());
        map.put("locale", person.getLocale());
        map.put("date_of_birth", person.getDateOfBirth());
        map.put("gender", person.getGender());
        map.put("nationality", person.getNationality());
        map.put("hiring_status", person.getHiringStatus());
        map.put("hiring_type", person.getHiringType());
        map.put("job_title", person.getJobTitle());
        map.put("department", person.getDepartment());
        map.put("legal_entity_id", person.getLegalEntityId());
        map.put("legal_entity_name", person.getLegalEntityName());
        map.put("team_id", person.getTeamId());
        map.put("team_name", person.getTeamName());
        map.put("manager_id", person.getManagerId());
        map.put("manager_name", person.getManagerName());
        map.put("start_date", person.getStartDate());
        map.put("end_date", person.getEndDate());
        map.put("employments", person.getEmployments());
        map.put("address", person.getAddress());
        map.put("bank_account", person.getBankAccount());
        map.put("created_at", person.getCreatedAt());
        map.put("updated_at", person.getUpdatedAt());
        return map;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Person details",
            description = "The retrieved person with all available fields including employments, address, and bank account."
        )
        private Map<String, Object> person;
    }
}