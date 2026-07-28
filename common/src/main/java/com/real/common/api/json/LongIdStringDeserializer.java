package com.real.common.api.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.util.regex.Pattern;

public class LongIdStringDeserializer extends JsonDeserializer<Long> {
    private static final Pattern ID = Pattern.compile("^[1-9][0-9]{0,18}$");

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING || !ID.matcher(parser.getText()).matches()) {
            throw InvalidFormatException.from(
                    parser,
                    "ID must be a positive decimal JSON string",
                    parser.getText(),
                    Long.class
            );
        }
        try {
            return Long.valueOf(parser.getText());
        } catch (NumberFormatException exception) {
            throw InvalidFormatException.from(
                    parser,
                    "ID is outside the signed 64-bit range",
                    parser.getText(),
                    Long.class
            );
        }
    }
}
