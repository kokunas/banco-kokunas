package com.kokunas.bancokokunas;

import com.kokunas.bancokokunas.model.MortgageLoan;
import com.kokunas.bancokokunas.service.LoanService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Isofunctional unit tests for the mortgage business logic. These assert
 * pure business outcomes (monthly payment, eligibility) and must keep
 * passing unchanged after the auto-remediation workflow patches the
 * log4j-core dependency and the SQL Injection in the search module -
 * neither of which this class touches.
 *
 * calculateMonthlyPayment/evaluateEligibility are pure functions that
 * never touch the repository or the audit logger, so both collaborators
 * are safely left null here.
 */
class LoanServiceTest {

    private final LoanService loanService = new LoanService(null, null, null);

    @Test
    void calculatesMonthlyPaymentForStandardMortgage() {
        BigDecimal payment = loanService.calculateMonthlyPayment(
                new BigDecimal("240000"), new BigDecimal("3.10"), 25);

        // French amortization system, 240000 @ 3.10% / 25y = 1150.63
        assertEquals(0, payment.compareTo(new BigDecimal("1150.63")),
                "expected ~1150.63, got " + payment);
    }

    @Test
    void approvesLoanWithinEightyPercentLoanToValue() {
        MortgageLoan.Status status = loanService.evaluateEligibility(
                new BigDecimal("320000"), new BigDecimal("240000"));
        assertEquals(MortgageLoan.Status.APPROVED, status);
    }

    @Test
    void rejectsLoanAboveEightyPercentLoanToValue() {
        MortgageLoan.Status status = loanService.evaluateEligibility(
                new BigDecimal("195000"), new BigDecimal("175000"));
        assertEquals(MortgageLoan.Status.REJECTED, status);
    }
}
