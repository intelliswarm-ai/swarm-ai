package ai.intelliswarm.swarmai.enterprise.license;

import ai.intelliswarm.swarmai.spi.LicenseProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validateAcceptsLicenseWithValidSignature() throws Exception {
        KeyPair keyPair = generateKeyPair();
        LicenseValidator validator = new LicenseValidator(keyPair.getPublic());

        ObjectNode payload = baseLicensePayload();
        payload.put("edition", "enterprise");

        String encodedKey = encodeAndSign(payload, keyPair);
        LicenseKey key = validator.validate(encodedKey);

        assertEquals(LicenseProvider.Edition.ENTERPRISE, key.edition());
        assertTrue(key.hasFeature("governance"));
    }

    @Test
    void validateRejectsLicenseWithInvalidSignature() throws Exception {
        KeyPair keyPair = generateKeyPair();
        LicenseValidator validator = new LicenseValidator(keyPair.getPublic());

        ObjectNode payload = baseLicensePayload();
        String encodedKey = encodeAndSign(payload, keyPair);

        ObjectNode tampered = (ObjectNode) objectMapper.readTree(new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8));
        tampered.put("edition", "enterprise");
        String tamperedEncoded = Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(tampered));

        LicenseValidationException ex = assertThrows(LicenseValidationException.class, () -> validator.validate(tamperedEncoded));
        assertTrue(ex.getMessage().contains("signature verification failed"));
    }

    private ObjectNode baseLicensePayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("licenseId", "lic-123");
        payload.put("tenantId", "tenant-1");
        payload.put("edition", "team");
        payload.put("issuedAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        payload.put("expiresAt", Instant.now().plus(30, ChronoUnit.DAYS).toString());
        payload.put("maxAgents", 10);
        ArrayNode features = payload.putArray("features");
        features.add("governance");
        return payload;
    }

    private String encodeAndSign(ObjectNode payload, KeyPair keyPair) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(objectMapper.writeValueAsBytes(payload));
        String signed = Base64.getEncoder().encodeToString(signature.sign());

        ObjectNode signedPayload = payload.deepCopy();
        signedPayload.put("signature", signed);
        return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(signedPayload));
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
