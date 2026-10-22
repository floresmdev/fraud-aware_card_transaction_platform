package dev.floresm.transactions.domain.authorization.rules;

import dev.floresm.transactions.domain.authorization.AuthorizationContext;
import dev.floresm.transactions.domain.authorization.AuthorizationRule;
import dev.floresm.transactions.domain.authorization.RuleResult;
import dev.floresm.transactions.domain.transaction.DeclineReason;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** A blocked card can never authorize, so this runs first. */
@Component
@Order(10)
public class CardMustBeActiveRule implements AuthorizationRule {

    @Override
    public RuleResult evaluate(AuthorizationContext context) {
        return context.card().isActive()
                ? RuleResult.pass()
                : RuleResult.fail(DeclineReason.CARD_NOT_ACTIVE);
    }
}
