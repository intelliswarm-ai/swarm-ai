package ai.intelliswarm.swarmai.enterprise.license;

import ai.intelliswarm.swarmai.spi.LicenseProvider.Edition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates enterprise license keys.
 * License keys are Base64-encoded JSON payloads with a signature field.
 */
public class LicenseValidator {

    private static final Logger logger = LoggerFactory.getLogger(LicenseValidator.class);
    private static final String PUBLIC_KEY_PROPERTY = "swarmai.enterprise.license.public-key";
    private static final String PUBLIC_KEY_ENV = "SWARMAI_ENTERPRISE_LICENSE_PUBLIC_KEY";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PublicKey verificationKey;

    public LicenseValidator() {
        this(loadVerificationKey());
    }

    LicenseValidator(PublicKey verificationKey) {
        this.verificationKey = verificationKey;
    }

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

            String signature = requireField(node, "signature");
            verifySignature(node, signature);

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

    private void verifySignature(JsonNode licenseNode, String signatureB64) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(verificationKey);
            verifier.update(canonicalPayload(licenseNode));
            boolean verified = verifier.verify(Base64.getDecoder().decode(signatureB64));
            if (!verified) {
                throw new LicenseValidationException("License signature verification failed");
            }
        } catch (LicenseValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new LicenseValidationException("License signature verification failed", e);
        }
    }

    private byte[] canonicalPayload(JsonNode licenseNode) {
        if (!licenseNode.isObject()) {
            throw new LicenseValidationException("License payload must be a JSON object");
        }

        ObjectNode payload = ((ObjectNode) licenseNode).deepCopy();
        payload.remove("signature");
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new LicenseValidationException("Failed to canonicalize license payload", e);
        }
    }

    private static PublicKey loadVerificationKey() {
        String keyMaterial = System.getProperty(PUBLIC_KEY_PROPERTY);
        if (keyMaterial == null || keyMaterial.isBlank()) {
            keyMaterial = System.getenv(PUBLIC_KEY_ENV);
        }

        if (keyMaterial == null || keyMaterial.isBlank()) {
            throw new LicenseValidationException(
                    "No license verification public key configured. Set system property '"
                            + PUBLIC_KEY_PROPERTY + "' or env '" + PUBLIC_KEY_ENV + "'."
            );
        }

        try {
            String normalized = keyMaterial
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(normalized);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new LicenseValidationException("Invalid license verification public key", e);
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
