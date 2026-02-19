package com.checkout.payment.gateway.api.processor;

import com.checkout.payment.gateway.api.dto.PaymentRequest;
import com.checkout.payment.gateway.api.dto.PaymentResponse;
import com.checkout.payment.gateway.infrastructure.banksim.BankSimulatorClient;
import com.checkout.payment.gateway.infrastructure.dto.BankPaymentRequest;
import com.checkout.payment.gateway.infrastructure.dto.BankPaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class CardPaymentProcessor implements PaymentProcessor {

    private final BankSimulatorClient bankClient;
    private static final List<String> SUPPORTED_CURRENCIES = List.of("USD", "EUR", "GBP");

    @Override
    public boolean supports(String type) {
        return "CARD".equalsIgnoreCase(type);
    }

    @Override
    public PaymentResponse process(PaymentRequest request) {
        log.info("Processing CARD payment for amount: {} {}", request.getAmount(), request.getCurrency());

        validate(request);

        // Extract from the flexible bag
        String cardNumber = String.valueOf(request.get("card_number"));
        String cvv = String.valueOf(request.get("cvv"));
        Integer expiryMonth = Integer.parseInt(String.valueOf(request.get("expiry_month")));
        Integer expiryYear = Integer.parseInt(String.valueOf(request.get("expiry_year")));
        String expiry = String.format("%02d/%d", expiryMonth, expiryYear);

        // Build Bank Request
        BankPaymentRequest bankRequest = new BankPaymentRequest();
        bankRequest.addProperty("amount", request.getAmount());
        bankRequest.addProperty("currency", request.getCurrency());
        bankRequest.addProperty("card_number", cardNumber);
        bankRequest.addProperty("expiry_date", expiry);
        bankRequest.addProperty("cvv", cvv);

        // Call Bank
        BankPaymentResponse bankResponse = bankClient.processPayment(bankRequest);

        // Handle Response Logic - including validation of the bank's response
        boolean isTimeout = Boolean.TRUE.equals(bankResponse.get("indeterminate"));
        Object authorizedObj = bankResponse.get("authorized");
        
        // A response is indeterminate if it was a timeout OR if it's not a timeout but is missing the 'authorized' field.
        boolean isIndeterminate = isTimeout || authorizedObj == null;
        boolean isAuthorized = Boolean.TRUE.equals(authorizedObj);

        String internalStatus;
        String message;

        if (isIndeterminate) {
            internalStatus = "PENDING_RECONCILIATION";
            message = isTimeout ? "Bank timeout" : "Malformed bank response";
        } else {
            internalStatus = isAuthorized ? "AUTHORIZED" : "DECLINED";
            message = isAuthorized ? "Success" : "Declined";
        }

        // Build Flexible PaymentResponse
        PaymentResponse response = PaymentResponse.builder()
                .status(internalStatus)
                .message(message)
                .build();

        response.add("type", "CARD");
        response.add("masked_card_number", maskCardNumber(cardNumber));
        response.add("card_type", detectCardType(cardNumber));
        response.add("expiry_month", expiryMonth);
        response.add("expiry_year", expiryYear);
        response.add("amount", request.getAmount());
        response.add("currency", request.getCurrency());
        response.add("authorization_code", bankResponse.get("authorization_code"));


        return response;
    }

    private void validate(PaymentRequest request) {
        // --- Currency ---
        if (!SUPPORTED_CURRENCIES.contains(request.getCurrency())) {
            throw new IllegalArgumentException("Unsupported currency: " + request.getCurrency() + ". We only support " + SUPPORTED_CURRENCIES);
        }

        // --- Card Number ---
        Object cardNumberObj = request.get("card_number");
        if (cardNumberObj == null) {
            throw new IllegalArgumentException("Card number is required.");
        }
        String cardNumber = cardNumberObj.toString();
        if (!cardNumber.matches("^[0-9]{14,19}$")) {
            throw new IllegalArgumentException("Card number must be 14-19 numeric characters long.");
        }

        // --- CVV ---
        Object cvvObj = request.get("cvv");
        if (cvvObj == null) {
            throw new IllegalArgumentException("CVV is required.");
        }
        String cvv = cvvObj.toString();
        if (!cvv.matches("^[0-9]{3,4}$")) {
            throw new IllegalArgumentException("CVV must be 3-4 numeric characters long.");
        }

        // --- Expiry ---
        Object expiryMonthObj = request.get("expiry_month");
        Object expiryYearObj = request.get("expiry_year");
        if (expiryMonthObj == null || expiryYearObj == null) {
            throw new IllegalArgumentException("Expiry month and year are required.");
        }

        try {
            Integer expiryMonth = Integer.parseInt(expiryMonthObj.toString());
            Integer expiryYear = Integer.parseInt(expiryYearObj.toString());

            if (expiryMonth < 1 || expiryMonth > 12) {
                throw new IllegalArgumentException("Expiry month must be between 1 and 12.");
            }

            LocalDate now = LocalDate.now();
            if (expiryYear < now.getYear() || (expiryYear.equals(now.getYear()) && expiryMonth < now.getMonthValue())) {
                throw new IllegalArgumentException("Card expiry date must be in the future.");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expiry month and year must be numbers.");
        }
    }

    @Override
    public void mapDetailsToResponse(Map<String, Object> dbDetails, PaymentResponse response) {
        if (dbDetails == null) return;

        // Mandatory Card Masking: Extract last 4 from the 'masked_card_number' in the bag
        String maskedCard = String.valueOf(dbDetails.getOrDefault("masked_card_number", ""));
        if (maskedCard.length() >= 4) {
            response.add("last_four_card_digits", maskedCard.substring(maskedCard.length() - 4));
        }

        // Mandatory Expiry Details
        response.add("expiry_month", dbDetails.get("expiry_month"));
        response.add("expiry_year", dbDetails.get("expiry_year"));

        // Note: PaymentService already handles payment_id, status, amount, and currency
        // from the core entity fields. All other fields (type, card_type, etc.) are ignored.
    }

    private String detectCardType(String pan) {
        if (pan == null) return "UNKNOWN";
        if (pan.startsWith("4")) return "VISA";
        if (pan.startsWith("5")) return "MASTERCARD";
        return "UNKNOWN";
    }

    private String maskCardNumber(String pan) {
        if (pan == null || pan.length() < 4) return "****";
        return "**** **** **** " + pan.substring(pan.length() - 4);
    }
}
