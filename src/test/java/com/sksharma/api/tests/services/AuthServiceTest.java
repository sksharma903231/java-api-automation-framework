package com.sksharma.api.tests.services;

import com.sksharma.api.models.auth.LoginRequest;
import com.sksharma.api.models.auth.LoginResponse;
import com.sksharma.api.models.users.User;
import com.sksharma.api.services.AuthService;
import com.sksharma.api.tests.utils.TestCredentials;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("E-Commerce API")
@Feature("Authentication")
@Test(groups = "integration")
public class AuthServiceTest {

    private AuthService authService;

    @BeforeClass(alwaysRun = true)
    public void setup() {
        authService = new AuthService();
    }

    @Test(description = "Verify successful login with valid public demo credentials returns access token")
    @Story("User Login")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Authenticates using valid demo credentials and validates the returned JWT access and refresh tokens.")
    public void testLoginWithValidDemoCredentials() {
        LoginRequest request = new LoginRequest(
                TestCredentials.getDemoUsername(),
                TestCredentials.getDemoPassword()
        );

        Response response = authService.login(request);

        assertThat(response.getStatusCode()).isEqualTo(200);

        LoginResponse loginResponse = response.as(LoginResponse.class);
        assertThat(loginResponse.getAccessToken()).isNotBlank();
        assertThat(loginResponse.getRefreshToken()).isNotBlank();
        assertThat(loginResponse.getUsername()).isEqualTo(TestCredentials.getDemoUsername());
        assertThat(loginResponse.getId()).isPositive();
    }

    @DataProvider(name = "invalidCredentialsData")
    public Object[][] invalidCredentialsData() {
        return com.sksharma.api.tests.utils.TestDataLoader.loadAsDataProvider("testdata/invalid-credentials.json");
    }

    @Test(dataProvider = "invalidCredentialsData", description = "Data-driven verification that invalid credentials return 400 Bad Request")
    @Story("Negative Authentication")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Ensures invalid or empty credential combinations are rejected with 400 Bad Request and descriptive errors.")
    public void testDataDrivenInvalidCredentials(com.fasterxml.jackson.databind.JsonNode data) {
        String username = data.get("username").asText();
        String password = data.get("password").asText();
        int expectedStatus = data.get("expectedStatus").asInt();
        String expectedMessage = data.get("expectedMessage").asText();

        LoginRequest request = new LoginRequest(username, password);
        Response response = authService.login(request);

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.jsonPath().getString("message")).containsIgnoringCase(expectedMessage);
    }

    @Test(description = "Verify retrieving authenticated user profile using valid Bearer token")
    @Story("User Profile")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Validates that /auth/me returns the authenticated user profile when provided with a valid Bearer token.")
    public void testGetAuthenticatedUserProfile() {
        Response loginResp = authService.login(
                TestCredentials.getDemoUsername(),
                TestCredentials.getDemoPassword()
        );
        assertThat(loginResp.getStatusCode()).isEqualTo(200);

        String token = loginResp.as(LoginResponse.class).getAccessToken();

        Response profileResp = authService.getAuthenticatedUserProfile(token);

        assertThat(profileResp.getStatusCode()).isEqualTo(200);

        User profile = profileResp.as(User.class);
        assertThat(profile.getId()).isPositive();
        assertThat(profile.getUsername()).isEqualTo(TestCredentials.getDemoUsername());
        assertThat(profile.getEmail()).isNotBlank();
    }

    @Test(description = "Verify retrieving user profile with invalid or expired token returns 401 Unauthorized")
    @Story("Negative Authentication")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Ensures requests to /auth/me with invalid or expired Bearer tokens are rejected with 401 Unauthorized.")
    public void testGetProfileWithInvalidTokenReturns401() {
        String invalidToken = "invalid-or-expired-token-sample";

        Response response = authService.getAuthenticatedUserProfile(invalidToken);

        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getString("message")).containsIgnoringCase("token");
    }
}
