package com.sksharma.api.filters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sksharma.api.config.ConfigManager;
import io.qameta.allure.Allure;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Intercepts requests and responses to produce sanitized logs and Allure attachments.
 * Network transmission payloads remain untouched.
 */
public class SensitiveDataRedactionFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(SensitiveDataRedactionFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REDACTED_MARKER = "[REDACTED]";

    // Safe diagnostic headers allowlist
    private static final Set<String> SAFE_HEADERS = Set.of(
            "content-type",
            "content-length",
            "date",
            "server",
            "etag",
            "transfer-encoding",
            "connection",
            "cf-ray",
            "vary"
    );

    // Normalized sensitive field names (lowercased without hyphens or underscores)
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password",
            "accesstoken",
            "refreshtoken",
            "token",
            "secret",
            "clientsecret",
            "apikey",
            "auth",
            "credentials",
            "privatekey",
            "sessionid"
    );

    private final boolean logBodyOverride;
    private final boolean hasBodyOverride;

    public SensitiveDataRedactionFilter() {
        this.hasBodyOverride = false;
        this.logBodyOverride = false;
    }

    public SensitiveDataRedactionFilter(boolean enableBodyLogging) {
        this.hasBodyOverride = true;
        this.logBodyOverride = enableBodyLogging;
    }

    private boolean isBodyLoggingActive() {
        if (hasBodyOverride) {
            return logBodyOverride;
        }
        return ConfigManager.getInstance().isBodyLoggingEnabled();
    }

    private static final java.util.regex.Pattern BEARER_PATTERN =
            java.util.regex.Pattern.compile("(?i)bearer\\s+[a-zA-Z0-9_\\-\\.\\+\\/\\=\\~]+");
    private static final java.util.regex.Pattern UNENCODED_QUERY_PATTERN =
            java.util.regex.Pattern.compile("(?i)([?&])(client[_-]?secret|access[_-]?token|refresh[_-]?token|private[_-]?key|api[_-]?key|passwords?|credentials|secrets?|tokens?|auth)(=)(?!\\[REDACTED\\])((?:(?!&|\\s|[\"'\\]}>]).)*)");
    private static final java.util.regex.Pattern ENCODED_QUERY_PATTERN =
            java.util.regex.Pattern.compile("(?i)(%3f|%26|&|\\b)(client[_-]?secret|access[_-]?token|refresh[_-]?token|private[_-]?key|api[_-]?key|passwords?|credentials|secrets?|tokens?|auth)(%3d)(?!\\[REDACTED\\])((?:(?!&|\\s|[\"'\\]}>]|%26(?=[a-zA-Z0-9_.-]+%3d)).)*)");
    private static final java.util.regex.Pattern KEY_VALUE_SECRET_PATTERN =
            java.util.regex.Pattern.compile("(?i)(\"?(?:client[_-]?secret|access[_-]?token|refresh[_-]?token|private[_-]?key|api[_-]?key|passwords?|credentials|secrets?|tokens?)\"?\\s*[:=]\\s*[\"']?)(?!\\[REDACTED\\])([^\"',;&\\s}\\]]*)([\"']?)");
    private static final java.util.regex.Pattern URI_USERINFO_PATTERN =
            java.util.regex.Pattern.compile("(?i)([a-zA-Z][a-zA-Z0-9+\\-.]*://)([^:/@\\s]+):([^@\\s]+)@");

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext filterContext) {

        try {
            String sanitizedRequest = buildSanitizedRequest(requestSpec);
            LOG.info("HTTP Outgoing Request:\n{}", sanitizedRequest);
            attachToAllure("HTTP Request - " + requestSpec.getMethod() + " " + extractSanitizedPath(requestSpec), sanitizedRequest);
        } catch (Exception e) {
            LOG.warn("Failed to log or attach outgoing request due to an internal processing error");
        }

        Response response = filterContext.next(requestSpec, responseSpec);

        try {
            String sanitizedResponse = buildSanitizedResponse(response);
            LOG.info("HTTP Incoming Response:\n{}", sanitizedResponse);
            attachToAllure("HTTP Response - " + response.getStatusCode(), sanitizedResponse);
        } catch (Exception e) {
            LOG.warn("Failed to log or attach incoming response due to an internal processing error");
        }

        return response;
    }

    public static String extractSanitizedPath(FilterableRequestSpecification requestSpec) {
        if (requestSpec == null) {
            return "/";
        }
        String uriStr = requestSpec.getURI();
        if (uriStr == null) {
            String userPath = requestSpec.getUserDefinedPath();
            return cleanPath(userPath);
        }
        try {
            URI uri = URI.create(uriStr);
            String path = uri.getPath();
            return cleanPath(path);
        } catch (Exception e) {
            return cleanPath(requestSpec.getUserDefinedPath());
        }
    }

    private static String cleanPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/";
        }
        // Strip any accidental query string or fragment from path
        int queryIdx = rawPath.indexOf('?');
        if (queryIdx >= 0) {
            rawPath = rawPath.substring(0, queryIdx);
        }
        int fragmentIdx = rawPath.indexOf('#');
        if (fragmentIdx >= 0) {
            rawPath = rawPath.substring(0, fragmentIdx);
        }
        return rawPath.isBlank() ? "/" : rawPath;
    }

    public static boolean isSensitiveField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.toLowerCase(Locale.ROOT).replaceAll("[_-]", "");
        return SENSITIVE_FIELDS.contains(normalized);
    }

    public static String redactJsonPayload(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            redactNode(root);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "[Non-JSON or Unparseable Payload]";
        }
    }

    private static void redactNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveField(field.getKey())) {
                    // Completely redact sensitive value, including nested objects and arrays
                    field.setValue(new TextNode(REDACTED_MARKER));
                } else {
                    redactNode(field.getValue());
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (JsonNode element : arrayNode) {
                redactNode(element);
            }
        }
    }

    public static String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            String jsonRedacted = redactJsonPayload(trimmed);
            if (!"[Non-JSON or Unparseable Payload]".equals(jsonRedacted)) {
                return jsonRedacted;
            }
            return "[Non-JSON or Unparseable Payload]";
        }
        String sanitized = text;
        sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("Bearer [REDACTED]");
        sanitized = UNENCODED_QUERY_PATTERN.matcher(sanitized).replaceAll("$1$2$3[REDACTED]");
        sanitized = ENCODED_QUERY_PATTERN.matcher(sanitized).replaceAll("$1$2$3[REDACTED]");
        sanitized = KEY_VALUE_SECRET_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]$3");
        sanitized = URI_USERINFO_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]:[REDACTED]@");
        return sanitized;
    }

    private String buildSanitizedRequest(FilterableRequestSpecification requestSpec) {
        StringBuilder sb = new StringBuilder();
        // Log method and sanitized path only. Query parameters are explicitly omitted.
        sb.append(requestSpec.getMethod()).append(" ").append(extractSanitizedPath(requestSpec)).append("\n");

        sb.append("--- Safe Headers ---\n");
        appendSafeHeaders(sb, requestSpec.getHeaders());

        if (isBodyLoggingActive()) {
            String contentType = requestSpec.getContentType();
            if (isJsonContentType(contentType) && requestSpec.getBody() != null) {
                sb.append("--- Body ---\n");
                sb.append(redactJsonPayload(requestSpec.getBody().toString())).append("\n");
            } else if (requestSpec.getBody() != null) {
                sb.append("--- Body ---\n[Non-JSON or Binary Content Excluded]\n");
            }
        }
        return sb.toString();
    }

    private String buildSanitizedResponse(Response response) {
        StringBuilder sb = new StringBuilder();
        sb.append("Status: ").append(response.getStatusLine()).append(" (")
                .append(response.getTime()).append(" ms)\n");

        sb.append("--- Safe Headers ---\n");
        appendSafeHeaders(sb, response.getHeaders());

        if (isBodyLoggingActive()) {
            String contentType = response.getContentType();
            if (isJsonContentType(contentType) && response.getBody() != null) {
                String body = response.getBody().asString();
                if (body != null && !body.isBlank()) {
                    sb.append("--- Body ---\n");
                    sb.append(redactJsonPayload(body)).append("\n");
                }
            } else if (response.getBody() != null && !response.getBody().asString().isBlank()) {
                sb.append("--- Body ---\n[Non-JSON or Binary Content Excluded]\n");
            }
        }
        return sb.toString();
    }

    private void appendSafeHeaders(StringBuilder sb, Headers headers) {
        if (headers == null) {
            return;
        }
        for (Header h : headers) {
            String lowerName = h.getName().toLowerCase(Locale.ROOT);
            if (SAFE_HEADERS.contains(lowerName)) {
                sb.append(h.getName()).append(": ").append(h.getValue()).append("\n");
            }
        }
    }

    private boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("application/json") || lower.contains("+json");
    }

    private void attachToAllure(String name, String content) {
        try {
            Allure.addAttachment(name, "text/plain", content);
        } catch (Exception e) {
            LOG.warn("Failed to attach '{}' to Allure due to a reporting error", name);
        }
    }
}
