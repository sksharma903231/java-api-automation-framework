package com.sksharma.api.tests.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for loading external test data files from test resources.
 */
public final class TestDataLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestDataLoader() {
    }

    public static JsonNode loadJson(String resourcePath) {
        try (InputStream is = TestDataLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Test data file not found: " + resourcePath);
            }
            return MAPPER.readTree(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse test data JSON: " + resourcePath, e);
        }
    }

    public static Object[][] loadAsDataProvider(String resourcePath) {
        JsonNode root = loadJson(resourcePath);
        if (!root.isArray()) {
            throw new IllegalArgumentException("Test data root must be an array: " + resourcePath);
        }

        List<Object[]> rows = new ArrayList<>();
        for (JsonNode element : root) {
            rows.add(new Object[]{element});
        }
        return rows.toArray(new Object[0][]);
    }
}
