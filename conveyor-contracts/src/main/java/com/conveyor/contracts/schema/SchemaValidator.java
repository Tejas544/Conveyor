package com.conveyor.contracts.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.Map;
import java.util.Set;

/**
 * ADR-6: every producer contract test validates its emitted event against the published schema in
 * this module's {@code schemas/} resources. Schemas are addressed by file name (e.g. {@code
 * "order-placed.schema.json"}) and resolved from the classpath; {@code $ref:
 * "envelope.schema.json"} inside a per-event schema resolves the same way via the URI mapping
 * below, so schemas never embed a filesystem-specific path.
 */
public final class SchemaValidator {

  private static final String SCHEMA_BASE_URI = "https://conveyor.internal/schemas/";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(
          SpecVersion.VersionFlag.V202012,
          builder ->
              builder.schemaMappers(
                  schemaMappers -> schemaMappers.mapPrefix(SCHEMA_BASE_URI, "classpath:schemas/")));

  private SchemaValidator() {}

  /**
   * @param schemaFileName e.g. {@code "order-placed.schema.json"}, resolved from {@code
   *     classpath:schemas/}
   * @param json the message to validate — typically the full envelope, serialized
   * @return validation error messages; empty means valid
   */
  public static Set<ValidationMessage> validate(String schemaFileName, String json) {
    try {
      JsonSchema schema =
          FACTORY.getSchema(
              SchemaLocation.of(SCHEMA_BASE_URI + schemaFileName), new SchemaValidatorsConfig());
      JsonNode node = OBJECT_MAPPER.readTree(json);
      return schema.validate(node);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to validate against schema " + schemaFileName, e);
    }
  }

  public static Set<ValidationMessage> validate(String schemaFileName, Map<String, Object> json) {
    try {
      return validate(schemaFileName, OBJECT_MAPPER.writeValueAsString(json));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize value for schema validation", e);
    }
  }
}
