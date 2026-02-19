package com.checkout.payment.gateway.infrastructure.banksim;

import com.checkout.payment.gateway.infrastructure.dto.BankPaymentRequest;
import com.checkout.payment.gateway.infrastructure.dto.BankPaymentResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class BankSimulatorClient {

    private final RestTemplate restTemplate;

    @Value("${bank.simulator.url}")
    private String bankUrl;

    @CircuitBreaker(name = "bankSimulator", fallbackMethod = "handleBankError")
    @Retry(name = "bankSimulator")
    public BankPaymentResponse processPayment(BankPaymentRequest request) {
        log.info("Sending request to Bank Simulator at: {}", bankUrl);

        ResponseEntity<BankPaymentResponse> response = restTemplate.postForEntity(
                bankUrl,
                request,
                BankPaymentResponse.class
        );

        return response.getBody();
    }

    /**
     * Fallback for Resilience4j.
     * Instead of returning 'false' (Decline), we mark it as indeterminate.
     */
    public BankPaymentResponse handleBankError(BankPaymentRequest request, Throwable t) {
        log.error("Bank Simulator call failed (Timeout or 5xx). Triggering fallback. Reason: {}", t.getMessage());

        BankPaymentResponse fallbackResponse = new BankPaymentResponse();

        // Flag this as an indeterminate state
        fallbackResponse.add("authorized", false);
        fallbackResponse.add("indeterminate", true);
        fallbackResponse.add("error_message", t.getMessage());

        return fallbackResponse;
    }
}