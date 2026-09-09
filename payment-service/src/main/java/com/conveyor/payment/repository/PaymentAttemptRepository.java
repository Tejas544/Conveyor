package com.conveyor.payment.repository;

import com.conveyor.payment.domain.PaymentAttempt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

  Optional<PaymentAttempt> findByIdempotencyKey(String idempotencyKey);
}
