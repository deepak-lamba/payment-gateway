package com.checkout.payment.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    @JsonProperty("payment_id")
    private UUID paymentId;

    private String status;
    private String message;

    @JsonIgnore
    private final Map<String, Object> details = new HashMap<>();

    @JsonAnySetter
    public void add(String key, Object value) {
        this.details.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getDetails() {
        return details;
    }
}