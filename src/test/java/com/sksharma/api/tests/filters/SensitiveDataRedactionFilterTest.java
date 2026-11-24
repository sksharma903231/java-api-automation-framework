package com.sksharma.api.tests.filters;

import com.sksharma.api.filters.SensitiveDataRedactionFilter;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.FilterableRequestSpecification;
import org.testng.annotations.Test;

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

        // Non-sensitive fields
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("username")).isFalse();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("email")).isFalse();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("title")).isFalse();
        assertThat(SensitiveDataRedactionFilter.isSensitiveField("price")).isFalse();
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
}
