package dev.floresm.transactions.domain.authorization;

/**
 * One eligibility check that an authorization must pass (card active, not expired, enough credit...).
 *
 * <h2>Design: Strategy pattern + polymorphism</h2>
 * Each rule is a separate class implementing this one-method interface. The service holds a
 * {@code List<AuthorizationRule>} and calls {@link #evaluate} on each without knowing which concrete
 * rule it is talking to. That is <b>runtime polymorphism</b>: the same call dispatches to different
 * code depending on the object's actual class.
 *
 * <p>Why bother instead of one big method with if-statements?
 * <ul>
 *   <li><b>Open/Closed Principle</b>: adding a rule = adding a class. Existing, tested code stays untouched.</li>
 *   <li><b>Single Responsibility</b>: each rule is small and has its own focused unit test.</li>
 *   <li>Rules can be reordered, enabled or disabled through configuration.</li>
 * </ul>
 *
 * <p>The implementations in {@code rules/} are Spring {@code @Component}s. Spring finds them all and
 * injects them as a list sorted by their {@code @Order} annotation (cheap checks first).
 *
 * <p>Rules must be <b>pure</b>: no I/O, no side effects. AuthorizationService relies on this to run
 * them twice (see the comments there).
 */
public interface AuthorizationRule {

    RuleResult evaluate(AuthorizationContext context);
}
