package com.kokunas.bancokokunas.client;

/**
 * Result of a call to the fraud-cve service's /api/fraudCheck endpoint.
 * {@link #unavailable()} is returned (fail-open) when the service can't be
 * reached, so a dependency outage never blocks a legitimate operation.
 */
public record FraudCheckResult(int fraudScore, String riskLevel, boolean approved) {

    public static FraudCheckResult unavailable() {
        return new FraudCheckResult(-1, "UNKNOWN", true);
    }
}
