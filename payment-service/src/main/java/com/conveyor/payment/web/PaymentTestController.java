package com.conveyor.payment.web;

import com.conveyor.payment.gateway.MockPaymentGateway;
import com.conveyor.payment.web.dto.FailureModeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ARCHITECTURE.md §10.4, §14: a test-only control panel for {@link MockPaymentGateway}, active only
 * under the {@code chaos} Spring profile. {@link
 * com.conveyor.payment.config.ChaosProfileStartupGuard} refuses to let the app start at all if
 * {@code prod} is active alongside {@code chaos}, so this class itself only needs the
 * {@code @Profile} guard, not a second runtime check.
 */
@RestController
@RequestMapping("/test")
@Profile("chaos")
@Tag(name = "Chaos (test-only)")
public class PaymentTestController {

  private final MockPaymentGateway gateway;

  public PaymentTestController(MockPaymentGateway gateway) {
    this.gateway = gateway;
  }

  @PostMapping("/failure-mode")
  @Operation(
      summary = "Arm the mock gateway to fail every charge with this mode at this probability.")
  public void setFailureMode(@Valid @RequestBody FailureModeRequest request) {
    gateway.armFailureMode(request.mode(), request.probability());
  }
}
