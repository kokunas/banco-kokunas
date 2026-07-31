package com.kokunas.bancokokunas.service;

import com.kokunas.bancokokunas.client.FraudCheckClient;
import com.kokunas.bancokokunas.client.FraudCheckResult;
import com.kokunas.bancokokunas.config.AuditLogger;
import com.kokunas.bancokokunas.model.Customer;
import com.kokunas.bancokokunas.model.Transfer;
import com.kokunas.bancokokunas.repository.TransferRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final AuditLogger auditLogger;
    private final FraudCheckClient fraudCheckClient;

    public TransferService(TransferRepository transferRepository, AuditLogger auditLogger,
                            FraudCheckClient fraudCheckClient) {
        this.transferRepository = transferRepository;
        this.auditLogger = auditLogger;
        this.fraudCheckClient = fraudCheckClient;
    }

    public Transfer createTransfer(Customer customer, String originIban, String destinationIban,
                                    BigDecimal amount, String concept) {
        Transfer transfer = new Transfer();
        transfer.setCustomer(customer);
        transfer.setOriginIban(originIban);
        transfer.setDestinationIban(destinationIban);
        transfer.setAmount(amount);
        transfer.setConcept(concept);

        FraudCheckResult fraudCheck = fraudCheckClient.check(customer.getNif(), "TRANSFER", amount);
        transfer.setStatus(fraudCheck.approved() ? Transfer.Status.COMPLETED : Transfer.Status.FAILED);

        Transfer saved = transferRepository.save(transfer);
        auditLogger.logTransfer(originIban, destinationIban, concept);
        return saved;
    }

    public List<Transfer> findAll() {
        return transferRepository.findAll();
    }

    public BigDecimal totalTransferred() {
        return transferRepository.findAll().stream()
                .map(Transfer::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public long countTransfers() {
        return transferRepository.count();
    }
}
