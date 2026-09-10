package com.conveyor.dispatch.web;

import com.conveyor.dispatch.domain.ShipmentNotFoundException;
import com.conveyor.dispatch.notification.NotificationRepository;
import com.conveyor.dispatch.repository.ShipmentRepository;
import com.conveyor.dispatch.web.dto.NotificationResponse;
import com.conveyor.dispatch.web.dto.ShipmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ARCHITECTURE.md §10.4. */
@RestController
@Tag(name = "Dispatch")
public class DispatchController {

  private final ShipmentRepository shipmentRepository;
  private final NotificationRepository notificationRepository;

  public DispatchController(
      ShipmentRepository shipmentRepository, NotificationRepository notificationRepository) {
    this.shipmentRepository = shipmentRepository;
    this.notificationRepository = notificationRepository;
  }

  @GetMapping("/api/v1/shipments/{orderId}")
  @Operation(summary = "The shipment for one order, if any.")
  public ShipmentResponse getShipment(@PathVariable UUID orderId) {
    return shipmentRepository
        .findByOrderId(orderId)
        .map(ShipmentResponse::from)
        .orElseThrow(() -> ShipmentNotFoundException.forOrderId(orderId));
  }

  @GetMapping("/api/v1/notifications")
  @Operation(summary = "Every notification logged for one order.")
  public List<NotificationResponse> getNotifications(@RequestParam UUID orderId) {
    return notificationRepository.findByOrderId(orderId.toString()).stream()
        .map(NotificationResponse::from)
        .toList();
  }
}
