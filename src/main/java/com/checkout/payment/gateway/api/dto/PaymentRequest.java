package com.checkout.payment.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {

    // Core fields we always expect for a payment
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private Long amount;

    @NotBlank(message = "Currency is required")
    private String currency;

    @JsonIgnore
    private final Map<String, Object> data = new HashMap<>();

    public Long getAmount() { return amount; }
    public String getCurrency() { return currency; }

    @JsonAnySetter
    public void add(String key, Object value) {
        this.data.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getData() {
        return data;
    }

    @JsonIgnore
    public Object get(String key) {
        return data.get(key);
    }

    @JsonIgnore
    public String getType() {
        return data.containsKey("type") ? data.get("type").toString() : "UNKNOWN";
    }
}
