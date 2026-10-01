package com.real.common.api.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

import java.math.BigDecimal;
import java.util.regex.Pattern;

public class DecimalStringDeserializer extends ValueDeserializer<BigDecimal> {
    private static final Pattern MONEY = Pattern.compile("^(0|[1-9][0-9]*)\\.[0-9]{2}$");

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING || !MONEY.matcher(parser.getString()).matches()) {
            throw InvalidFormatException.from(
                    parser,
                    "Money must be a JSON string with exactly two decimal places",
                    parser.getString(),
                    BigDecimal.class
            );
        }
        return new BigDecimal(parser.getString());
    }
}
