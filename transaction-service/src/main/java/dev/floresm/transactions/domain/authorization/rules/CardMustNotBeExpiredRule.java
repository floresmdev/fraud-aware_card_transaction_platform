package dev.floresm.transactions.domain.authorization.rules;

import dev.floresm.transactions.domain.authorization.AuthorizationContext;
import dev.floresm.transactions.domain.authorization.AuthorizationRule;
import dev.floresm.transactions.domain.authorization.RuleResult;
import dev.floresm.transactions.domain.transaction.DeclineReason;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Uses the business date from the context (injected Clock), which makes it trivially testable. */
@Component
@Order(20)
public class CardMustNotBeExpiredRule implements AuthorizationRule {

    @Override
    public RuleResult evaluate(AuthorizationContext context) {
        return context.card().isExpiredOn(context.today())
                ? RuleResult.fail(DeclineReason.CARD_EXPIRED)
                : RuleResult.pass();
    }
}
