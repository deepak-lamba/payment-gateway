package com.checkout.payment.gateway.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BankPaymentResponse {

    @JsonIgnore
    private Map<String, Object> rawData = new HashMap<>();

    @JsonAnySetter
    public void add(String key, Object value) {
        rawData.put(key, value);
    }

    public Object get(String key) {
        return rawData.get(key);
    }
}