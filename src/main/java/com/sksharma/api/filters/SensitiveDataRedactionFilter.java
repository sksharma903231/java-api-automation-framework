package com.sksharma.api.filters;

import com.fasterxml.jackson.core.JsonProcessingException;
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
 *
 * <p><b>Logging-Limit Policy and Distinction:</b></p>
 * <ul>
 *   <li><b>Oversized Request Bodies:</b> Request bodies exceeding {@link #MAX_LOG_BODY_LENGTH}
 *       characters or bytes are excluded from log output prior to string processing or JSON parsing.
 *       Direct-representation types ({@link CharSequence}, {@code byte[]}, {@link java.io.File})
 *       are size-checked before conversion or file reading.</li>
 *   <li><b>Oversized Response Bodies:</b> Responses with declared {@code Content-Length} or actual
 *       byte size exceeding {@link #MAX_LOG_BODY_LENGTH} bytes are excluded from body logging prior
 *       to Jackson JSON tree parsing, preventing excessive memory and GC overhead.</li>
 *   <li><b>Non-JSON Responses:</b> Non-JSON responses are excluded without decoding or converting
 *       the underlying response body to a String, avoiding arbitrary memory allocation for large
 *       HTML or binary payloads. Downstream response readability is preserved.</li>
 *   <li><b>Truncated Bodies:</b> When a body is within buffering limits but its sanitized representation
 *       expands beyond {@link #MAX_LOG_BODY_LENGTH} characters due to sensitive field replacement,
 *       it is truncated such that the complete logged body output, including the diagnostic
 *       truncation marker, does not exceed {@link #MAX_LOG_BODY_LENGTH} characters.</li>
 *   <li><b>Underlying Network Buffering & POJO Limitations:</b>
 *       <ol>
 *         <li>By design, REST Assured's HTTP transport layer buffers complete HTTP responses
 *             in-memory (within {@code RestAssuredResponseImpl}) to allow downstream test assertions.
 *             This filter bounds logging memory and CPU overhead but cannot alter REST Assured's
 *             underlying HTTP transport buffering behavior.</li>
 *         <li>For arbitrary Java object payloads (POJOs, Maps), the serialized length cannot be
 *             known prior to object conversion; for these types, the size guard evaluates the
 *             converted string before JSON parsing and redaction.</li>
 *       </ol>
 *   </li>
 * </ul>
 */
public class SensitiveDataRedactionFilter implements Filter {

    public static final int MAX_LOG_BODY_LENGTH = 10_000;
    public static final String TRUNCATION_MARKER = "... [Truncated to " + MAX_LOG_BODY_LENGTH + " characters]";

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
            "passwd",
            "accesstoken",
            "refreshtoken",
            "token",
            "secret",
            "clientsecret",
            "apikey",
            "auth",
            "credentials",
            "privatekey",
            "sessionid",
            "jwt",
            "idtoken",
            "authorization",
            "cookie",
            "setcookie"
    );

    private final boolean logBodyOverride;
    private final boolean hasBodyOverride;
    private volatile String lastSanitizedRequest;
    private volatile String lastSanitizedResponse;

    public SensitiveDataRedactionFilter() {
        this.hasBodyOverride = false;
        this.logBodyOverride = false;
    }

    public SensitiveDataRedactionFilter(boolean enableBodyLogging) {
        this.hasBodyOverride = true;
        this.logBodyOverride = enableBodyLogging;
    }

    public String getLastSanitizedRequest() {
        return lastSanitizedRequest;
    }

    public String getLastSanitizedResponse() {
        return lastSanitizedResponse;
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
            java.util.regex.Pattern.compile("(?i)([?&])(client[_-]?secret|access[_-]?token|refresh[_-]?token|private[_-]?key|api[_-]?key|passwords?|passwd|credentials|secrets?|tokens?|auth|jwt|id[_-]?token|authorization|cookies?|set[_-]?cookie|session[_-]?id)(=)(?!\\[REDACTED\\])((?:(?!&|\\s|[\"'\\]}>]).)*)");
    private static final java.util.regex.Pattern ENCODED_QUERY_PATTERN =
            java.util.regex.Pattern.compile("(?i)(%3f|%26|&|\\b)(client[_-]?secret|access[_-]?token|refresh[_-]?token|private[_-]?key|api[_-]?key|passwords?|passwd|credentials|secrets?|tokens?|auth|jwt|id[_-]?token|authorization|cookies?|set[_-]?cookie|session[_-]?id)(%3d)(?!\\[REDACTED\\])((?:(?!&|\\s|[\"'\\]}>]|%26(?=[a-zA-Z0-9_.-]+%3d)).)*)");
    private static final java.util.regex.Pattern KEY_VALUE_SECRET_PATTERN =
            java.util.regex.Pattern.compile("(?i)(\"?(?:client[_-]?secret|access[_-]?token|refresh[_-]?token|private[_-]?key|api[_-]?key|passwords?|passwd|credentials|secrets?|tokens?|auth|jwt|id[_-]?token|authorization|cookies?|set[_-]?cookie|session[_-]?id)\"?\\s*[:=]\\s*[\"']?)(?!\\[REDACTED\\]|Bearer\\b)([^\"',;&\\s}\\]]+)([\"']?)");
    private static final java.util.regex.Pattern URI_USERINFO_PATTERN =
            java.util.regex.Pattern.compile("(?i)([a-zA-Z][a-zA-Z0-9+\\-.]*://)([^:/@\\s]+):([^@\\s]+)@");

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext filterContext) {

        try {
            String sanitizedRequest = buildSanitizedRequest(requestSpec);
            this.lastSanitizedRequest = sanitizedRequest;
            LOG.info("HTTP Outgoing Request:\n{}", sanitizedRequest);
            attachToAllure("HTTP Request - " + requestSpec.getMethod() + " " + extractSanitizedPath(requestSpec), sanitizedRequest);
        } catch (Exception e) {
            LOG.warn("Failed to log or attach outgoing request due to an internal processing error");
        }

        Response response = filterContext.next(requestSpec, responseSpec);

        try {
            String sanitizedResponse = buildSanitizedResponse(response);
            this.lastSanitizedResponse = sanitizedResponse;
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
            if (root.isTextual()) {
                return MAPPER.writeValueAsString(sanitizeTextNode(root).asText());
            }
            redactNode(root);
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return "[Non-JSON or Unparseable Payload]";
        }
    }

    private static JsonNode sanitizeTextNode(JsonNode node) {
        if (node != null && node.isTextual()) {
            String text = node.asText();
            java.util.regex.Matcher matcher = BEARER_PATTERN.matcher(text);
            if (matcher.find()) {
                return new TextNode(matcher.replaceAll("Bearer " + REDACTED_MARKER));
            }
        }
        return node;
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
                } else if (field.getValue().isTextual()) {
                    field.setValue(sanitizeTextNode(field.getValue()));
                } else {
                    redactNode(field.getValue());
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode element = arrayNode.get(i);
                if (element.isTextual()) {
                    arrayNode.set(i, sanitizeTextNode(element));
                } else {
                    redactNode(element);
                }
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

    private static String truncateBody(String body) {
        if (body == null || body.length() <= MAX_LOG_BODY_LENGTH) {
            return body;
        }
        int keepChars = MAX_LOG_BODY_LENGTH - TRUNCATION_MARKER.length();
        if (keepChars < 0) {
            keepChars = 0;
        }
        return body.substring(0, keepChars) + TRUNCATION_MARKER;
    }

    private String buildSanitizedRequest(FilterableRequestSpecification requestSpec) {
        StringBuilder sb = new StringBuilder();
        // Log method and sanitized path only. Query parameters are explicitly omitted.
        sb.append(requestSpec.getMethod()).append(" ").append(extractSanitizedPath(requestSpec)).append("\n");

        sb.append("--- Safe Headers ---\n");
        appendSafeHeaders(sb, requestSpec.getHeaders());

        if (isBodyLoggingActive()) {
            String contentType = requestSpec.getContentType();
            Object rawBody = requestSpec.getBody();
            if (isJsonContentType(contentType) && rawBody != null) {
                // 1. Explicit size guards for supported direct-representation body types before string conversion
                if (rawBody instanceof CharSequence cs) {
                    if (cs.length() > MAX_LOG_BODY_LENGTH) {
                        sb.append("--- Body ---\n[Request body exceeds logging limit of ")
                                .append(MAX_LOG_BODY_LENGTH)
                                .append(" characters (Actual length: ")
                                .append(cs.length())
                                .append("). Body excluded from logs to prevent log flooding.]\n");
                        return sb.toString();
                    }
                } else if (rawBody instanceof byte[] bytes) {
                    if (bytes.length > MAX_LOG_BODY_LENGTH) {
                        sb.append("--- Body ---\n[Request body exceeds logging limit of ")
                                .append(MAX_LOG_BODY_LENGTH)
                                .append(" bytes (Actual size: ")
                                .append(bytes.length)
                                .append(" bytes). Body excluded from logs to prevent log flooding.]\n");
                        return sb.toString();
                    }
                } else if (rawBody instanceof java.io.File file) {
                    if (file.length() > MAX_LOG_BODY_LENGTH) {
                        sb.append("--- Body ---\n[Request body exceeds logging limit of ")
                                .append(MAX_LOG_BODY_LENGTH)
                                .append(" bytes (File size: ")
                                .append(file.length())
                                .append(" bytes). Body excluded from logs to prevent log flooding.]\n");
                        return sb.toString();
                    }
                } else if (rawBody instanceof java.io.InputStream) {
                    // Streams cannot be inspected in advance without consuming them, which breaks request transmission
                    sb.append("--- Body ---\n[Stream Content Excluded from Logs to Preserve Request Transmission]\n");
                    return sb.toString();
                }

                // For byte[], decode directly to UTF-8 without Jackson. For other objects (POJOs, Maps), obtain string representation.
                String bodyStr = (rawBody instanceof byte[] bytes)
                        ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                        : rawBody.toString();

                if (bodyStr.length() > MAX_LOG_BODY_LENGTH) {
                    sb.append("--- Body ---\n[Request body exceeds logging limit of ")
                            .append(MAX_LOG_BODY_LENGTH)
                            .append(" characters (Actual length: ")
                            .append(bodyStr.length())
                            .append("). Body excluded from logs to prevent log flooding.]\n");
                    return sb.toString();
                }

                sb.append("--- Body ---\n");
                String redacted = redactJsonPayload(bodyStr);
                if (redacted.length() > MAX_LOG_BODY_LENGTH) {
                    sb.append(truncateBody(redacted)).append("\n");
                } else {
                    sb.append(redacted).append("\n");
                }
            } else if (rawBody != null) {
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
            if (isJsonContentType(contentType)) {
                // 1. Check declared Content-Length header before accessing response body
                String contentLengthHeader = response.getHeader("Content-Length");
                if (contentLengthHeader != null) {
                    try {
                        long declaredLength = Long.parseLong(contentLengthHeader.trim());
                        if (declaredLength > MAX_LOG_BODY_LENGTH) {
                            sb.append("--- Body ---\n[Payload exceeds logging limit of ")
                                    .append(MAX_LOG_BODY_LENGTH)
                                    .append(" bytes (Content-Length: ")
                                    .append(declaredLength)
                                    .append("). Body excluded from logs to prevent log flooding.]\n");
                            return sb.toString();
                        }
                    } catch (NumberFormatException ignored) {
                        // If invalid Content-Length, proceed to actual byte size check
                    }
                }

                // 2. Safe check on actual byte size (handles missing or inaccurate Content-Length)
                // Accessing asByteArray utilizes REST Assured's cached response bytes without consuming stream
                byte[] bodyBytes = response.asByteArray();
                if (bodyBytes == null || bodyBytes.length == 0) {
                    return sb.toString();
                }

                if (bodyBytes.length > MAX_LOG_BODY_LENGTH) {
                    sb.append("--- Body ---\n[Payload exceeds logging limit of ")
                            .append(MAX_LOG_BODY_LENGTH)
                            .append(" bytes (Actual size: ")
                            .append(bodyBytes.length)
                            .append(" bytes). Body excluded from logs to prevent excessive memory and log consumption.]\n");
                    return sb.toString();
                }

                // 3. Body size is within limits: convert to UTF-8 String and redact
                String body = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
                if (!body.isBlank()) {
                    sb.append("--- Body ---\n");
                    String redacted = redactJsonPayload(body);
                    if (redacted.length() > MAX_LOG_BODY_LENGTH) {
                        sb.append(truncateBody(redacted)).append("\n");
                    } else {
                        sb.append(redacted).append("\n");
                    }
                }
            } else {
                // Non-JSON response: apply exclusion policy without decoding or converting body to String
                String contentLengthHeader = response.getHeader("Content-Length");
                boolean hasContent = false;
                if (contentLengthHeader != null) {
                    try {
                        long declaredLength = Long.parseLong(contentLengthHeader.trim());
                        if (declaredLength > 0) {
                            hasContent = true;
                        }
                    } catch (NumberFormatException ignored) {
                        // Malformed Content-Length: fallback to byte length inspection below
                    }
                }

                // If declared length was <= 0, invalid, or missing, check Transfer-Encoding or cached response byte length
                if (!hasContent) {
                    if (response.getHeader("Transfer-Encoding") != null) {
                        hasContent = true;
                    } else {
                        byte[] bodyBytes = response.asByteArray();
                        hasContent = bodyBytes != null && bodyBytes.length > 0;
                    }
                }

                if (hasContent) {
                    sb.append("--- Body ---\n[Non-JSON or Binary Content Excluded]\n");
                }
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
