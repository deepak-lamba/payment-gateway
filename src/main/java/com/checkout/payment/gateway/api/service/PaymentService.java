package com.checkout.payment.gateway.api.service;

import com.checkout.payment.gateway.api.dto.PaymentRequest;
import com.checkout.payment.gateway.api.dto.PaymentResponse;
import com.checkout.payment.gateway.api.exception.ResourceNotFoundException;
import com.checkout.payment.gateway.api.model.Payment;
import com.checkout.payment.gateway.api.model.PaymentAudit;
import com.checkout.payment.gateway.api.processor.PaymentProcessor;
import com.checkout.payment.gateway.api.repository.PaymentAuditRepository;
import com.checkout.payment.gateway.api.repository.PaymentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@Service
@Slf4j
public class PaymentService {

    private final List<PaymentProcessor> processors;
    private final PaymentRepository paymentRepository;
    private final PaymentAuditRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final PaymentService self;

    public PaymentService(@Lazy PaymentService self, List<PaymentProcessor> processors, PaymentRepository paymentRepository, PaymentAuditRepository auditRepository, ObjectMapper objectMapper) {
        this.self = self;
        this.processors = processors;
        this.paymentRepository = paymentRepository;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
    }


    @Transactional(isolation = Isolation.SERIALIZABLE)
    public PaymentResponse handlePayment(String idempotencyKey, PaymentRequest request) {
        saveAuditLog(null, idempotencyKey, "REQUEST_RECEIVED", request);

        var existingPayment = paymentRepository.findByIdempotencyKey(idempotencyKey);

        // Safe Check Again due to Concurrency Possible
        // Locks only Dual - Less probable cases of repeat.
        if (existingPayment.isPresent()) {
            log.info("Idempotency conflict for key: {}. Replaying.", idempotencyKey);
            return self.findAndMap(idempotencyKey);
        }

        Payment payment = Payment.builder()
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status("PENDING")
                .idempotencyKey(idempotencyKey)
                .build();
        payment = paymentRepository.save(payment);

        return executeAndFinalize(payment, request);
    }

    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public PaymentResponse findAndMap(String idempotencyKey) {
        return paymentRepository.findAndLockByIdempotencyKey(idempotencyKey)
            .map(this::mapToResponse)
            .orElseThrow(() -> new IllegalStateException("Consistency error during idempotent replay for key: " + idempotencyKey));
    }


    public PaymentResponse getPaymentById(UUID id) {
        log.info("Service: Fetching payment record for ID: {}", id);
        return paymentRepository.findById(id)
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for ID: " + id));
    }

    private PaymentResponse executeAndFinalize(Payment payment, PaymentRequest request) {
        PaymentProcessor strategy = processors.stream()
                .filter(p -> p.supports(request.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported payment type: " + request.getType()));

        // Get the "Full" internal response from the processor
        PaymentResponse processorResponse = strategy.process(request);

        // Update the entity for DB storage (Stores the "Full" bag)
        payment.setStatus(processorResponse.getStatus());

        // Merge message into details for persistence
        if (processorResponse.getMessage() != null) {
            processorResponse.add("message", processorResponse.getMessage());
        }
        payment.setDetails(processorResponse.getDetails());

        // Save to Database
        Payment savedPayment = paymentRepository.save(payment);

        // Audit the FULL response
        saveAuditLog(savedPayment.getId(), savedPayment.getIdempotencyKey(), "PROCESS_COMPLETED", processorResponse);

        // Use mapToResponse to filter the fields for the Merchant
        return mapToResponse(savedPayment);
    }

    private PaymentResponse mapToResponse(Payment entity) {
        // Mandatory Core Fields
        PaymentResponse response = PaymentResponse.builder()
                .paymentId(entity.getId())
                .status(entity.getStatus())
                .build();

        // Add Amount and Currency
        response.add("amount", entity.getAmount());
        response.add("currency", entity.getCurrency());

        // Delegation with safe type check
        if (entity.getDetails() != null) {
            // Extract type safely to avoid NPE
            String type = String.valueOf(entity.getDetails().getOrDefault("type", "UNKNOWN"));

            processors.stream()
                    .filter(p -> p.supports(type))
                    .findFirst()
                    .ifPresent(processor -> processor.mapDetailsToResponse(entity.getDetails(), response));

            // Map the top-level message if present
            if (entity.getDetails().containsKey("message")) {
                response.setMessage((String) entity.getDetails().get("message"));
            }
        }

        return response;
    }

    private void saveAuditLog(UUID paymentId, String key, String action, Object payload) {
        try {
            String scrubbedPayload = scrubAndSerialize(payload);

            PaymentAudit audit = PaymentAudit.builder()
                    .paymentId(paymentId)
                    .idempotencyKey(key)
                    .action(action)
                    .payload(scrubbedPayload)
                    .build();
            auditRepository.save(audit);
        } catch (Exception e) {
            log.error("Critical audit failure for idempotency key: {}. Reason: {}", key, e.getMessage());
        }
    }

    private String scrubAndSerialize(Object payload) throws JsonProcessingException {
        if (payload instanceof PaymentRequest) {
            PaymentRequest originalRequest = (PaymentRequest) payload;
            Map<String, Object> dataCopy = new HashMap<>(originalRequest.getData());

            if (dataCopy.containsKey("card_number")) {
                dataCopy.put("card_number", "****");
            }
            if (dataCopy.containsKey("cvv")) {
                dataCopy.put("cvv", "***");
            }
            
            Map<String, Object> forSerialization = new HashMap<>();
            forSerialization.put("amount", originalRequest.getAmount());
            forSerialization.put("currency", originalRequest.getCurrency());
            forSerialization.put("data", dataCopy);
            
            return objectMapper.writeValueAsString(forSerialization);
        }

        // For other types (like PaymentResponse), serialize as is. They don't contain raw sensitive data.
        return objectMapper.writeValueAsString(payload);
    }
}
