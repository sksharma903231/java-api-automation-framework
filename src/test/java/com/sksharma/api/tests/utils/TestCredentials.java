package com.sksharma.api.tests.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Accessor for public DummyJSON demo account credentials used strictly for automated testing.
 * These are mock demo accounts publicly documented by DummyJSON (https://dummyjson.com/docs/auth).
 */
public final class TestCredentials {

    private static final String CREDENTIALS_FILE = "demo-credentials.properties";
    private static final Properties properties = new Properties();

    static {
        try (InputStream is = TestCredentials.class.getClassLoader().getResourceAsStream(CREDENTIALS_FILE)) {
            if (is != null) {
                properties.load(is);
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError("Failed to load test credentials from " + CREDENTIALS_FILE);
        }
    }

    private TestCredentials() {
    }

    public static String getDemoUsername() {
        return properties.getProperty("dummyjson.demo.username", "emilys");
    }

    public static String getDemoPassword() {
        return properties.getProperty("dummyjson.demo.password", "emilyspass");
    }
}
