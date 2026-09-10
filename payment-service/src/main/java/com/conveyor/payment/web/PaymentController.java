package com.conveyor.payment.web;

import com.conveyor.payment.domain.PaymentNotFoundException;
import com.conveyor.payment.repository.PaymentRepository;
import com.conveyor.payment.web.dto.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ARCHITECTURE.md §10.4. */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

  private final PaymentRepository paymentRepository;

  public PaymentController(PaymentRepository paymentRepository) {
    this.paymentRepository = paymentRepository;
  }

  @GetMapping("/{orderId}")
  @Operation(summary = "The payment for one order, if any.")
  public PaymentResponse get(@PathVariable UUID orderId) {
    return paymentRepository
        .findByOrderId(orderId)
        .map(PaymentResponse::from)
        .orElseThrow(() -> PaymentNotFoundException.forOrderId(orderId));
  }
}
