package com.kokunas.bancokokunas.controller;

import com.kokunas.bancokokunas.model.Customer;
import com.kokunas.bancokokunas.model.MortgageLoan;
import com.kokunas.bancokokunas.repository.CustomerRepository;
import com.kokunas.bancokokunas.service.LoanService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@Controller
public class LoanController {

    private final LoanService loanService;
    private final CustomerRepository customerRepository;

    public LoanController(LoanService loanService, CustomerRepository customerRepository) {
        this.loanService = loanService;
        this.customerRepository = customerRepository;
    }

    @GetMapping("/loans")
    public String list(Model model) {
        model.addAttribute("loans", loanService.findAll());
        return "loans";
    }

    @GetMapping("/loans/new")
    public String newForm(Model model) {
        model.addAttribute("customers", customerRepository.findAll());
        return "loan-form";
    }

    @PostMapping("/loans/simulate")
    public String simulate(@RequestParam BigDecimal propertyValue,
                            @RequestParam BigDecimal requestedAmount,
                            @RequestParam BigDecimal interestRate,
                            @RequestParam int termYears,
                            Model model) {
        BigDecimal monthlyPayment = loanService.calculateMonthlyPayment(requestedAmount, interestRate, termYears);
        MortgageLoan.Status eligibility = loanService.evaluateEligibility(propertyValue, requestedAmount);

        model.addAttribute("customers", customerRepository.findAll());
        model.addAttribute("propertyValue", propertyValue);
        model.addAttribute("requestedAmount", requestedAmount);
        model.addAttribute("interestRate", interestRate);
        model.addAttribute("termYears", termYears);
        model.addAttribute("monthlyPayment", monthlyPayment);
        model.addAttribute("eligibility", eligibility);
        return "loan-form";
    }

    @PostMapping("/loans/apply")
    public String apply(@RequestParam Long customerId,
                         @RequestParam BigDecimal propertyValue,
                         @RequestParam BigDecimal requestedAmount,
                         @RequestParam BigDecimal interestRate,
                         @RequestParam int termYears,
                         Model model) {
        Customer customer = customerRepository.findById(customerId).orElseThrow();
        MortgageLoan loan = loanService.submitApplication(customer, propertyValue, requestedAmount, interestRate, termYears);
        model.addAttribute("loan", loan);
        return "loan-result";
    }

    @GetMapping("/loans/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("loan", loanService.findById(id));
        return "loan-detail";
    }
}
