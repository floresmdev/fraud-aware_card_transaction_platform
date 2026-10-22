package dev.floresm.transactions.domain.authorization.rules;

import dev.floresm.transactions.domain.authorization.AuthorizationContext;
import dev.floresm.transactions.domain.authorization.AuthorizationRule;
import dev.floresm.transactions.domain.authorization.RuleResult;
import dev.floresm.transactions.domain.transaction.DeclineReason;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * We do not support foreign-exchange conversion (yet), so the transaction currency must equal the
 * account currency. Real issuers convert using the card network's rate and add an FX fee.
 * Documented as a known limitation in the README.
 */
@Component
@Order(30)
public class CurrencyMustMatchAccountRule implements AuthorizationRule {

    @Override
    public RuleResult evaluate(AuthorizationContext context) {
        return context.amount().currency().equals(context.account().currency())
                ? RuleResult.pass()
                : RuleResult.fail(DeclineReason.CURRENCY_MISMATCH);
    }
}
