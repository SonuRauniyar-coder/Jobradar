package com.jobradar.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.scan.FetchException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

final class JsonSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {}

    static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new FetchException("The response was not valid JSON", e);
        }
    }

    /** Text at a field, or null if it is missing, JSON null or blank. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through to the date-only form
        }
        try {
            return LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
