package ai.intelliswarm.swarmai.enterprise.license;

import ai.intelliswarm.swarmai.spi.LicenseProvider.Edition;

import java.time.Instant;
import java.util.Set;

/**
 * Represents a decoded and validated enterprise license key.
 */
public record LicenseKey(
        String licenseId,
        String tenantId,
        Edition edition,
        Instant issuedAt,
        Instant expiresAt,
        int maxAgents,
        Set<String> features,
        String signature
) {
    public LicenseKey {
        if (licenseId == null || licenseId.isBlank()) {
            throw new IllegalArgumentException("licenseId is required");
        }
        if (edition == null) {
            throw new IllegalArgumentException("edition is required");
        }
        features = features != null ? Set.copyOf(features) : Set.of();
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean hasFeature(String feature) {
        return features.contains(feature);
    }
}
