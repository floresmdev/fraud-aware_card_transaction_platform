package dev.floresm.transactions.infrastructure.fraud;

import dev.floresm.transactions.domain.fraud.FraudAssessment;
import dev.floresm.transactions.domain.fraud.FraudScreener;
import dev.floresm.transactions.domain.fraud.FraudScreeningRequest;
import org.springframework.stereotype.Component;

/**
 * Temporary Phase 1 implementation: approves everything. It exists so the authorization flow is
 * complete end-to-end before the Python service is built. Replaced in Phase 3 by an HTTP client.
 *
 * <p>This is the <b>Null Object</b> pattern: a do-nothing implementation that lets callers skip
 * {@code if (screener != null)} checks.
 */
@Component
public class ApproveAllFraudScreener implements FraudScreener {

    @Override
    public FraudAssessment screen(FraudScreeningRequest request) {
        return FraudAssessment.approve("fraud screening not configured (Phase 1 placeholder)");
    }
}
