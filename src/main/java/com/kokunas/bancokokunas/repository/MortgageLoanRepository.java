package com.kokunas.bancokokunas.repository;

import com.kokunas.bancokokunas.model.MortgageLoan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MortgageLoanRepository extends JpaRepository<MortgageLoan, Long> {
}
