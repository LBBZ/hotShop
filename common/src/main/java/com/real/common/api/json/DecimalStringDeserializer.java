package com.real.common.api.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.regex.Pattern;

public class DecimalStringDeserializer extends JsonDeserializer<BigDecimal> {
    private static final Pattern MONEY = Pattern.compile("^(0|[1-9][0-9]*)\\.[0-9]{2}$");

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING || !MONEY.matcher(parser.getText()).matches()) {
            throw InvalidFormatException.from(
                    parser,
                    "Money must be a JSON string with exactly two decimal places",
                    parser.getText(),
                    BigDecimal.class
            );
        }
        return new BigDecimal(parser.getText());
    }
}
