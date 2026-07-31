package com.kokunas.bancokokunas.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Regulatory audit trail for transfers and mortgage applications.
 * Deliberately uses Apache Log4j 2 (org.apache.logging.log4j) directly
 * instead of the Spring Boot default (Logback), to keep a real,
 * reachable sink for CVE-2021-44228 / CVE-2021-45046 (Log4Shell):
 * user-controlled input (the "X-Channel" request header, or a transfer
 * concept) flows unsanitized into a log4j-core 2.14.1 log statement.
 */
@Component
public class AuditLogger {

    private static final Logger logger = LogManager.getLogger(AuditLogger.class);

    public void logChannelAccess(String operation, String channelHeader) {
        // VULNERABLE (CVE-2021-44228): channelHeader is attacker-controlled
        // and reaches Log4j's message lookup substitution unmodified.
        logger.info("Audit: operation={} channel={}", operation, channelHeader);
    }

    public void logTransfer(String originIban, String destinationIban, String concept) {
        logger.info("Audit: transfer {} -> {} concept={}", originIban, destinationIban, concept);
    }

    public void logMortgageApplication(String customerNif, String status) {
        logger.info("Audit: mortgage application for {} status={}", customerNif, status);
    }
}
