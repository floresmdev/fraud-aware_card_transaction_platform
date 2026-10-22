package dev.floresm.transactions.domain.authorization.rules;

import dev.floresm.transactions.domain.authorization.AuthorizationContext;
import dev.floresm.transactions.domain.authorization.AuthorizationRule;
import dev.floresm.transactions.domain.authorization.RuleResult;
import dev.floresm.transactions.domain.transaction.DeclineReason;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs last: it is only meaningful once we know the currencies match. {@code Account.canCover}
 * returns false on a currency mismatch instead of throwing, so this rule is safe even if
 * someone reorders the rules.
 */
@Component
@Order(40)
public class SufficientCreditRule implements AuthorizationRule {

    @Override
    public RuleResult evaluate(AuthorizationContext context) {
        return context.account().canCover(context.amount())
                ? RuleResult.pass()
                : RuleResult.fail(DeclineReason.INSUFFICIENT_CREDIT);
    }
}
