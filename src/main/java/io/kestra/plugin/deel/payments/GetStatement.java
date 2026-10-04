package io.kestra.plugin.deel.payments;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import jakarta.validation.constraints.NotNull;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.deel.connection.AbstractDeelConnection;
import io.kestra.plugin.deel.model.DeelPaymentStatement;
import io.kestra.plugin.deel.model.DeelPaymentStatementResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "Get Payment Statement",
    description = "Retrieve a payment statement by its ID, including status, amounts, beneficiary details, and settled invoices. Requires the payment-statements:read scope."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a payment statement by ID",
            full = true,
            code = """
                id: get_payment_statement
                namespace: company.team
                tasks:
                  - id: get_payment_statement
                    type: io.kestra.plugin.deel.payments.GetStatement
                    apiToken: "{{ secret('DEEL_API_TOKEN') }}"
                    paymentStatementId: "34d7Y48Ymu44g66fghN9F"
                """
        )
    }
)
public class GetStatement extends AbstractDeelConnection implements RunnableTask<GetStatement.Output> {

    @Schema(
        title = "Payment statement ID",
        description = "Unique identifier of the payment statement to retrieve."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> paymentStatementId;

    private static final TypeReference<DeelPaymentStatementResponse> STATEMENT_TYPE_REF = new TypeReference<>() {};

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String renderedId = runContext.render(this.paymentStatementId).as(String.class).orElseThrow();

        DeelPaymentStatementResponse response = request(
            runContext,
            "/payments/statements/" + renderedId,
            "GET",
            Map.of(),
            STATEMENT_TYPE_REF
        );

        if (response == null || response.getData() == null) {
            throw new IllegalStateException("Payment statement not found: " + renderedId);
        }

        DeelPaymentStatement statement = response.getData();
        logger.debug("Retrieved payment statement: {} ({})", statement.getId(), statement.getStatus());

        return Output.builder()
            .statement(statementToMap(statement))
            .build();
    }

    private Map<String, Object> statementToMap(DeelPaymentStatement statement) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", statement.getId());
        map.put("amount", statement.getAmount());
        map.put("status", statement.getStatus());
        map.put("amount_due", statement.getAmountDue());
        map.put("payment_currency", statement.getPaymentCurrency());
        map.put("paid_at", statement.getPaidAt());
        map.put("reference", statement.getReference());
        map.put("created_at", statement.getCreatedAt());
        map.put("payment_method", statement.getPaymentMethod());
        map.put("balance_applied", statement.getBalanceApplied());
        map.put("fee_credits_applied", statement.getFeeCreditsApplied());
        map.put("beneficiary_details", statement.getBeneficiaryDetails());

        List<Map<String, Object>> invoices = new ArrayList<>();
        if (statement.getInvoices() != null) {
            for (var invoice : statement.getInvoices()) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", invoice.getId());
                item.put("amount", invoice.getAmount());
                item.put("currency", invoice.getCurrency());
                item.put("label", invoice.getLabel());
                item.put("is_auto_added", invoice.getAutoAdded());
                invoices.add(item);
            }
        }
        map.put("invoices", invoices);
        return map;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Payment statement details",
            description = "The retrieved payment statement with status, amounts, beneficiary details, and settled invoices."
        )
        private Map<String, Object> statement;
    }
}
