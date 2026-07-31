package com.kokunas.bancokokunas.controller;

import com.kokunas.bancokokunas.config.AuditLogger;
import com.kokunas.bancokokunas.repository.VulnerableSearchRepository;
import javax.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Public teller-desk quick search, exposed on the internet-facing web UI.
 * Two intentional vulnerabilities live on this request path:
 *  1. CWE-89 SQL Injection in VulnerableSearchRepository#searchCustomers.
 *  2. CVE-2021-44228 (Log4Shell): the "X-Channel" header is logged via
 *     log4j-core 2.14.1 through AuditLogger before any validation.
 */
@Controller
public class SearchController {

    private final VulnerableSearchRepository searchRepository;
    private final AuditLogger auditLogger;

    public SearchController(VulnerableSearchRepository searchRepository, AuditLogger auditLogger) {
        this.searchRepository = searchRepository;
        this.auditLogger = auditLogger;
    }

    @GetMapping("/search")
    public String search(@RequestParam(required = false, defaultValue = "") String query,
                          HttpServletRequest request,
                          Model model) {
        String channel = request.getHeader("X-Channel");
        if (channel == null) {
            channel = "web";
        }
        // VULNERABLE (CVE-2021-44228): unsanitized header value logged via log4j-core.
        auditLogger.logChannelAccess("customer-search", channel);

        List<Map<String, Object>> results = List.of();
        if (!query.isBlank()) {
            // VULNERABLE (CWE-89): unsanitized query concatenated into SQL.
            results = searchRepository.searchCustomers(query);
        }

        model.addAttribute("query", query);
        model.addAttribute("results", results);
        return "search";
    }
}
