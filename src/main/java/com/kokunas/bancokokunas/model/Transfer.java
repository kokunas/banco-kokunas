package com.kokunas.bancokokunas.model;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transfers")
public class Transfer {

    public enum Status {
        COMPLETED, PENDING, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(nullable = false)
    private String originIban;

    @Column(nullable = false)
    private String destinationIban;

    @Column(nullable = false)
    private BigDecimal amount;

    private String concept;

    @Enumerated(EnumType.STRING)
    private Status status = Status.COMPLETED;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Transfer() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public String getOriginIban() {
        return originIban;
    }

    public void setOriginIban(String originIban) {
        this.originIban = originIban;
    }

    public String getDestinationIban() {
        return destinationIban;
    }

    public void setDestinationIban(String destinationIban) {
        this.destinationIban = destinationIban;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getConcept() {
        return concept;
    }

    public void setConcept(String concept) {
        this.concept = concept;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
