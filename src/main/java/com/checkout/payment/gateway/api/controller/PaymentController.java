package com.checkout.payment.gateway.api.controller;

import com.checkout.payment.gateway.api.dto.PaymentRequest;
import com.checkout.payment.gateway.api.dto.PaymentResponse;
import com.checkout.payment.gateway.api.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        log.info("REST Request: Processing {} payment for amount {} {}. Idempotency: {}",
                request.getType(),
                request.getAmount(),
                request.getCurrency(),
                idempotencyKey);

        PaymentResponse response = paymentService.handlePayment(idempotencyKey, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPaymentDetails(@PathVariable UUID id) {
        log.info("REST Request: Fetching payment details for ID: {}", id);

        PaymentResponse response = paymentService.getPaymentById(id);
        return ResponseEntity.ok(response);
    }
}