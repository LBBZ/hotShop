package com.real.common.api.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;


public final class AgentStrictPositiveIntegerDeserializer extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw InvalidFormatException.from(
                    parser,
                    "Quantity must be a positive JSON integer",
                    parser.getString(),
                    Integer.class
            );
        }
        int value = parser.getIntValue();
        if (value <= 0) {
            throw InvalidFormatException.from(
                    parser,
                    "Quantity must be positive",
                    value,
                    Integer.class
            );
        }
        return value;
    }
}
