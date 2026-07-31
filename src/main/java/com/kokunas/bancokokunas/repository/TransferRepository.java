package com.kokunas.bancokokunas.repository;

import com.kokunas.bancokokunas.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, Long> {
}
