package com.real.common.api.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;

public final class AgentStrictPositiveIntegerDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw InvalidFormatException.from(
                    parser,
                    "Quantity must be a positive JSON integer",
                    parser.getText(),
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
