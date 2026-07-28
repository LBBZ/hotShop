package com.real.security.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.real.common.audit.AuditStateSummary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class AuditSensitiveDataSanitizer {
    static final String REDACTED = "[REDACTED]";

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passphrase|access.?token|refresh.?token|api.?key|"
                    + "cookie|authorization|client.?assertion|full.?prompt|"
                    + "chain.?of.?thought|thought.?chain|reasoning|完整?提示词|思维链|推理).*"
    );
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?is).*(\\bbearer\\s+\\S+|"
                    + "(password|passphrase|access[-_ ]?token|refresh[-_ ]?token|"
                    + "api[-_ ]?key|cookie|authorization|client[-_ ]?assertion|"
                    + "full[-_ ]?prompt|chain[-_ ]?of[-_ ]?thought|thought[-_ ]?chain)"
                    + "\\s*[:=].+|"
                    + "(完整?提示词|思维链|推理)\\s*[:=：].+|"
                    + "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b).*"
    );

    private final ObjectMapper objectMapper;

    public AuditSensitiveDataSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode sanitize(AuditStateSummary summary) {
        return sanitizeNode(objectMapper.valueToTree(summary));
    }

    JsonNode sanitizeNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node.deepCopy();
            for (Map.Entry<String, JsonNode> field : List.copyOf(object.properties())) {
                if (field.getValue() == null || field.getValue().isNull()) {
                    object.remove(field.getKey());
                } else if (SENSITIVE_KEY.matcher(field.getKey()).matches()) {
                    object.set(field.getKey(), TextNode.valueOf(REDACTED));
                } else {
                    object.set(field.getKey(), sanitizeNode(field.getValue()));
                }
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(sanitizeNode(value)));
            return array;
        }
        if (node.isTextual() && SENSITIVE_VALUE.matcher(node.textValue()).matches()) {
            return TextNode.valueOf(REDACTED);
        }
        return node.deepCopy();
    }
}
