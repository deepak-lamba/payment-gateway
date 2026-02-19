package com.checkout.payment.gateway.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@NoArgsConstructor
public class BankPaymentRequest {

    @JsonIgnore
    private final Map<String, Object> properties = new HashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getProperties() {
        return properties;
    }

    public void addProperty(String key, Object value) {
        this.properties.put(key, value);
    }
}