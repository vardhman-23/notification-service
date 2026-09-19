package com.demo.notification.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataMaskingUtilsTest {

    @Test
    @DisplayName("Mask email: normal email masks middle characters")
    void testMaskEmail() {
        assertThat(DataMaskingUtils.maskEmail("trader_john@example.com"))
                .isEqualTo("t******n@example.com");

        assertThat(DataMaskingUtils.maskEmail("a@b.com"))
                .isEqualTo("a***@b.com");

        assertThat(DataMaskingUtils.maskEmail(null))
                .isEqualTo("[EMPTY]");

        assertThat(DataMaskingUtils.maskEmail("notanemail"))
                .contains("***");
    }

    @Test
    @DisplayName("Mask phone: masks middle digits preserving prefix and last 4")
    void testMaskPhone() {
        assertThat(DataMaskingUtils.maskPhone("+14155552671"))
                .isEqualTo("+1415***2671");

        assertThat(DataMaskingUtils.maskPhone("5551234"))
                .isEqualTo("555***1234");

        assertThat(DataMaskingUtils.maskPhone("123"))
                .isEqualTo("****");

        assertThat(DataMaskingUtils.maskPhone(null))
                .isEqualTo("[EMPTY]");
    }

    @Test
    @DisplayName("Mask destination: auto-detects email, phone, and webhook URLs")
    void testMaskDestination() {
        assertThat(DataMaskingUtils.maskDestination("client@demo.com"))
                .contains("@demo.com");

        assertThat(DataMaskingUtils.maskDestination("+18005550199"))
                .contains("***0199");

        assertThat(DataMaskingUtils.maskDestination("https://user:secret@webhook.site/path?token=xyz123"))
                .contains("***:***@")
                .contains("?***");

        assertThat(DataMaskingUtils.maskDestination(null))
                .isEqualTo("[EMPTY]");
    }

    @Test
    @DisplayName("Mask sensitive content: masks credit cards and SSNs in plain text")
    void testMaskSensitiveContent() {
        String payload = "Client card is 4111 2222 3333 4444 and SSN is 123-45-6789 for tax form";
        String masked = DataMaskingUtils.maskSensitiveContent(payload);

        assertThat(masked).doesNotContain("4111");
        assertThat(masked).doesNotContain("123-45-6789");
        assertThat(masked).contains("****-****-****-****");
        assertThat(masked).contains("***-**-****");

        assertThat(DataMaskingUtils.maskSensitiveContent(null)).isEmpty();
    }
}

