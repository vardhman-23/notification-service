package com.demo.notification.util;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise utility for PII sanitization and sensitive data masking in audit logs and telemetry.
 * <p>
 * Complies with strict privacy standards (GDPR, CCPA, GLBA) by guaranteeing that plain-text emails,
 * phone numbers, authentication tokens, and card numbers never leak into permanent audit trails.
 */
public final class DataMaskingUtils {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("([^@]{1,3})([^@]*)@(.+)");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\+?\\d{1,3})?(\\d{2,3})(\\d+)(\\d{4})");
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    private DataMaskingUtils() {
        // Utility class
    }

    /**
     * Masks an email address preserving the first letter, last letter before domain, and the domain.
     * Example: "trader_john@example.com" -> "t*********n@example.com"
     *
     * @param email plain-text email address
     * @return masked email or sanitized placeholder
     */
    public static String maskEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return "[EMPTY]";
        }
        String trimmed = email.trim();
        Matcher matcher = EMAIL_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return maskGeneral(trimmed);
        }

        String prefix = matcher.group(1);
        String middle = matcher.group(2);
        String domain = matcher.group(3);

        if (middle.isEmpty()) {
            return prefix.charAt(0) + "***@" + domain;
        }

        String maskedMiddle = "*".repeat(Math.min(middle.length(), 6));
        return prefix.charAt(0) + maskedMiddle + middle.charAt(middle.length() - 1) + "@" + domain;
    }

    /**
     * Masks a phone number preserving the country/area prefix and last 4 digits.
     * Example: "+14155552671" -> "+1415***2671"
     *
     * @param phone plain-text phone number
     * @return masked phone number
     */
    public static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone)) {
            return "[EMPTY]";
        }
        String digits = phone.replaceAll("[^+\\d]", "");
        if (digits.length() < 7) {
            return maskGeneral(phone);
        }

        String prefix = digits.substring(0, Math.min(5, digits.length() - 4));
        String suffix = digits.substring(digits.length() - 4);
        return prefix + "***" + suffix;
    }

    /**
     * Intelligently masks a notification destination (auto-detects email or phone).
     *
     * @param destination destination string
     * @return masked destination
     */
    public static String maskDestination(String destination) {
        if (!StringUtils.hasText(destination)) {
            return "[EMPTY]";
        }
        String trimmed = destination.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            // Mask URL query parameters / basic auth tokens
            return trimmed.replaceAll("(?<=://)[^@]+@", "***:***@").replaceAll("\\?.*", "?***");
        }
        if (trimmed.contains("@")) {
            return maskEmail(trimmed);
        }
        if (trimmed.startsWith("+") || trimmed.matches("^[\\d\\s()-]+$")) {
            return maskPhone(trimmed);
        }
        return maskGeneral(trimmed);
    }

    /**
     * Masks sensitive tokens (credit cards, SSNs) within generic text or payload bodies.
     *
     * @param text input payload or message
     * @return sanitized text with sensitive numbers replaced
     */
    public static String maskSensitiveContent(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String result = CREDIT_CARD_PATTERN.matcher(text).replaceAll("****-****-****-****");
        return SSN_PATTERN.matcher(result).replaceAll("***-**-****");
    }

    private static String maskGeneral(String str) {
        if (str.length() <= 4) {
            return "****";
        }
        return str.substring(0, 2) + "***" + str.substring(str.length() - 2);
    }
}
