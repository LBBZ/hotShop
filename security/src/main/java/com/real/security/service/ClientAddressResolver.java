package com.real.security.service;

import com.real.security.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ClientAddressResolver {
    static final int MAX_FORWARDED_HEADER_LENGTH = 1_024;
    static final int MAX_FORWARDED_HOPS = 32;

    private final boolean trustForwardedHeaders;
    private final Set<String> trustedProxyAddresses;

    public ClientAddressResolver(SecurityProperties properties) {
        SecurityProperties.RateLimit rateLimit = properties.getRateLimit();
        this.trustForwardedHeaders = rateLimit.isTrustForwardedHeaders();
        this.trustedProxyAddresses = normalizeTrustedProxies(
                rateLimit.getTrustedProxyAddresses()
        );
    }

    public String resolve(HttpServletRequest request) {
        String immediatePeer = normalizeLiteral(request.getRemoteAddr());
        if (immediatePeer == null) {
            return "unknown";
        }
        if (!trustForwardedHeaders || !trustedProxyAddresses.contains(immediatePeer)) {
            return immediatePeer;
        }

        List<String> forwardedChain = parseForwardedChain(request);
        if (forwardedChain == null) {
            return immediatePeer;
        }
        for (int index = forwardedChain.size() - 1; index >= 0; index--) {
            String candidate = forwardedChain.get(index);
            if (!trustedProxyAddresses.contains(candidate)) {
                return candidate;
            }
        }
        return immediatePeer;
    }

    private List<String> parseForwardedChain(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("X-Forwarded-For");
        if (values == null) {
            return null;
        }
        List<String> headers = Collections.list(values);
        if (headers.size() != 1) {
            return null;
        }
        String header = headers.getFirst();
        if (!StringUtils.hasText(header)
                || header.length() > MAX_FORWARDED_HEADER_LENGTH) {
            return null;
        }
        String[] elements = header.split(",", -1);
        if (elements.length == 0 || elements.length > MAX_FORWARDED_HOPS) {
            return null;
        }
        List<String> normalized = new ArrayList<>(elements.length);
        for (String element : elements) {
            if (!StringUtils.hasText(element)) {
                return null;
            }
            String literal = normalizeLiteral(element.trim());
            if (literal == null) {
                return null;
            }
            normalized.add(literal);
        }
        return List.copyOf(normalized);
    }

    private Set<String> normalizeTrustedProxies(Set<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String configuredAddress : configured) {
            if (!StringUtils.hasText(configuredAddress)) {
                throw new IllegalStateException(
                        "Trusted proxy addresses must be IPv4 or IPv6 literals"
                );
            }
            String literal = normalizeLiteral(configuredAddress.trim());
            if (literal == null) {
                throw new IllegalStateException(
                        "Trusted proxy addresses must be IPv4 or IPv6 literals"
                );
            }
            normalized.add(literal);
        }
        return Set.copyOf(normalized);
    }

    static String normalizeLiteral(String address) {
        if (!StringUtils.hasText(address)
                || !address.equals(address.trim())) {
            return null;
        }
        if (address.indexOf(':') >= 0) {
            return normalizeIpv6(address);
        }
        return normalizeIpv4(address);
    }

    private static String normalizeIpv4(String address) {
        String[] elements = address.split("\\.", -1);
        if (elements.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int index = 0; index < elements.length; index++) {
            String element = elements[index];
            if (element.isEmpty() || element.length() > 3) {
                return null;
            }
            int value = 0;
            for (int characterIndex = 0; characterIndex < element.length(); characterIndex++) {
                char character = element.charAt(characterIndex);
                if (character < '0' || character > '9') {
                    return null;
                }
                value = value * 10 + (character - '0');
            }
            if (value > 255) {
                return null;
            }
            octets[index] = value;
        }
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }

    private static String normalizeIpv6(String address) {
        if (!address.matches("^[0-9A-Fa-f:.]+$")) {
            return null;
        }
        int compression = address.indexOf("::");
        if (compression >= 0 && address.indexOf("::", compression + 2) >= 0) {
            return null;
        }

        String leftText = compression >= 0 ? address.substring(0, compression) : address;
        String rightText = compression >= 0 ? address.substring(compression + 2) : "";
        List<Integer> left = parseIpv6Groups(leftText, compression < 0);
        List<Integer> right = parseIpv6Groups(rightText, true);
        if (left == null || right == null) {
            return null;
        }
        int represented = left.size() + right.size();
        if ((compression < 0 && represented != 8)
                || (compression >= 0 && represented >= 8)) {
            return null;
        }

        int[] words = new int[8];
        int output = 0;
        for (int word : left) {
            words[output++] = word;
        }
        if (compression >= 0) {
            output += 8 - represented;
        }
        for (int word : right) {
            words[output++] = word;
        }
        return formatIpv6(words);
    }

    private static List<Integer> parseIpv6Groups(String text, boolean finalPart) {
        if (text.isEmpty()) {
            return List.of();
        }
        String[] groups = text.split(":", -1);
        List<Integer> words = new ArrayList<>(groups.length);
        for (int index = 0; index < groups.length; index++) {
            String group = groups[index];
            if (group.isEmpty()) {
                return null;
            }
            if (group.indexOf('.') >= 0) {
                if (!finalPart || index != groups.length - 1) {
                    return null;
                }
                String ipv4 = normalizeIpv4(group);
                if (ipv4 == null) {
                    return null;
                }
                String[] octets = ipv4.split("\\.");
                words.add((Integer.parseInt(octets[0]) << 8) | Integer.parseInt(octets[1]));
                words.add((Integer.parseInt(octets[2]) << 8) | Integer.parseInt(octets[3]));
            } else {
                if (group.length() > 4) {
                    return null;
                }
                try {
                    words.add(Integer.parseInt(group, 16));
                } catch (NumberFormatException exception) {
                    return null;
                }
            }
        }
        return words;
    }

    private static String formatIpv6(int[] words) {
        int bestStart = -1;
        int bestLength = 0;
        for (int index = 0; index < words.length; ) {
            if (words[index] != 0) {
                index++;
                continue;
            }
            int end = index;
            while (end < words.length && words[end] == 0) {
                end++;
            }
            int length = end - index;
            if (length >= 2 && length > bestLength) {
                bestStart = index;
                bestLength = length;
            }
            index = end;
        }

        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < words.length; index++) {
            if (index == bestStart) {
                normalized.append("::");
                index += bestLength - 1;
                continue;
            }
            if (!normalized.isEmpty()
                    && normalized.charAt(normalized.length() - 1) != ':') {
                normalized.append(':');
            }
            normalized.append(Integer.toHexString(words[index]).toLowerCase(Locale.ROOT));
        }
        return normalized.toString();
    }
}
