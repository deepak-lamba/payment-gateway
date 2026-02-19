package com.checkout.payment.gateway.api.processor;

import com.checkout.payment.gateway.api.dto.PaymentRequest;
import com.checkout.payment.gateway.api.dto.PaymentResponse;
import com.checkout.payment.gateway.infrastructure.banksim.BankSimulatorClient;
import com.checkout.payment.gateway.infrastructure.dto.BankPaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardPaymentProcessorTest {

    @Mock
    private BankSimulatorClient bankClient;

    @InjectMocks
    private CardPaymentProcessor cardPaymentProcessor;

    private PaymentRequest baseValidRequest;

    @BeforeEach
    void setUp() {
        // Manually construct a valid base request as it's a property bag
        baseValidRequest = new PaymentRequest(1000L, "USD"); // Lombok @AllArgsConstructor
        baseValidRequest.add("type", "CARD");
        baseValidRequest.add("card_number", "4234567890123456");
        baseValidRequest.add("expiry_month", 12);
        baseValidRequest.add("expiry_year", LocalDate.now().getYear() + 5); // Ensure future year
        baseValidRequest.add("cvv", "123");
    }

    // Helper to get a modifiable copy for each test
    private PaymentRequest getModifiableValidRequest() {
        PaymentRequest newRequest = new PaymentRequest(baseValidRequest.getAmount(), baseValidRequest.getCurrency());

        newRequest.getData().putAll(new HashMap<>(baseValidRequest.getData()));
        return newRequest;
    }

    @Test
    void supports_shouldReturnTrueForCardType() {
        assertTrue(cardPaymentProcessor.supports("CARD"));
        assertTrue(cardPaymentProcessor.supports("card"));
    }

    @Test
    void supports_shouldReturnFalseForNonCardType() {
        assertFalse(cardPaymentProcessor.supports("BANK_TRANSFER"));
        assertFalse(cardPaymentProcessor.supports("UNKNOWN"));
    }

    @Test
    void shouldProcessCardPaymentSuccessfullyWhenBankAuthorizes() {
        // Given
        PaymentRequest request = getModifiableValidRequest();
        BankPaymentResponse bankResponse = new BankPaymentResponse();
        bankResponse.add("authorization_code", "4cfc3a33-54e8");
        bankResponse.add("authorized", true);
        when(bankClient.processPayment(any())).thenReturn(bankResponse);

        // When
        PaymentResponse response = cardPaymentProcessor.process(request);

        // Then
        assertEquals("AUTHORIZED", response.getStatus());
        assertEquals("Success", response.getMessage());
        assertEquals("**** **** **** 3456", response.getDetails().get("masked_card_number"));
        assertEquals("VISA", response.getDetails().get("card_type")); // Assuming 1234 starts with 1-4 for VISA
        assertEquals(12, response.getDetails().get("expiry_month"));
        assertEquals(LocalDate.now().getYear() + 5, response.getDetails().get("expiry_year"));
        assertEquals(request.getAmount(), response.getDetails().get("amount"));
        assertEquals(request.getCurrency(), response.getDetails().get("currency"));
    }

    @Test
    void shouldDeclineCardPaymentWhenBankDeclines() {
        // Given
        PaymentRequest request = getModifiableValidRequest();
        BankPaymentResponse bankResponse = new BankPaymentResponse();
        bankResponse.add("authorized", false);
        when(bankClient.processPayment(any())).thenReturn(bankResponse);

        // When
        PaymentResponse response = cardPaymentProcessor.process(request);

        // Then
        assertEquals("DECLINED", response.getStatus());
        assertEquals("Declined", response.getMessage());
    }

    @Test
    void shouldSetStatusToPendingWhenBankResponseIsMalformed() {
        // Given
        PaymentRequest request = getModifiableValidRequest();
        BankPaymentResponse malformedBankResponse = new BankPaymentResponse();
        // 'authorized' key is missing, and 'indeterminate' is false by default
        when(bankClient.processPayment(any())).thenReturn(malformedBankResponse);

        // When
        PaymentResponse response = cardPaymentProcessor.process(request);

        // Then
        assertEquals("PENDING_RECONCILIATION", response.getStatus());
        assertEquals("Malformed bank response", response.getMessage());
    }

    @Test
    void shouldSetStatusToPendingWhenBankResponseIsIndeterminate() {
        // Given
        PaymentRequest request = getModifiableValidRequest();
        BankPaymentResponse indeterminateBankResponse = new BankPaymentResponse();
        indeterminateBankResponse.add("indeterminate", true); // Simulating timeout fallback
        indeterminateBankResponse.add("authorized", false); // Even if bank says false, indeterminate takes precedence
        when(bankClient.processPayment(any())).thenReturn(indeterminateBankResponse);

        // When
        PaymentResponse response = cardPaymentProcessor.process(request);

        // Then
        assertEquals("PENDING_RECONCILIATION", response.getStatus());
        assertEquals("Bank timeout", response.getMessage());
    }

    // --- Validation Tests ---

    @Test
    void shouldThrowExceptionWhenCardNumberIsMissing() {
        PaymentRequest request = getModifiableValidRequest();
        request.getData().remove("card_number"); // Missing card number
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "Card number is required.");
    }

    @Test
    void shouldThrowExceptionWhenCardNumberIsTooShort() {
        PaymentRequest request = getModifiableValidRequest();
        request.getData().put("card_number", "12345"); // Too short
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "Card number must be 14-19 numeric characters long.");
    }

    @Test
    void shouldThrowExceptionWhenCardNumberContainsNonNumericChars() {
        PaymentRequest request = getModifiableValidRequest();
        request.getData().put("card_number", "123456789012345A"); // Contains char
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "Card number must be 14-19 numeric characters long.");
    }
    
    @Test
    void shouldThrowExceptionWhenCvvIsMissing() {
        PaymentRequest request = getModifiableValidRequest();
        request.getData().remove("cvv"); // Missing CVV
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "CVV is required.");
    }

    @Test
    void shouldThrowExceptionWhenCvvIsInvalidLength() {
        PaymentRequest request = getModifiableValidRequest();
        request.getData().put("cvv", "12"); // Too short
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "CVV must be 3-4 numeric characters long.");
        
        request.getData().put("cvv", "12345"); // Too long
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "CVV must be 3-4 numeric characters long.");
    }
    
    @Test
    void shouldThrowExceptionWhenCvvContainsNonNumericChars() {
        PaymentRequest request = getModifiableValidRequest();
        request.getData().put("cvv", "12A"); // Contains char
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "CVV must be 3-4 numeric characters long.");
    }

    @Test
    void shouldThrowExceptionWhenExpiryMonthIsMissing() {
        PaymentRequest request = getModifiableValidRequest();
        request.getData().remove("expiry_month"); // Missing month
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "Expiry month and year are required.");
    }

    @Test
    void shouldThrowExceptionWhenExpiryYearIsMissing() {
        PaymentRequest request = getModifiableValidRequest();
        request.getData().remove("expiry_year"); // Missing year
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "Expiry month and year are required.");
    }

    @Test
    void shouldThrowExceptionWhenExpiryMonthIsInvalid() {
        PaymentRequest request = getModifiableValidRequest();
        request.getData().put("expiry_month", 0); // Invalid month
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "Expiry month must be between 1 and 12.");
        
        request.getData().put("expiry_month", 13); // Invalid month
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "Expiry month must be between 1 and 12.");
    }

    @Test
    void shouldThrowExceptionWhenExpiryDateIsInThePast() {
        PaymentRequest request = getModifiableValidRequest();
        request.getData().put("expiry_year", LocalDate.now().getYear() - 1); // Past year
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "Card expiry date must be in the future.");

        request.getData().put("expiry_year", LocalDate.now().getYear());
        request.getData().put("expiry_month", LocalDate.now().getMonthValue() - 1); // Past month in current year
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "Card expiry date must be in the future.");
    }

    @Test
    void shouldThrowExceptionWhenCurrencyIsUnsupported() {
        PaymentRequest request = new PaymentRequest(baseValidRequest.getAmount(), "XYZ"); // Create new request with unsupported currency
        request.getData().putAll(new HashMap<>(baseValidRequest.getData())); // Copy other valid data
        assertThrows(IllegalArgumentException.class, () -> cardPaymentProcessor.process(request), "Unsupported currency: XYZ. We only support [USD, EUR, GBP]");
    }
}
