package com.kokunas.bancokokunas;

import com.kokunas.bancokokunas.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Isofunctional regression suite: these tests describe the OBSERVABLE
 * BUSINESS BEHAVIOR of the banking demo (mortgage simulation/application,
 * fund transfers, customer search) rather than any particular
 * implementation. They are run BEFORE and AFTER the auto-remediation
 * workflow patches log4j-core (CVE-2021-44228) and rewrites the SQL
 * Injection in VulnerableSearchRepository to use bind parameters.
 *
 * A green run on both sides of the remediation is the proof that the
 * fix preserved functional equivalence ("isofunctional").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IsofunctionalWebTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void dashboardLoadsWithBankBranding() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Banco Kokunas")));
    }

    @Test
    void mortgageSimulationReturnsExpectedMonthlyPayment() throws Exception {
        mockMvc.perform(post("/loans/simulate")
                        .param("propertyValue", "320000")
                        .param("requestedAmount", "240000")
                        .param("interestRate", "3.10")
                        .param("termYears", "25"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1150.63")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("APPROVED")));
    }

    @Test
    void mortgageAboveLoanToValueIsRejected() throws Exception {
        mockMvc.perform(post("/loans/simulate")
                        .param("propertyValue", "195000")
                        .param("requestedAmount", "175000")
                        .param("interestRate", "3.45")
                        .param("termYears", "30"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REJECTED")));
    }

    @Test
    void mortgageApplicationIsPersistedAndListed() throws Exception {
        Long customerId = customerRepository.findAll().get(0).getId();

        mockMvc.perform(post("/loans/apply")
                        .param("customerId", String.valueOf(customerId))
                        .param("propertyValue", "300000")
                        .param("requestedAmount", "200000")
                        .param("interestRate", "3.0")
                        .param("termYears", "20"))
                .andExpect(status().isOk())
                .andExpect(view().name("loan-result"));

        mockMvc.perform(get("/loans"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("200000")));
    }

    @Test
    void transferIsCreatedAndAppearsInList() throws Exception {
        Long customerId = customerRepository.findAll().get(0).getId();

        mockMvc.perform(post("/transfers")
                        .param("customerId", String.valueOf(customerId))
                        .param("originIban", "ES9121000418450200051332")
                        .param("destinationIban", "ES6621000418401234567891")
                        .param("amount", "500.00")
                        .param("concept", "Test isofuncional"))
                .andExpect(status().isOk())
                .andExpect(view().name("transfer-result"));

        mockMvc.perform(get("/transfers"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Test isofuncional")));
    }

    @Test
    void customerSearchByFullNameReturnsExpectedCustomer() throws Exception {
        // Functional behavior that MUST survive the SQLi fix: legitimate
        // free-text search by (partial) name still finds the customer,
        // once the query is rewritten to use bind parameters instead of
        // string concatenation.
        mockMvc.perform(get("/search").param("query", "Maria"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Maria Garcia Lopez")));
    }

    @Test
    void customerSearchByPartialNifReturnsExpectedCustomer() throws Exception {
        mockMvc.perform(get("/search").param("query", "2345678"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Fernandez")));
    }

    @Test
    void customerSearchWithNoMatchReturnsEmptyResultsGracefully() throws Exception {
        mockMvc.perform(get("/search").param("query", "NoExisteEsteCliente"))
                .andExpect(status().isOk())
                .andExpect(status().is2xxSuccessful());
    }
}
