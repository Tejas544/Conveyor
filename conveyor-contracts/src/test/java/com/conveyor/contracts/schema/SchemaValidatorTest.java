package com.conveyor.contracts.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.ValidationMessage;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

  private static final String VALID_ORDER_PLACED =
      """
      {
        "eventId": "018f6f1e-0000-7000-8000-000000000001",
        "eventType": "OrderPlaced",
        "schemaVersion": 1,
        "occurredAt": "2026-09-10T10:15:03.421Z",
        "producer": "order-service",
        "sagaId": null,
        "orderId": "44a1e4b2-0000-4000-8000-000000000002",
        "correlationId": "44a1e4b2-0000-4000-8000-000000000002",
        "causationId": null,
        "payload": {
          "customerId": "9b2ecb1a-0000-4000-8000-000000000003",
          "items": [{ "sku": "SKU-1042", "quantity": 2, "unitPrice": 9.99 }],
          "totalAmount": 19.98,
          "currency": "USD",
          "shippingAddress": { "line1": "1 Test St", "city": "Testville", "postalCode": "00000", "country": "IN" },
          "paymentMethodToken": "tok_test_visa"
        }
      }
      """;

  @Test
  void validEnvelopePassesBothTheEnvelopeAndOrderPlacedSchemas() {
    Set<ValidationMessage> envelopeErrors =
        SchemaValidator.validate("envelope.schema.json", VALID_ORDER_PLACED);
    assertTrue(envelopeErrors.isEmpty(), () -> "unexpected envelope errors: " + envelopeErrors);

    Set<ValidationMessage> orderPlacedErrors =
        SchemaValidator.validate("order-placed.schema.json", VALID_ORDER_PLACED);
    assertTrue(
        orderPlacedErrors.isEmpty(), () -> "unexpected order-placed errors: " + orderPlacedErrors);
  }

  @Test
  void missingRequiredPayloadFieldFailsValidation() {
    String missingCurrency = VALID_ORDER_PLACED.replace("\"currency\": \"USD\",", "");

    Set<ValidationMessage> errors =
        SchemaValidator.validate("order-placed.schema.json", missingCurrency);
    assertFalse(errors.isEmpty());
  }

  @Test
  void wrongEventTypeFailsValidation() {
    String wrongType = VALID_ORDER_PLACED.replace("\"OrderPlaced\"", "\"SomethingElse\"");

    Set<ValidationMessage> errors = SchemaValidator.validate("order-placed.schema.json", wrongType);
    assertFalse(errors.isEmpty());
  }
}
