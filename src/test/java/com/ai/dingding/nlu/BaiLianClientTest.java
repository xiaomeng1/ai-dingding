package com.ai.dingding.nlu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for BaiLianClient.
 * Validates: Requirements 2.3
 */
class BaiLianClientTest {

    private String originalApiKey;

    @BeforeEach
    void setUp() {
        originalApiKey = System.getProperty("bailian.apiKey");
        System.clearProperty("bailian.apiKey");
    }

    @AfterEach
    void tearDown() {
        if (originalApiKey != null) {
            System.setProperty("bailian.apiKey", originalApiKey);
        } else {
            System.clearProperty("bailian.apiKey");
        }
    }

    @Test
    void constructor_throwsIllegalStateException_whenApiKeyNotConfigured() {
        assertThrows(IllegalStateException.class, BaiLianClient::new,
                "Expected IllegalStateException when bailian.apiKey system property is not set");
    }
}
