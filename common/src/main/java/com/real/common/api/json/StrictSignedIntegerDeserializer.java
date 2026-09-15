package com.real.common.api.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;

/** Inventory deltas must not be truncated or coerced from strings or booleans. */
public final class StrictSignedIntegerDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw InvalidFormatException.from(parser, "Delta must be a signed JSON integer",
                    parser.getText(), Integer.class);
        }
        return parser.getIntValue();
    }
}
