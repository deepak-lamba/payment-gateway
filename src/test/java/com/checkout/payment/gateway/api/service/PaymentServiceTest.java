package com.checkout.payment.gateway.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.checkout.payment.gateway.api.dto.PaymentRequest;
import com.checkout.payment.gateway.api.dto.PaymentResponse;
import com.checkout.payment.gateway.api.exception.ResourceNotFoundException;
import com.checkout.payment.gateway.api.model.Payment;
import com.checkout.payment.gateway.api.model.PaymentAudit;
import com.checkout.payment.gateway.api.processor.PaymentProcessor;
import com.checkout.payment.gateway.api.repository.PaymentAuditRepository;
import com.checkout.payment.gateway.api.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness; // Import for Strictness
import org.mockito.junit.jupiter.MockitoSettings; // Import for MockitoSettings

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentAuditRepository auditRepository;
    @Mock
    private PaymentProcessor cardPaymentProcessor; // Specific processor for testing
    @Mock
    private ObjectMapper objectMapper;

    // Self-inject the mock for transactional methods
    @Mock
    private PaymentService self;

    private PaymentService paymentService;

    @Captor
    private ArgumentCaptor<Payment> paymentCaptor;
    @Captor
    private ArgumentCaptor<PaymentAudit> auditCaptor;


    private PaymentRequest validPaymentRequest;
    private PaymentResponse processorSuccessResponse;
    private UUID testPaymentId;
    private String testIdempotencyKey;

    @BeforeEach
    void setUp() {
        List<PaymentProcessor> processors = List.of(cardPaymentProcessor);
        paymentService = new PaymentService(self, processors, paymentRepository, auditRepository, objectMapper);

        when(cardPaymentProcessor.supports("CARD")).thenReturn(true);
        testPaymentId = UUID.randomUUID();
        testIdempotencyKey = UUID.randomUUID().toString();

        validPaymentRequest = new PaymentRequest(1000L, "USD");
        validPaymentRequest.add("type", "CARD");
        validPaymentRequest.add("card_number", "4234567890123456");
        validPaymentRequest.add("expiry_month", 12);
        validPaymentRequest.add("expiry_year", LocalDate.now().getYear() + 5);
        validPaymentRequest.add("cvv", "123");

        processorSuccessResponse = PaymentResponse.builder()
                .paymentId(testPaymentId)
                .status("AUTHORIZED")
                .message("Success")
                .build();
        processorSuccessResponse.add("masked_card_number", "**** **** **** 3456");
        processorSuccessResponse.add("expiry_month", 12);
        processorSuccessResponse.add("expiry_year", LocalDate.now().getYear() + 5);
        processorSuccessResponse.add("amount", validPaymentRequest.getAmount());
        processorSuccessResponse.add("currency", validPaymentRequest.getCurrency());
        processorSuccessResponse.add("message", "Success");
    }

    @Test
    void handlePayment_shouldProcessNewPaymentSuccessfully() throws JsonProcessingException {
        // Given
        Payment initialPayment = Payment.builder()
                .id(testPaymentId)
                .amount(validPaymentRequest.getAmount())
                .currency(validPaymentRequest.getCurrency())
                .status("PENDING")
                .idempotencyKey(testIdempotencyKey)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        // Simulate save of initial PENDING payment
        when(paymentRepository.findByIdempotencyKey(testIdempotencyKey)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenReturn(initialPayment);
        // Simulate processor selection and processing
        when(cardPaymentProcessor.supports("CARD")).thenReturn(true);
        when(cardPaymentProcessor.process(any(PaymentRequest.class))).thenReturn(processorSuccessResponse);

        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(new HashMap<>());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        // When
        PaymentResponse result = paymentService.handlePayment(testIdempotencyKey, validPaymentRequest);

        // Then
        assertNotNull(result);
        assertEquals("AUTHORIZED", result.getStatus());
        assertEquals(testPaymentId, result.getPaymentId());

        // Verify repository interactions
        verify(paymentRepository, times(1)).findByIdempotencyKey(testIdempotencyKey);
        verify(paymentRepository, times(2)).save(paymentCaptor.capture()); // Initial PENDING and final AUTHORIZED

        // Verify audit logs
        verify(auditRepository, times(2)).save(auditCaptor.capture()); // REQUEST_RECEIVED and PROCESS_COMPLETED
        assertEquals("REQUEST_RECEIVED", auditCaptor.getAllValues().get(0).getAction());
        assertEquals("PROCESS_COMPLETED", auditCaptor.getAllValues().get(1).getAction());

        Payment finalPayment = paymentCaptor.getAllValues().get(1);
        assertEquals("AUTHORIZED", finalPayment.getStatus());
        assertEquals(processorSuccessResponse.getDetails(), finalPayment.getDetails());
    }

    @Test
    void handlePayment_shouldReturnExistingPaymentOnIdempotencyConflict() throws JsonProcessingException {
        // Given
        Payment existingAuthorizedPayment = Payment.builder()
                .id(testPaymentId)
                .amount(validPaymentRequest.getAmount())
                .currency(validPaymentRequest.getCurrency())
                .status("AUTHORIZED")
                .idempotencyKey(testIdempotencyKey)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .details(new HashMap<>(Map.of("masked_card_number", "**** **** **** 3456", "message", "Success"))) // Include message in details
                .build();

        // Simulate an existing payment found
        when(paymentRepository.findByIdempotencyKey(testIdempotencyKey)).thenReturn(Optional.of(existingAuthorizedPayment));
        // Mock self-invocation for the findAndMap transactional method
        // Manually construct the expected PaymentResponse from the existing payment
        PaymentResponse expectedResponse = PaymentResponse.builder()
                .paymentId(existingAuthorizedPayment.getId())
                .status(existingAuthorizedPayment.getStatus())
                .message((String) existingAuthorizedPayment.getDetails().get("message")) // Get message from details
                .build();
        expectedResponse.add("amount", existingAuthorizedPayment.getAmount());
        expectedResponse.add("currency", existingAuthorizedPayment.getCurrency());
        existingAuthorizedPayment.getDetails().forEach(expectedResponse::add);


        when(self.findAndMap(testIdempotencyKey)).thenReturn(expectedResponse);

        when(objectMapper.convertValue(any(PaymentRequest.class), eq(Map.class))).thenReturn(new HashMap<>());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");


        // When
        PaymentResponse result = paymentService.handlePayment(testIdempotencyKey, validPaymentRequest);

        // Then
        assertNotNull(result);
        assertEquals("AUTHORIZED", result.getStatus());
        assertEquals(testPaymentId, result.getPaymentId());
        assertEquals("Success", result.getMessage());
        assertEquals("**** **** **** 3456", result.getDetails().get("masked_card_number"));

        // Verify that no new payment was created or processed
        verify(paymentRepository, times(1)).findByIdempotencyKey(testIdempotencyKey);
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(cardPaymentProcessor, never()).process(any(PaymentRequest.class));
        verify(self, times(1)).findAndMap(testIdempotencyKey); // Ensure findAndMap was called via self-injection
        verify(auditRepository, times(1)).save(any(PaymentAudit.class)); // Only REQUEST_RECEIVED audit log
    }

    @Test
    void getPaymentById_shouldReturnPaymentDetailsWhenFound() {
        // Given
        Map<String, Object> dbDetails = new HashMap<>(Map.of(
                "type", "CARD",
                "masked_card_number", "**** **** **** 3456",
                "expiry_month", 12,
                "expiry_year", 2030,
                "message", "Success"
        ));

        Payment foundPayment = Payment.builder()
                .id(testPaymentId)
                .amount(validPaymentRequest.getAmount())
                .currency(validPaymentRequest.getCurrency())
                .status("AUTHORIZED")
                .details(dbDetails)
                .build();

        when(paymentRepository.findById(testPaymentId)).thenReturn(Optional.of(foundPayment));

        doAnswer(invocation -> {
            PaymentResponse resp = invocation.getArgument(1);
            resp.add("last_four_card_digits", "3456");
            resp.add("expiry_month", 12);
            resp.add("expiry_year", 2030);
            return null;
        }).when(cardPaymentProcessor).mapDetailsToResponse(anyMap(), any(PaymentResponse.class));

        // When
        PaymentResponse result = paymentService.getPaymentById(testPaymentId);

        // Then
        assertEquals("3456", result.getDetails().get("last_four_card_digits"));
        assertNull(result.getDetails().get("masked_card_number"), "Internal field should be filtered out");
        assertEquals(12, result.getDetails().get("expiry_month"));
    }

    @Test
    void getPaymentById_shouldThrowResourceNotFoundExceptionWhenNotFound() {
        // Given
        when(paymentRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        // When & Then
        assertThrows(ResourceNotFoundException.class, () -> paymentService.getPaymentById(UUID.randomUUID()));
        verify(paymentRepository, times(1)).findById(any(UUID.class));
    }



    @Test
    void handlePayment_shouldThrowIllegalArgumentExceptionForUnsupportedType() throws JsonProcessingException {
        // Given
        PaymentRequest unsupportedRequest = new PaymentRequest(1000L, "USD");
        unsupportedRequest.add("type", "UNSUPPORTED");

        when(paymentRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenReturn(new Payment());
        when(cardPaymentProcessor.supports(anyString())).thenReturn(false); // No processor supports it
        when(objectMapper.convertValue(any(PaymentRequest.class), eq(Map.class))).thenReturn(new HashMap<>());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> paymentService.handlePayment(testIdempotencyKey, unsupportedRequest));
        verify(paymentRepository, times(1)).findByIdempotencyKey(anyString());
        verify(paymentRepository, times(1)).save(any(Payment.class)); // Initial PENDING save happens
        verify(auditRepository, times(1)).save(any(PaymentAudit.class)); // REQUEST_RECEIVED audit log
    }

    @Test
    void findAndMap_shouldThrowIllegalStateExceptionIfPaymentNotFoundAfterLock() {
        // Given
        when(paymentRepository.findAndLockByIdempotencyKey(testIdempotencyKey)).thenReturn(Optional.empty());
        // When & Then
        assertThrows(IllegalStateException.class, () -> paymentService.findAndMap(testIdempotencyKey));
    }
}
