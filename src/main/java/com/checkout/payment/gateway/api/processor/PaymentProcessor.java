package com.checkout.payment.gateway.api.processor;

import com.checkout.payment.gateway.api.dto.PaymentRequest;
import com.checkout.payment.gateway.api.dto.PaymentResponse;

import java.util.Map;

public interface PaymentProcessor {
    boolean supports(String type);
    PaymentResponse process(PaymentRequest request);

    // Each processor defines how to map its specific DB details to a Merchant Response
    void mapDetailsToResponse(Map<String, Object> dbDetails, PaymentResponse response);
}