package com.kokunas.bancokokunas.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Global "quick search" used by the branch-office teller desk to look up
 * customers, mortgage applications and transfers by free-text term across
 * several tables at once.
 *
 * NOT CVE-tracked: this is an application-level SQL Injection (CWE-89)
 * introduced by string concatenation instead of bind parameters. Trivy's
 * SCA scanning will not flag it (it's not a dependency CVE) - it is meant
 * to demonstrate the "unknown" / non-catalogued vulnerability class that a
 * SAST pass or Concert's code-risk analysis needs to surface, separate
 * from the known CVE-2021-44228 dependency finding.
 */
@Repository
public class VulnerableSearchRepository {

    private final JdbcTemplate jdbcTemplate;

    public VulnerableSearchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> searchCustomers(String term) {
        // VULNERABLE: user input concatenated directly into the SQL string.
        String sql = "SELECT id, full_name, nif, email, iban FROM customers " +
                "WHERE full_name ILIKE '%" + term + "%' OR nif ILIKE '%" + term + "%'";
        return jdbcTemplate.queryForList(sql);
    }
}
