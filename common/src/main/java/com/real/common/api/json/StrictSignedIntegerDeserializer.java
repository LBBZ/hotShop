package com.real.common.api.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;


/** Inventory deltas must not be truncated or coerced from strings or booleans. */
public final class StrictSignedIntegerDeserializer extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw InvalidFormatException.from(parser, "Delta must be a signed JSON integer",
                    parser.getString(), Integer.class);
        }
        return parser.getIntValue();
    }
}
