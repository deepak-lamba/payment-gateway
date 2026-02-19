package com.checkout.payment.gateway.api.repository;

import com.checkout.payment.gateway.api.model.PaymentAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentAuditRepository extends JpaRepository<PaymentAudit, Long> {
}