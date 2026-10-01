package com.real.common.api.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.regex.Pattern;

public class LongIdStringDeserializer extends ValueDeserializer<Long> {
    private static final Pattern ID = Pattern.compile("^[1-9][0-9]{0,18}$");

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING || !ID.matcher(parser.getString()).matches()) {
            throw InvalidFormatException.from(
                    parser,
                    "ID must be a positive decimal JSON string",
                    parser.getString(),
                    Long.class
            );
        }
        try {
            return Long.valueOf(parser.getString());
        } catch (NumberFormatException exception) {
            throw InvalidFormatException.from(
                    parser,
                    "ID is outside the signed 64-bit range",
                    parser.getString(),
                    Long.class
            );
        }
    }
}
