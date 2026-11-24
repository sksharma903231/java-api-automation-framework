package com.sksharma.api.tests.filters;

import com.sksharma.api.filters.SensitiveDataRedactionFilter;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import com.sksharma.api.tests.support.LocalHttpTestServer;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Security & Observability")
@Feature("Sensitive Data Redaction")
@Test(groups = "offline")
public class SensitiveDataRedactionFilterTest {

    private static final String IDENTIFIABLE_SECRET = "secret_nested_token_99999_xyz";

    @Test(description = "Verify query parameters are excluded from path extraction to prevent token leakage")
    @Story("Path Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that query parameters are excluded from path extraction so that tokens and credentials cannot leak.")
    public void testQueryParametersOmittedFromPath() {
        FilterableRequestSpecification spec = (FilterableRequestSpecification) new RequestSpecBuilder()
                .setBaseUri("https://dummyjson.com")
                .setBasePath("/users/search")
                .addQueryParam("q", "john")
                .addQueryParam("apiKey", IDENTIFIABLE_SECRET)
                .build();

        String sanitizedPath = SensitiveDataRedactionFilter.extractSanitizedPath(spec);

        assertThat(sanitizedPath)
                .isEqualTo("/users/search")
                .doesNotContain(IDENTIFIABLE_SECRET)
                .doesNotContain("apiKey")
                .doesNotContain("?");
    }

    @Test(description = "Verify Jackson recursively redacts sensitive fields in nested objects and arrays")
    @Story("Payload Redaction")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Validates that sensitive fields inside arbitrary nested JSON objects and arrays are replaced with [REDACTED].")
    public void testNestedJsonBodyRedaction() {
        String complexPayload = """
                {
                  "user": {
                    "username": "automation.test.user",
                    "authData": {
                      "password": "%s",
                      "clientSecret": "%s"
                    }
                  },
                  "items": [
                    { "type": "bearer", "accessToken": "%s" },
                    { "type": "refresh", "refreshToken": "%s" }
                  ],
                  "security": {
                    "apiKey": "%s",
                    "privateKey": "%s"
                  },
                  "metadata": {
                    "requestId": "req-12345",
                    "status": "active"
                  }
                }
                """.formatted(
                IDENTIFIABLE_SECRET,
                IDENTIFIABLE_SECRET,
                IDENTIFIABLE_SECRET,
                IDENTIFIABLE_SECRET,
                IDENTIFIABLE_SECRET,
                IDENTIFIABLE_SECRET
        );

        String redacted = SensitiveDataRedactionFilter.redactJsonPayload(complexPayload);

        assertThat(redacted)
                .as("Redacted payload must not contain any instance of the raw secret")
                .doesNotContain(IDENTIFIABLE_SECRET)
                .contains("\"password\":\"[REDACTED]\"")
                .contains("\"clientSecret\":\"[REDACTED]\"")
                .contains("\"accessToken\":\"[REDACTED]\"")
                .contains("\"refreshToken\":\"[REDACTED]\"")
                .contains("\"apiKey\":\"[REDACTED]\"")
                .contains("\"privateKey\":\"[REDACTED]\"")
                .contains("\"username\":\"automation.test.user\"")
                .contains("\"requestId\":\"req-12345\"")
                .contains("\"status\":\"active\"");
    }

    @Test(description = "Verify sensitive JSON fields containing objects or arrays are completely redacted")
    @Story("Payload Redaction")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that sensitive JSON fields with nested objects (e.g. credentials) are completely replaced with [REDACTED].")
    public void testSensitiveObjectFieldCompleteRedaction() {
        String payload = """
                {
                  "username": "demo",
                  "password": "secret123",
                  "credentials": {
                    "value": "hidden-secret"
                  },
                  "accessToken": "token123"
                }
                """;

        String redacted = SensitiveDataRedactionFilter.redactJsonPayload(payload);

        assertThat(redacted)
                .contains("\"username\":\"demo\"")
                .contains("\"password\":\"[REDACTED]\"")
                .contains("\"credentials\":\"[REDACTED]\"")
                .contains("\"accessToken\":\"[REDACTED]\"")
                .doesNotContain("secret123")
                .doesNotContain("hidden-secret")
                .doesNotContain("token123");
    }

    @Test(description = "Verify sensitive JSON fields with null values are safely handled")
    @Story("Payload Redaction")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies that sensitive fields with null values are redacted without throwing exceptions.")
    public void testNullSensitiveFields() {
        String payload = """
                {
                  "username": "demo",
                  "password": null,
                  "clientSecret": null
                }
                """;

        String redacted = SensitiveDataRedactionFilter.redactJsonPayload(payload);

        assertThat(redacted)
                .contains("\"username\":\"demo\"")
                .contains("\"password\":\"[REDACTED]\"")
                .contains("\"clientSecret\":\"[REDACTED]\"");
    }

    @Test(description = "Verify sensitive field detection handles variations with underscores, hyphens, and mixed case")
    @Story("Field Detection")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies sensitive field key matching for naming variations (camelCase, snake_case, kebab-case).")
    public void testSensitiveFieldVariations() {
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("password")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("PASSWORD")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("passwd")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("PASSWD")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("pass_wd")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("clientSecret")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("client_secret")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("client-secret")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("CLIENT_SECRET")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("accessToken")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("access_token")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("access-token")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("ACCESS_TOKEN")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("privateKey")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("private_key")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("private-key")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("PRIVATE_KEY")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("apiKey")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("api_key")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("API_KEY")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("refreshToken")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("refresh_token")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("credentials")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("session_id")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("sessionId")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("jwt")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("JWT")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("idToken")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("id_token")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("id-token")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("authorization")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("AUTHORIZATION")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("cookie")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("COOKIE")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("setCookie")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("set_cookie")).isTrue();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("set-cookie")).isTrue();

        // Non-sensitive harmless fields must not be redacted
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("username")).isFalse();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("email")).isFalse();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("title")).isFalse();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("price")).isFalse();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("author")).isFalse();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("authority")).isFalse();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("description")).isFalse();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField(null)).isFalse();
    }

    @Test(description = "Verify JSON payload redaction handles supported naming variants in nested objects and arrays")
    @Story("Payload Redaction")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies JSON redaction for client_secret, access_token, private_key inside nested objects and arrays.")
    public void testSupportedNamingVariantsInJson() {
        String payload = """
                {
                  "security": {
                    "client_secret": "cs_live_12345",
                    "access_token": "at_live_67890",
                    "private_key": "-----BEGIN PRIVATE KEY-----secret-----END PRIVATE KEY-----"
                  },
                  "history": [
                    { "refresh_token": "rt_live_abc", "session_id": "sess_live_xyz" }
                  ]
                }
                """;

        String redacted = SensitiveDataRedactionFilter.redactJsonPayload(payload);

        assertThat(redacted)
                .doesNotContain("cs_live_12345")
                .doesNotContain("at_live_67890")
                .doesNotContain("BEGIN PRIVATE KEY")
                .doesNotContain("rt_live_abc")
                .doesNotContain("sess_live_xyz")
                .contains("\"client_secret\":\"[REDACTED]\"")
                .contains("\"access_token\":\"[REDACTED]\"")
                .contains("\"private_key\":\"[REDACTED]\"")
                .contains("\"refresh_token\":\"[REDACTED]\"")
                .contains("\"session_id\":\"[REDACTED]\"");
    }

    @Test(description = "Verify non-JSON and unparseable payloads do not leak raw text")
    @Story("Payload Redaction")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Ensures that unparseable non-JSON payloads are masked entirely rather than leaking verbatim.")
    public void testNonJsonPayloadHandling() {
        String nonJson = "This is a raw text payload containing secret=12345";
        String result = SensitiveDataRedactionFilter.redactJsonPayload(nonJson);

        assertThat(result)
                .isEqualTo("[Non-JSON or Unparseable Payload]")
                .doesNotContain("secret=12345");
    }

    @Test(description = "Verify malformed JSON containing sensitive fields is masked without leaking secrets")
    @Story("Payload Redaction")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Ensures that malformed JSON strings containing secrets are completely masked.")
    public void testMalformedJsonWithSensitiveDataIsMasked() {
        String malformedJson = "{ \"password\": \"leak_pass_123\", \"client_secret\": \"leak_cs_456\", \"unclosed\": ";
        String redacted = SensitiveDataRedactionFilter.redactJsonPayload(malformedJson);

        assertThat(redacted)
                .isEqualTo("[Non-JSON or Unparseable Payload]")
                .doesNotContain("leak_pass_123")
                .doesNotContain("leak_cs_456");

        String sanitizedText = SensitiveDataRedactionFilter.sanitizeText(malformedJson);
        assertThat(sanitizedText)
                .doesNotContain("leak_pass_123")
                .doesNotContain("leak_cs_456");
    }

    @Test(description = "Verify general text sanitization redacts bearer tokens, query parameters and credentials")
    @Story("Text Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies regex-based text sanitization across Bearer tokens, query string parameters and key-values.")
    public void testGeneralTextSanitization() {
        String errorMsg = "Failed connecting to https://admin:secretPass@dummyjson.com/users?apiKey=querySecret123 with Bearer tokenXYZ987";
        String sanitized = SensitiveDataRedactionFilter.sanitizeText(errorMsg);

        assertThat(sanitized)
                .doesNotContain("secretPass")
                .doesNotContain("querySecret123")
                .doesNotContain("tokenXYZ987")
                .contains("https://[REDACTED]:[REDACTED]@dummyjson.com/users?apiKey=[REDACTED]")
                .contains("Bearer [REDACTED]");
    }

    @Test(description = "Verify sensitive query parameter values containing %26 are completely redacted")
    @Story("Query Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that encoded ampersands (%26) within sensitive parameter values are not treated as boundaries and the entire value is redacted.")
    public void testSensitiveValuesContainingEncodedAmpersand() {
        String fullSecret = "my%20secret%21%26other%3D123";
        String url = "https://dummyjson.com/auth?client_secret=" + fullSecret + "&user=john";
        String sanitized = SensitiveDataRedactionFilter.sanitizeText(url);

        assertThat(sanitized)
                .as("Entire sensitive value containing %26 must be absent from sanitized output")
                .doesNotContain(fullSecret)
                .as("Trailing segment after %26 must not remain visible")
                .doesNotContain("%26other%3D123")
                .doesNotContain("my%20secret%21")
                .contains("client_secret=[REDACTED]")
                .contains("&user=john");

        // Also test standalone without trailing parameters
        String standalone = "?apiKey=key%26with%26ampersands";
        String sanitizedStandalone = SensitiveDataRedactionFilter.sanitizeText(standalone);
        assertThat(sanitizedStandalone)
                .doesNotContain("key%26with%26ampersands")
                .isEqualTo("?apiKey=[REDACTED]");
    }

    @Test(description = "Verify sensitive query parameter values containing %3D are completely redacted")
    @Story("Query Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that encoded equals signs (%3D) inside sensitive parameter values are redacted without premature truncation.")
    public void testSensitiveValuesContainingEncodedEquals() {
        String fullSecret = "token%3Dwith%3Dencoded%3Dequals";
        String url = "https://dummyjson.com/oauth?apiKey=" + fullSecret + "&clientId=app123";
        String sanitized = SensitiveDataRedactionFilter.sanitizeText(url);

        assertThat(sanitized)
                .as("Entire sensitive value containing %3D must be absent from sanitized output")
                .doesNotContain(fullSecret)
                .doesNotContain("token%3D")
                .doesNotContain("%3Dequals")
                .contains("apiKey=[REDACTED]")
                .contains("&clientId=app123");
    }

    @Test(description = "Verify multiple sensitive parameters separated by literal & are all redacted")
    @Story("Query Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that multiple sensitive query parameters separated by literal & are all independently redacted.")
    public void testMultipleSensitiveParametersSeparatedByLiteralAmpersand() {
        String secret1 = "sec_alpha_123";
        String secret2 = "sec_beta_456";
        String secret3 = "sec_gamma_789";
        String url = "/auth/token?client_secret=" + secret1 + "&access_token=" + secret2 + "&password=" + secret3;
        String sanitized = SensitiveDataRedactionFilter.sanitizeText(url);

        assertThat(sanitized)
                .as("All individual secrets separated by literal & must be absent")
                .doesNotContain(secret1)
                .doesNotContain(secret2)
                .doesNotContain(secret3)
                .contains("client_secret=[REDACTED]")
                .contains("access_token=[REDACTED]")
                .contains("password=[REDACTED]");
    }

    @Test(description = "Verify encoded nested query strings are correctly identified and redacted")
    @Story("Query Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that URL-encoded nested query parameters with %3D and %26 are correctly identified, parsed, and redacted.")
    public void testEncodedNestedQueryStrings() {
        String nestedSecret = "nestedLiveSecret777";
        String url = "https://auth.example.com/oauth?redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback%3Fclient_secret%3D"
                + nestedSecret + "%26state%3Dxyz123";
        String sanitized = SensitiveDataRedactionFilter.sanitizeText(url);

        assertThat(sanitized)
                .as("Nested encoded secret must be absent from sanitized output")
                .doesNotContain(nestedSecret)
                .contains("client_secret%3D[REDACTED]")
                .contains("%26state%3Dxyz123");

        // Also verify nested encoded secret containing %26 is conservatively redacted
        String nestedSecretWithAmp = "nested%26token%26val";
        String nestedUrl = "callback=%3Faccess_token%3D" + nestedSecretWithAmp + "%26scope%3Dread";
        String sanitizedNested = SensitiveDataRedactionFilter.sanitizeText(nestedUrl);
        assertThat(sanitizedNested)
                .doesNotContain(nestedSecretWithAmp)
                .contains("access_token%3D[REDACTED]")
                .contains("%26scope%3Dread");
    }

    @Test(description = "Verify mixed encoded and unencoded sensitive values are properly redacted")
    @Story("Query Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies redaction when a request contains both unencoded query parameters with encoded characters and an encoded nested query string.")
    public void testMixedEncodedAndUnencodedValues() {
        String rawSecret = "rawSecret%26Value%3D999";
        String nestedSecret = "nestedSecret888";
        String url = "https://api.example.com/v1/dispatch?apiKey=" + rawSecret
                + "&target_url=https%3A%2F%2Fauth.example.com%3Fclient_secret%3D" + nestedSecret + "%26flag%3Dtrue";
        String sanitized = SensitiveDataRedactionFilter.sanitizeText(url);

        assertThat(sanitized)
                .as("Unencoded query param secret with encoded chars must be fully redacted")
                .doesNotContain(rawSecret)
                .as("Encoded nested query secret must be fully redacted")
                .doesNotContain(nestedSecret)
                .contains("apiKey=[REDACTED]")
                .contains("client_secret%3D[REDACTED]")
                .contains("%26flag%3Dtrue");
    }

    @Test(description = "Verify empty sensitive parameter values are safely redacted without errors")
    @Story("Query Sanitization")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies that empty sensitive parameters (?client_secret= or key%3D) are safely handled and masked as [REDACTED].")
    public void testEmptySensitiveValuesHandling() {
        String url = "https://dummyjson.com/auth?client_secret=&username=admin&password=&access_token=";
        String sanitized = SensitiveDataRedactionFilter.sanitizeText(url);

        assertThat(sanitized)
                .contains("client_secret=[REDACTED]")
                .contains("password=[REDACTED]")
                .contains("access_token=[REDACTED]")
                .contains("&username=admin");

        String single = "?api-key=";
        assertThat(SensitiveDataRedactionFilter.sanitizeText(single))
                .isEqualTo("?api-key=[REDACTED]");

        String nestedEmpty = "redirect=%3Fclient_secret%3D%26state%3D123";
        assertThat(SensitiveDataRedactionFilter.sanitizeText(nestedEmpty))
                .contains("client_secret%3D[REDACTED]")
                .contains("%26state%3D123");
    }

    @Test(description = "Verify Bearer tokens with Base64 padding and unusual token characters are redacted")
    @Story("Text Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that Bearer tokens with padding (==), slashes, pluses, tildes, and tabs are fully masked.")
    public void testBearerTokenVariations() {
        String textWithPaddedBearer = "Authorization: Bearer   eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc+def/xyz_123~test==";
        String sanitized = SensitiveDataRedactionFilter.sanitizeText(textWithPaddedBearer);

        assertThat(sanitized)
                .doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
                .doesNotContain("abc+def/xyz_123~test==")
                .contains("Authorization: Bearer [REDACTED]");
    }

    @Test(description = "Verify exception messages containing nested JSON or URL credentials are fully sanitized")
    @Story("Text Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Ensures exception diagnostic text containing embedded URL credentials and key-value JSON pairs is sanitized.")
    public void testExceptionMessageWithNestedJsonAndUrlCredentials() {
        String exceptionMsg = "java.lang.AssertionError: Request to https://svc_user:superSecretPassword99@api.internal/v1/auth failed. Response payload: {\"error\":\"invalid_request\",\"client_secret\":\"live_secret_777\",\"private_key\":\"pk_live_888\"}";
        String sanitized = SensitiveDataRedactionFilter.sanitizeText(exceptionMsg);

        assertThat(sanitized)
                .doesNotContain("superSecretPassword99")
                .doesNotContain("live_secret_777")
                .doesNotContain("pk_live_888")
                .contains("https://[REDACTED]:[REDACTED]@api.internal/v1/auth")
                .contains("\"client_secret\":\"[REDACTED]\"")
                .contains("\"private_key\":\"[REDACTED]\"");
    }

    @Test(description = "Verify kebab-case text sanitization redacts key-values and URL query parameters")
    @Story("Text Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies sanitizeText() correctly masks client-secret, private-key, access-token, refresh-token, and api-key in both key-values and URL query parameters.")
    public void testSanitizeTextKebabCaseKeyValuesAndQueryParams() {
        // 1. Key-value text format
        String kvText = "Config: client-secret: cs-val-901, private-key: pk-val-902, access-token: at-val-903, refresh-token: rt-val-904, api-key: ak-val-905";
        String sanitizedKv = SensitiveDataRedactionFilter.sanitizeText(kvText);

        assertThat(sanitizedKv)
                .as("Raw kebab-case secrets must be stripped from key-value text")
                .doesNotContain("cs-val-901")
                .doesNotContain("pk-val-902")
                .doesNotContain("at-val-903")
                .doesNotContain("rt-val-904")
                .doesNotContain("ak-val-905")
                .contains("client-secret: [REDACTED]")
                .contains("private-key: [REDACTED]")
                .contains("access-token: [REDACTED]")
                .contains("refresh-token: [REDACTED]")
                .contains("api-key: [REDACTED]");

        // 2. URL query parameters format
        String queryText = "HTTP GET /oauth/token?client-secret=cs-query-101&private-key=pk-query-102&access-token=at-query-103&refresh-token=rt-query-104&api-key=ak-query-105";
        String sanitizedQuery = SensitiveDataRedactionFilter.sanitizeText(queryText);

        assertThat(sanitizedQuery)
                .as("Raw kebab-case secrets must be stripped from URL query parameters")
                .doesNotContain("cs-query-101")
                .doesNotContain("pk-query-102")
                .doesNotContain("at-query-103")
                .doesNotContain("rt-query-104")
                .doesNotContain("ak-query-105")
                .contains("client-secret=[REDACTED]")
                .contains("private-key=[REDACTED]")
                .contains("access-token=[REDACTED]")
                .contains("refresh-token=[REDACTED]")
                .contains("api-key=[REDACTED]");
    }

    @Test(description = "Verify snake_case text sanitization redacts key-values and URL query parameters")
    @Story("Text Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies sanitizeText() correctly masks client_secret, private_key, access_token, refresh_token, and api_key in both key-values and URL query parameters.")
    public void testSanitizeTextSnakeCaseKeyValuesAndQueryParams() {
        // 1. Key-value text format
        String kvText = "Config: client_secret: cs-val-801, private_key: pk-val-802, access_token: at-val-803, refresh_token: rt-val-804, api_key: ak-val-805";
        String sanitizedKv = SensitiveDataRedactionFilter.sanitizeText(kvText);

        assertThat(sanitizedKv)
                .as("Raw snake_case secrets must be stripped from key-value text")
                .doesNotContain("cs-val-801")
                .doesNotContain("pk-val-802")
                .doesNotContain("at-val-803")
                .doesNotContain("rt-val-804")
                .doesNotContain("ak-val-805")
                .contains("client_secret: [REDACTED]")
                .contains("private_key: [REDACTED]")
                .contains("access_token: [REDACTED]")
                .contains("refresh_token: [REDACTED]")
                .contains("api_key: [REDACTED]");

        // 2. URL query parameters format
        String queryText = "HTTP GET /oauth/token?client_secret=cs-query-201&private_key=pk-query-202&access_token=at-query-203&refresh_token=rt-query-204&api_key=ak-query-205";
        String sanitizedQuery = SensitiveDataRedactionFilter.sanitizeText(queryText);

        assertThat(sanitizedQuery)
                .as("Raw snake_case secrets must be stripped from URL query parameters")
                .doesNotContain("cs-query-201")
                .doesNotContain("pk-query-202")
                .doesNotContain("at-query-203")
                .doesNotContain("rt-query-204")
                .doesNotContain("ak-query-205")
                .contains("client_secret=[REDACTED]")
                .contains("private_key=[REDACTED]")
                .contains("access_token=[REDACTED]")
                .contains("refresh_token=[REDACTED]")
                .contains("api_key=[REDACTED]");
    }

    @Test(description = "Verify camelCase text sanitization redacts key-values and URL query parameters")
    @Story("Text Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies sanitizeText() correctly masks clientSecret, privateKey, accessToken, refreshToken, and apiKey in both key-values and URL query parameters.")
    public void testSanitizeTextCamelCaseKeyValuesAndQueryParams() {
        // 1. Key-value text format
        String kvText = "Config: clientSecret: cs-val-701, privateKey: pk-val-702, accessToken: at-val-703, refreshToken: rt-val-704, apiKey: ak-val-705";
        String sanitizedKv = SensitiveDataRedactionFilter.sanitizeText(kvText);

        assertThat(sanitizedKv)
                .as("Raw camelCase secrets must be stripped from key-value text")
                .doesNotContain("cs-val-701")
                .doesNotContain("pk-val-702")
                .doesNotContain("at-val-703")
                .doesNotContain("rt-val-704")
                .doesNotContain("ak-val-705")
                .contains("clientSecret: [REDACTED]")
                .contains("privateKey: [REDACTED]")
                .contains("accessToken: [REDACTED]")
                .contains("refreshToken: [REDACTED]")
                .contains("apiKey: [REDACTED]");

        // 2. URL query parameters format
        String queryText = "HTTP GET /oauth/token?clientSecret=cs-query-301&privateKey=pk-query-302&accessToken=at-query-303&refreshToken=rt-query-304&apiKey=ak-query-305";
        String sanitizedQuery = SensitiveDataRedactionFilter.sanitizeText(queryText);

        assertThat(sanitizedQuery)
                .as("Raw camelCase secrets must be stripped from URL query parameters")
                .doesNotContain("cs-query-301")
                .doesNotContain("pk-query-302")
                .doesNotContain("at-query-303")
                .doesNotContain("rt-query-304")
                .doesNotContain("ak-query-305")
                .contains("clientSecret=[REDACTED]")
                .contains("privateKey=[REDACTED]")
                .contains("accessToken=[REDACTED]")
                .contains("refreshToken=[REDACTED]")
                .contains("apiKey=[REDACTED]");
    }

    @Test(description = "Verify root-level sensitive fields and root-level string tokens are redacted")
    @Story("Payload Redaction")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that root-level authorization, jwt, cookie, and root textual bearer tokens are properly masked.")
    public void testRootLevelSensitiveFieldsAndTokens() {
        String rootObjectPayload = """
                {
                  "authorization": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.token.root",
                  "jwt": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig",
                  "passwd": "rootPassword123",
                  "cookie": "session_id=abcdef123456",
                  "idToken": "id_token_root_value"
                }
                """;

        String redacted = SensitiveDataRedactionFilter.redactJsonPayload(rootObjectPayload);

        assertThat(redacted)
                .doesNotContain("token.root")
                .doesNotContain("payload.sig")
                .doesNotContain("rootPassword123")
                .doesNotContain("abcdef123456")
                .doesNotContain("id_token_root_value")
                .contains("\"authorization\":\"[REDACTED]\"")
                .contains("\"jwt\":\"[REDACTED]\"")
                .contains("\"passwd\":\"[REDACTED]\"")
                .contains("\"cookie\":\"[REDACTED]\"")
                .contains("\"idToken\":\"[REDACTED]\"");

        // Root level textual string containing bearer token
        String rootTextPayload = "\"Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.root.token\"";
        String redactedText = SensitiveDataRedactionFilter.redactJsonPayload(rootTextPayload);
        assertThat(redactedText)
                .doesNotContain("root.token")
                .contains("Bearer [REDACTED]");
    }

    @Test(description = "Verify Bearer tokens inside neutral JSON string values and arrays are redacted")
    @Story("Payload Redaction")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that Bearer tokens embedded within arbitrary textual JSON properties or string arrays are sanitized.")
    public void testBearerTokenInsideNeutralJsonStringValues() {
        String payload = """
                {
                  "description": "Please pass Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secret123.sig to access",
                  "hints": [
                    "Normal hint text",
                    "Header should be Bearer token_in_array_456"
                  ]
                }
                """;

        String redacted = SensitiveDataRedactionFilter.redactJsonPayload(payload);

        assertThat(redacted)
                .doesNotContain("secret123.sig")
                .doesNotContain("token_in_array_456")
                .contains("Please pass Bearer [REDACTED] to access")
                .contains("Normal hint text")
                .contains("Header should be Bearer [REDACTED]");
    }

    @Test(description = "Verify newly added sensitive fields (passwd, jwt, idToken, cookie, setCookie) in nested structures")
    @Story("Payload Redaction")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that passwd, jwt, id_token, cookie, set_cookie are completely redacted across nested objects and arrays.")
    public void testNewlyAddedSensitiveFieldVariantsInJson() {
        String payload = """
                {
                  "authConfig": {
                    "passwd": "secret_passwd_val",
                    "jwt": "jwt_val_789",
                    "id_token": "id_token_val_012"
                  },
                  "session": [
                    { "cookie": "sid_secret_cookie_345", "set_cookie": "sid_secret_setcookie_678" }
                  ]
                }
                """;

        String redacted = SensitiveDataRedactionFilter.redactJsonPayload(payload);

        assertThat(redacted)
                .doesNotContain("secret_passwd_val")
                .doesNotContain("jwt_val_789")
                .doesNotContain("id_token_val_012")
                .doesNotContain("sid_secret_cookie_345")
                .doesNotContain("sid_secret_setcookie_678")
                .contains("\"passwd\":\"[REDACTED]\"")
                .contains("\"jwt\":\"[REDACTED]\"")
                .contains("\"id_token\":\"[REDACTED]\"")
                .contains("\"cookie\":\"[REDACTED]\"")
                .contains("\"set_cookie\":\"[REDACTED]\"");
    }

    @Test(description = "Verify harmless non-sensitive fields are preserved without accidental redaction")
    @Story("Payload Integrity")
    @Severity(SeverityLevel.NORMAL)
    @Description("Validates that non-sensitive fields like author, authority, username, email, title, price are completely unchanged.")
    public void testNormalFieldsRemainUnchangedInJson() {
        String payload = """
                {
                  "id": 101,
                  "username": "john_doe",
                  "email": "john.doe@example.com",
                  "title": "Clean Architecture",
                  "price": 49.99,
                  "category": "books",
                  "author": "Robert C. Martin",
                  "authority": "ROLE_USER"
                }
                """;

        String redacted = SensitiveDataRedactionFilter.redactJsonPayload(payload);

        assertThat(redacted)
                .contains("\"id\":101")
                .contains("\"username\":\"john_doe\"")
                .contains("\"email\":\"john.doe@example.com\"")
                .contains("\"title\":\"Clean Architecture\"")
                .contains("\"price\":49.99")
                .contains("\"category\":\"books\"")
                .contains("\"author\":\"Robert C. Martin\"")
                .contains("\"authority\":\"ROLE_USER\"")
                .doesNotContain("[REDACTED]");
    }

    @Test(description = "Verify empty, blank, and null payloads are safely handled")
    @Story("Edge Cases")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies that empty, blank, and null inputs return empty strings without throwing exceptions.")
    public void testEmptyAndNullPayloadHandling() {
        assertThat(SensitiveDataRedactionFilter.redactJsonPayload(null)).isEmpty();
        assertThat(SensitiveDataRedactionFilter.redactJsonPayload("")).isEmpty();
        assertThat(SensitiveDataRedactionFilter.redactJsonPayload("   ")).isEmpty();

        assertThat(SensitiveDataRedactionFilter.sanitizeText(null)).isEmpty();
        assertThat(SensitiveDataRedactionFilter.sanitizeText("")).isEmpty();
        assertThat(SensitiveDataRedactionFilter.sanitizeText("   ")).isEmpty();
    }

    @Test(description = "Verify sanitizeText redacts newly supported sensitive fields in key-values and query parameters")
    @Story("Text Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies text sanitization for passwd, jwt, id-token, cookie, set-cookie, and authorization in text logs.")
    public void testSanitizeTextWithNewSensitiveFieldNames() {
        String text = "Diagnostics: passwd=pass999, jwt=jwtToken888, id-token=idTok777, authorization=authVal666, cookie=cook555, set-cookie=setCook444";
        String sanitized = SensitiveDataRedactionFilter.sanitizeText(text);

        assertThat(sanitized)
                .doesNotContain("pass999")
                .doesNotContain("jwtToken888")
                .doesNotContain("idTok777")
                .doesNotContain("authVal666")
                .doesNotContain("cook555")
                .doesNotContain("setCook444")
                .contains("passwd=[REDACTED]")
                .contains("jwt=[REDACTED]")
                .contains("id-token=[REDACTED]")
                .contains("authorization=[REDACTED]")
                .contains("cookie=[REDACTED]")
                .contains("set-cookie=[REDACTED]");
    }

    @Test(description = "Verify malformed truncated JSON is sanitized without leaking secrets")
    @Story("Text Sanitization")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that incomplete, truncated JSON with open braces containing secrets is safely sanitized.")
    public void testSanitizeTextMalformedTruncatedJson() {
        String truncatedJson = "{\"authorization\": \"Bearer leak_tok_999\", \"passwd\": \"leak_pass_888\", \"unclosed\": ";
        String sanitized = SensitiveDataRedactionFilter.sanitizeText(truncatedJson);

        assertThat(sanitized)
                .doesNotContain("leak_tok_999")
                .doesNotContain("leak_pass_888")
                .contains("Bearer [REDACTED]")
                .contains("\"passwd\": \"[REDACTED]\"");
    }

    @Test(description = "Verify 50 KB response with declared Content-Length is excluded from logs and remains readable downstream")
    @Story("Body Logging Limits")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that a 50 KB JSON response with Content-Length is excluded prior to body parsing and remains readable downstream.")
    public void test50KbResponseWithContentLengthExcludedAndReadableDownstream() throws IOException {
        String largePadding = "x".repeat(50_000);
        String jsonBody = "{\"status\":\"success\",\"passwd\":\"secret_large_passwd_123\",\"padding\":\"" + largePadding + "\"}";

        try (LocalHttpTestServer server = LocalHttpTestServer.createAndStart()) {
            server.registerJsonResponse("/api/large-response-cl", 200, jsonBody);

            SensitiveDataRedactionFilter filter = new SensitiveDataRedactionFilter(true);
            Response response = RestAssured.given()
                    .filter(filter)
                    .get(server.getBaseUrl() + "/api/large-response-cl");

            // 1. Verify downstream readability is preserved
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.jsonPath().getString("status")).isEqualTo("success");
            assertThat(response.asString().length()).isGreaterThan(50_000);

            // 2. Verify log output captured during actual filter execution excludes oversized body
            String sanitizedLog = filter.getLastSanitizedResponse();
            assertThat(sanitizedLog)
                    .contains("Payload exceeds logging limit of 10000 bytes (Content-Length:")
                    .doesNotContain("secret_large_passwd_123")
                    .doesNotContain(largePadding);
        }
    }

    @Test(description = "Verify large chunked response without Content-Length is safely excluded and remains readable")
    @Story("Body Logging Limits")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that a 50 KB response without Content-Length (chunked) is detected via actual byte size and excluded.")
    public void testLargeChunkedResponseWithoutContentLengthExcludedAndReadableDownstream() throws IOException {
        String largePadding = "y".repeat(50_000);
        String jsonBody = "{\"status\":\"chunked_ok\",\"jwt\":\"jwt_secret_token_456\",\"padding\":\"" + largePadding + "\"}";
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        try (LocalHttpTestServer server = LocalHttpTestServer.createAndStart()) {
            server.registerHandler("/api/large-chunked", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                // 0 indicates chunked transfer encoding (no Content-Length header)
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bodyBytes);
                }
            });

            SensitiveDataRedactionFilter filter = new SensitiveDataRedactionFilter(true);
            Response response = RestAssured.given()
                    .filter(filter)
                    .get(server.getBaseUrl() + "/api/large-chunked");

            // 1. Downstream response readability
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.jsonPath().getString("status")).isEqualTo("chunked_ok");
            assertThat(response.asString().length()).isGreaterThan(50_000);

            // 2. Filter log output safely excludes oversized body based on actual byte size
            String sanitizedLog = filter.getLastSanitizedResponse();
            assertThat(sanitizedLog)
                    .contains("Payload exceeds logging limit of 10000 bytes (Actual size:")
                    .doesNotContain("jwt_secret_token_456")
                    .doesNotContain(largePadding);
        }
    }

    @Test(description = "Verify inaccurate small Content-Length header with 50 KB payload is safely caught by actual byte size inspection")
    @Story("Body Logging Limits")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies using a raw HTTP server that when Content-Length declares 10 bytes but actual chunked body is 50 KB, actual byte inspection excludes the body.")
    public void testInaccurateSmallContentLengthWithLargePayloadHandledSafely() throws Exception {
        String largePadding = "z".repeat(50_000);
        String jsonBody = "{\"status\":\"raw_inaccurate\",\"apiKey\":\"key_secret_raw_789\",\"padding\":\"" + largePadding + "\"}";
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        try (ServerSocket rawServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = rawServer.getLocalPort();

            CompletableFuture<Void> serverFuture = CompletableFuture.runAsync(() -> {
                try (Socket client = rawServer.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // consume request headers
                    }
                    OutputStream out = client.getOutputStream();
                    String chunkHex = Integer.toHexString(bodyBytes.length);
                    String responseHeaders = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: 10\r\n"
                            + "Transfer-Encoding: chunked\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
                    out.write((chunkHex + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(bodyBytes);
                    out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {
                }
            });

            SensitiveDataRedactionFilter filter = new SensitiveDataRedactionFilter(true);
            Response response = RestAssured.given()
                    .filter(filter)
                    .get("http://127.0.0.1:" + port + "/api/raw-inaccurate");

            serverFuture.join();

            // 1. Verify inaccurate declared Content-Length header was indeed present as 10
            assertThat(response.getHeader("Content-Length")).isEqualTo("10");

            // 2. Verify actual payload received by HTTP client is over 50 KB and readable
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.asByteArray().length).isGreaterThan(50_000);
            assertThat(response.jsonPath().getString("status")).isEqualTo("raw_inaccurate");

            // 3. Verify actual byte inspection caught the discrepancy and safely excluded the body
            String sanitizedLog = filter.getLastSanitizedResponse();
            assertThat(sanitizedLog)
                    .contains("Payload exceeds logging limit of 10000 bytes (Actual size:")
                    .doesNotContain("key_secret_raw_789")
                    .doesNotContain(largePadding);
        }
    }

    @Test(description = "Verify large request body exceeding limit is excluded from logs without leaking secrets")
    @Story("Body Logging Limits")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that a request body exceeding 10,000 characters is excluded from log output prior to JSON processing.")
    public void testLargeRequestBodyExceedingLimitExcludedFromLogs() throws IOException {
        String largePadding = "a".repeat(30_000);
        String requestJson = "{\"username\":\"admin\",\"password\":\"req_secret_pass_321\",\"data\":\"" + largePadding + "\"}";

        try (LocalHttpTestServer server = LocalHttpTestServer.createAndStart()) {
            SensitiveDataRedactionFilter filter = new SensitiveDataRedactionFilter(true);
            RestAssured.given()
                    .filter(filter)
                    .contentType("application/json")
                    .body(requestJson)
                    .post(server.getBaseUrl() + "/api/large-req");

            String sanitizedRequestLog = filter.getLastSanitizedRequest();
            assertThat(sanitizedRequestLog)
                    .contains("Request body exceeds logging limit of 10000 characters")
                    .contains("Body excluded from logs to prevent log flooding.")
                    .doesNotContain("req_secret_pass_321")
                    .doesNotContain(largePadding);
        }
    }

    @Test(description = "Verify response body truncation enforces exact maximum length including marker without leaking secrets")
    @Story("Body Logging Limits")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that response body truncation strictly bounds the complete logged body to MAX_LOG_BODY_LENGTH characters including the marker, and ensures prior redaction prevents secret leakage.")
    public void testActualBodyTruncationEnforcesExactMaxLimitWithoutLeakingSecrets() throws IOException {
        // Raw byte size is within 10,000 bytes (< 10,000), but redaction expansion pushes it over 10,000 characters
        StringBuilder sb = new StringBuilder("{\"status\":\"ok\",\"passwd\":\"secret_pass_inside_expansion\",\"items\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"token\":\"x\"}");
        }
        sb.append("],\"padding\":\"").append("d".repeat(9_500)).append("\"}");
        String expansionJson = sb.toString();
        byte[] expansionBytes = expansionJson.getBytes(StandardCharsets.UTF_8);

        assertThat(expansionBytes.length)
                .as("Raw byte size must be within 10,000 bytes so byte exclusion is not triggered")
                .isLessThanOrEqualTo(SensitiveDataRedactionFilter.MAX_LOG_BODY_LENGTH);

        try (LocalHttpTestServer server = LocalHttpTestServer.createAndStart()) {
            server.registerJsonResponse("/api/truncation-path", 200, expansionJson);

            SensitiveDataRedactionFilter filter = new SensitiveDataRedactionFilter(true);
            Response response = RestAssured.given()
                    .filter(filter)
                    .get(server.getBaseUrl() + "/api/truncation-path");

            assertThat(response.getStatusCode()).isEqualTo(200);

            String sanitizedLog = filter.getLastSanitizedResponse();
            assertThat(sanitizedLog)
                    .contains(SensitiveDataRedactionFilter.TRUNCATION_MARKER)
                    .doesNotContain("secret_pass_inside_expansion");

            // Extract the body portion after '--- Body ---\n'
            int bodyMarkerIdx = sanitizedLog.indexOf("--- Body ---\n");
            assertThat(bodyMarkerIdx).isGreaterThanOrEqualTo(0);
            String bodyOutput = sanitizedLog.substring(bodyMarkerIdx + "--- Body ---\n".length()).trim();

            assertThat(bodyOutput.length())
                    .as("The complete logged body output including the marker must be exactly MAX_LOG_BODY_LENGTH")
                    .isEqualTo(SensitiveDataRedactionFilter.MAX_LOG_BODY_LENGTH);
            assertThat(bodyOutput)
                    .endsWith(SensitiveDataRedactionFilter.TRUNCATION_MARKER);
        }
    }

    @Test(description = "Verify initially in-limit request body expanding past limit after redaction is truncated without leaking secrets")
    @Story("Body Logging Limits")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that a request body whose original length is under 10,000 characters but expands past 10,000 characters upon redaction is strictly truncated with marker and leaks no secrets.")
    public void testInLimitRequestBodyExpandingPastLimitAfterRedactionTruncated() throws IOException {
        // Construct request payload of ~9,850 chars (< 10,000 chars) with 25 sensitive token items
        // Each {"token":"x"} (11 chars) expands to {"token":"[REDACTED]"} (20 chars, +9 chars each)
        // 9,850 + (25 * 9) = 10,075 characters (> 10,000 characters)
        StringBuilder sb = new StringBuilder("{\"password\":\"req_secret_expand_999\",\"items\":[");
        for (int i = 0; i < 25; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"token\":\"x\"}");
        }
        sb.append("],\"padding\":\"").append("e".repeat(9_500)).append("\"}");
        String requestJson = sb.toString();

        assertThat(requestJson.length())
                .as("Initial request body must be under MAX_LOG_BODY_LENGTH characters")
                .isLessThanOrEqualTo(SensitiveDataRedactionFilter.MAX_LOG_BODY_LENGTH);

        try (LocalHttpTestServer server = LocalHttpTestServer.createAndStart()) {
            SensitiveDataRedactionFilter filter = new SensitiveDataRedactionFilter(true);
            RestAssured.given()
                    .filter(filter)
                    .contentType("application/json")
                    .body(requestJson)
                    .post(server.getBaseUrl() + "/api/req-expansion");

            String sanitizedRequestLog = filter.getLastSanitizedRequest();
            assertThat(sanitizedRequestLog)
                    .contains(SensitiveDataRedactionFilter.TRUNCATION_MARKER)
                    .doesNotContain("req_secret_expand_999");

            // Extract the body portion after '--- Body ---\n'
            int bodyMarkerIdx = sanitizedRequestLog.indexOf("--- Body ---\n");
            assertThat(bodyMarkerIdx).isGreaterThanOrEqualTo(0);
            String bodyOutput = sanitizedRequestLog.substring(bodyMarkerIdx + "--- Body ---\n".length()).trim();

            assertThat(bodyOutput.length())
                    .as("The complete logged request body output including marker must be exactly MAX_LOG_BODY_LENGTH")
                    .isEqualTo(SensitiveDataRedactionFilter.MAX_LOG_BODY_LENGTH);
            assertThat(bodyOutput)
                    .endsWith(SensitiveDataRedactionFilter.TRUNCATION_MARKER);
        }
    }

    @Test(description = "Verify large non-JSON response is excluded without decoding to String and remains readable downstream")
    @Story("Body Logging Limits")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that a 50 KB text/html response is excluded from logs without decoding or converting to String, and remains readable downstream.")
    public void testLargeNonJsonResponseExcludedWithoutStringDecodingAndReadableDownstream() throws IOException {
        String htmlPadding = "<!-- comment " + "h".repeat(50_000) + " -->";
        String htmlBody = "<!DOCTYPE html><html><head><title>Large HTML Page</title></head><body>"
                + htmlPadding + "<h1>Maintenance</h1></body></html>";
        byte[] htmlBytes = htmlBody.getBytes(StandardCharsets.UTF_8);

        try (LocalHttpTestServer server = LocalHttpTestServer.createAndStart()) {
            server.registerHandler("/api/large-html", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, htmlBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(htmlBytes);
                }
            });

            SensitiveDataRedactionFilter filter = new SensitiveDataRedactionFilter(true);
            Response response = RestAssured.given()
                    .filter(filter)
                    .get(server.getBaseUrl() + "/api/large-html");

            // 1. Verify downstream readability is preserved
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.asByteArray().length).isGreaterThan(50_000);
            assertThat(response.asString()).contains("<title>Large HTML Page</title>");

            // 2. Verify filter log safely excludes non-JSON body without leaking HTML contents
            String sanitizedLog = filter.getLastSanitizedResponse();
            assertThat(sanitizedLog)
                    .contains("--- Body ---\n[Non-JSON or Binary Content Excluded]")
                    .doesNotContain("<title>Large HTML Page</title>")
                    .doesNotContain(htmlPadding);
        }
    }

    @Test(description = "Verify inaccurate zero Content-Length on non-JSON response falls back to actual byte length inspection")
    @Story("Body Logging Limits")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies using a raw HTTP server that when Content-Length is declared as 0 on a non-JSON chunked response, the filter falls back to cached byte length without decoding to String.")
    public void testInaccurateZeroContentLengthNonJsonResponseHandledSafely() throws Exception {
        byte[] nonJsonPayload = "Maintenance status: system upgrading\nDetailed diagnostics binary blob".getBytes(StandardCharsets.UTF_8);

        try (ServerSocket rawServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = rawServer.getLocalPort();

            CompletableFuture<Void> serverFuture = CompletableFuture.runAsync(() -> {
                try (Socket client = rawServer.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // consume request headers
                    }
                    OutputStream out = client.getOutputStream();
                    String chunkHex = Integer.toHexString(nonJsonPayload.length);
                    // Declared Content-Length: 0 alongside Transfer-Encoding: chunked with actual payload
                    String responseHeaders = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/plain\r\n"
                            + "Content-Length: 0\r\n"
                            + "Transfer-Encoding: chunked\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
                    out.write((chunkHex + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(nonJsonPayload);
                    out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {
                }
            });

            SensitiveDataRedactionFilter filter = new SensitiveDataRedactionFilter(true);
            Response response = RestAssured.given()
                    .filter(filter)
                    .get("http://127.0.0.1:" + port + "/api/zero-cl-non-json");

            serverFuture.join();

            // 1. Verify declared Content-Length was 0
            assertThat(response.getHeader("Content-Length")).isEqualTo("0");

            // 2. Verify actual payload was received by HTTP client and is readable downstream
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.asByteArray().length).isEqualTo(nonJsonPayload.length);
            assertThat(response.asString()).contains("Maintenance status: system upgrading");

            // 3. Verify filter fallback caught the non-empty body and excluded it without decoding
            String sanitizedLog = filter.getLastSanitizedResponse();
            assertThat(sanitizedLog)
                    .contains("--- Body ---\n[Non-JSON or Binary Content Excluded]")
                    .doesNotContain("Detailed diagnostics binary blob");
        }
    }

    @Test(description = "Verify File request body exceeding limit is excluded before reading file into memory")
    @Story("Body Logging Limits")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that a File request body exceeding 10,000 bytes is guarded using file.length() without reading into memory.")
    public void testLargeFileRequestBodyExcludedBeforeReadingFile() throws IOException {
        File tempFile = File.createTempFile("large-request-", ".json");
        tempFile.deleteOnExit();

        String fileContent = "{\"apiKey\":\"file_secret_key_888\",\"data\":\"" + "f".repeat(20_000) + "\"}";
        java.nio.file.Files.writeString(tempFile.toPath(), fileContent, StandardCharsets.UTF_8);

        try (LocalHttpTestServer server = LocalHttpTestServer.createAndStart()) {
            SensitiveDataRedactionFilter filter = new SensitiveDataRedactionFilter(true);
            RestAssured.given()
                    .filter(filter)
                    .contentType("application/json")
                    .body(tempFile)
                    .post(server.getBaseUrl() + "/api/test-file");

            String sanitizedRequestLog = filter.getLastSanitizedRequest();
            assertThat(sanitizedRequestLog)
                    .contains("Request body exceeds logging limit of 10000 bytes (File size:")
                    .contains("Body excluded from logs to prevent log flooding.")
                    .doesNotContain("file_secret_key_888")
                    .doesNotContain("f".repeat(100));
        }
    }

    @Test(description = "Verify byte[] request body exceeding limit is excluded before byte-to-string conversion")
    @Story("Body Logging Limits")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that a byte[] request body exceeding 10,000 bytes is excluded via byte array length guard.")
    public void testLargeByteArrayRequestBodyExcludedBeforeStringConversion() throws IOException {
        String rawPayload = "{\"token\":\"byte_secret_token_777\",\"data\":\"" + "b".repeat(25_000) + "\"}";
        byte[] payloadBytes = rawPayload.getBytes(StandardCharsets.UTF_8);

        try (LocalHttpTestServer server = LocalHttpTestServer.createAndStart()) {
            SensitiveDataRedactionFilter filter = new SensitiveDataRedactionFilter(true);
            RestAssured.given()
                    .filter(filter)
                    .contentType("application/json")
                    .body(payloadBytes)
                    .post(server.getBaseUrl() + "/api/test-bytes");

            String sanitizedRequestLog = filter.getLastSanitizedRequest();
            assertThat(sanitizedRequestLog)
                    .contains("Request body exceeds logging limit of 10000 bytes (Actual size:")
                    .contains("Body excluded from logs to prevent log flooding.")
                    .doesNotContain("byte_secret_token_777")
                    .doesNotContain("b".repeat(100));
        }
    }

    @Test(description = "Verify arbitrary object request body exceeding limit is safely excluded after conversion")
    @Story("Body Logging Limits")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that an arbitrary POJO/object whose string representation exceeds 10,000 characters is excluded without leaking secrets.")
    public void testArbitraryObjectRequestBodySizeCheckedAfterConversion() throws IOException {
        // Supported POJO / Map payload whose serialized representation exceeds 10,000 characters
        java.util.Map<String, Object> payloadMap = new java.util.HashMap<>();
        payloadMap.put("password", "pojo_secret_pass_666");
        payloadMap.put("data", "p".repeat(15_000));

        try (LocalHttpTestServer server = LocalHttpTestServer.createAndStart()) {
            SensitiveDataRedactionFilter filter = new SensitiveDataRedactionFilter(true);
            RestAssured.given()
                    .filter(filter)
                    .contentType("application/json")
                    .body(payloadMap)
                    .post(server.getBaseUrl() + "/api/test-map");

            String sanitizedRequestLog = filter.getLastSanitizedRequest();
            assertThat(sanitizedRequestLog)
                    .contains("Request body exceeds logging limit of 10000 characters (Actual length:")
                    .contains("Body excluded from logs to prevent log flooding.")
                    .doesNotContain("pojo_secret_pass_666")
                    .doesNotContain("p".repeat(100));
        }
    }
}
