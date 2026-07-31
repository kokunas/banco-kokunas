package com.kokunas.bancokokunas.controller;

import com.kokunas.bancokokunas.model.Customer;
import com.kokunas.bancokokunas.repository.CustomerRepository;
import com.kokunas.bancokokunas.service.TransferService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@Controller
public class TransferController {

    private final TransferService transferService;
    private final CustomerRepository customerRepository;

    public TransferController(TransferService transferService, CustomerRepository customerRepository) {
        this.transferService = transferService;
        this.customerRepository = customerRepository;
    }

    @GetMapping("/transfers")
    public String list(Model model) {
        model.addAttribute("transfers", transferService.findAll());
        model.addAttribute("total", transferService.totalTransferred());
        return "transfers";
    }

    @GetMapping("/transfers/new")
    public String newForm(Model model) {
        model.addAttribute("customers", customerRepository.findAll());
        return "transfer-form";
    }

    @PostMapping("/transfers")
    public String create(@RequestParam Long customerId,
                          @RequestParam String originIban,
                          @RequestParam String destinationIban,
                          @RequestParam BigDecimal amount,
                          @RequestParam(required = false) String concept,
                          Model model) {
        Customer customer = customerRepository.findById(customerId).orElseThrow();
        var transfer = transferService.createTransfer(customer, originIban, destinationIban, amount, concept);
        model.addAttribute("transfer", transfer);
        return "transfer-result";
    }
}
