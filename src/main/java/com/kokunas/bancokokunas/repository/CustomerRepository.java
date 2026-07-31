package com.kokunas.bancokokunas.repository;

import com.kokunas.bancokokunas.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
