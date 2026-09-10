package com.conveyor.common.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Every {@code @JdbcTypeCode(SqlTypes.JSON)} field (the {@code outbox}/{@code inbox} pattern's
 * {@code payload}/{@code headers} columns, used identically by every service) is otherwise
 * deserialized by a Hibernate-internal {@code ObjectMapper} that Hibernate constructs itself and
 * calls {@code findAndRegisterModules()} on — picking up <em>whatever</em> Jackson modules happen
 * to be on the classpath via SPI, independently of this application's own Spring-managed {@link
 * ObjectMapper} bean. On this project's test classpath that includes {@code jackson-module-scala}
 * (pulled in transitively, test-scope only, by {@code spring-kafka-test}'s embedded-Kafka support —
 * see BUGS.md BUG-0010), which makes Hibernate's mapper deserialize nested untyped JSON objects as
 * {@code scala.collection.immutable.Map} instead of {@code java.util.LinkedHashMap}, silently and
 * with no code anywhere asking for it.
 *
 * <p>Pointing Hibernate at this application's own {@code ObjectMapper} bean instead of letting it
 * build a private one fixes the root cause rather than working around the symptom in every caller,
 * and is the more correct configuration regardless — one consistently-configured JSON mapper for
 * the whole application, not two that can silently diverge.
 */
@AutoConfiguration
@ConditionalOnClass(JacksonJsonFormatMapper.class)
public class HibernateJsonFormatMapperAutoConfiguration {

  @Bean
  public HibernatePropertiesCustomizer jsonFormatMapperCustomizer(ObjectMapper objectMapper) {
    return properties ->
        properties.put(
            AvailableSettings.JSON_FORMAT_MAPPER, new JacksonJsonFormatMapper(objectMapper));
  }
}
