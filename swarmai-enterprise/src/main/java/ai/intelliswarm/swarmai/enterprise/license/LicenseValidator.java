package ai.intelliswarm.swarmai.enterprise.license;

import ai.intelliswarm.swarmai.spi.LicenseProvider.Edition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates enterprise license keys.
 * License keys are Base64-encoded JSON payloads with a signature field.
 * Production deployments should use RSA signature verification.
 */
public class LicenseValidator {

    private static final Logger logger = LoggerFactory.getLogger(LicenseValidator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Decodes and validates a license key string.
     *
     * @param encodedKey Base64-encoded license key
     * @return validated LicenseKey
     * @throws LicenseValidationException if the key is invalid or expired
     */
    public LicenseKey validate(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new LicenseValidationException("License key is empty");
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey.trim());
            String json = new String(decoded, StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(json);

            String licenseId = requireField(node, "licenseId");
            String tenantId = requireField(node, "tenantId");
            Edition edition = Edition.valueOf(requireField(node, "edition").toUpperCase());
            Instant issuedAt = Instant.parse(requireField(node, "issuedAt"));
            Instant expiresAt = node.has("expiresAt") && !node.get("expiresAt").isNull()
                    ? Instant.parse(node.get("expiresAt").asText())
                    : null;
            int maxAgents = node.has("maxAgents") ? node.get("maxAgents").asInt() : getDefaultMaxAgents(edition);

            Set<String> features = new HashSet<>();
            if (node.has("features") && node.get("features").isArray()) {
                node.get("features").forEach(f -> features.add(f.asText()));
            } else {
                features.addAll(getDefaultFeatures(edition));
            }

            String signature = node.has("signature") ? node.get("signature").asText() : "";

            LicenseKey key = new LicenseKey(licenseId, tenantId, edition, issuedAt, expiresAt, maxAgents, features, signature);

            if (key.isExpired()) {
                logger.warn("License {} for tenant {} expired at {}", licenseId, tenantId, expiresAt);
                throw new LicenseValidationException("License expired at " + expiresAt);
            }

            logger.info("License validated: id={}, tenant={}, edition={}, maxAgents={}, features={}",
                    licenseId, tenantId, edition, maxAgents, features);
            return key;

        } catch (LicenseValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new LicenseValidationException("Failed to decode license key: " + e.getMessage(), e);
        }
    }

    private String requireField(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull() || node.get(field).asText().isBlank()) {
            throw new LicenseValidationException("Missing required license field: " + field);
        }
        return node.get(field).asText();
    }

    private int getDefaultMaxAgents(Edition edition) {
        return switch (edition) {
            case TEAM -> 5;
            case BUSINESS -> 50;
            case ENTERPRISE -> Integer.MAX_VALUE;
            case COMMUNITY -> 5;
        };
    }

    private Set<String> getDefaultFeatures(Edition edition) {
        return switch (edition) {
            case TEAM -> Set.of("governance", "budget-advanced");
            case BUSINESS -> Set.of("governance", "budget-advanced", "multi-tenancy", "audit");
            case ENTERPRISE -> Set.of("governance", "budget-advanced", "multi-tenancy", "audit",
                    "deep-rl", "rbac", "sso", "secrets-management", "advanced-monitoring");
            case COMMUNITY -> Set.of();
        };
    }
}
