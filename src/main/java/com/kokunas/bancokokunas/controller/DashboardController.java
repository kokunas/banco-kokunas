package com.kokunas.bancokokunas.controller;

import com.kokunas.bancokokunas.repository.CustomerRepository;
import com.kokunas.bancokokunas.service.LoanService;
import com.kokunas.bancokokunas.service.TransferService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.util.List;

@Controller
public class DashboardController {

    private final LoanService loanService;
    private final TransferService transferService;
    private final CustomerRepository customerRepository;

    public DashboardController(LoanService loanService, TransferService transferService,
                                CustomerRepository customerRepository) {
        this.loanService = loanService;
        this.transferService = transferService;
        this.customerRepository = customerRepository;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        var loans = loanService.findAll();
        long approved = loans.stream().filter(l -> l.getStatus().name().equals("APPROVED")).count();
        long pending = loans.stream().filter(l -> l.getStatus().name().equals("PENDING")).count();

        BigDecimal totalMortgageVolume = loans.stream()
                .map(l -> l.getRequestedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        model.addAttribute("totalCustomers", customerRepository.count());
        model.addAttribute("totalLoans", loans.size());
        model.addAttribute("approvedLoans", approved);
        model.addAttribute("pendingLoans", pending);
        model.addAttribute("totalMortgageVolume", totalMortgageVolume);
        model.addAttribute("totalTransfers", transferService.countTransfers());
        model.addAttribute("totalTransferredAmount", transferService.totalTransferred());
        model.addAttribute("recentLoans", loans.stream().limit(5).toList());
        model.addAttribute("recentTransfers", transferService.findAll().stream().limit(5).toList());

        return "dashboard";
    }
}
