package ai.intelliswarm.swarmai.spi;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.time.Instant;
import java.util.Set;

/**
 * SPI for license validation.
 * Enterprise module provides the real implementation with JWT/RSA validation.
 * Community edition provides an always-valid community license.
 */
@PublicApi(since = "1.0")
public interface LicenseProvider {

    LicenseInfo getLicense();

    boolean hasFeature(String feature);

    boolean isValid();

    record LicenseInfo(
            String tenantId,
            Edition edition,
            Instant expiresAt,
            int maxAgents,
            Set<String> features
    ) {
        public boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }
    }

    enum Edition {
        COMMUNITY,
        TEAM,
        BUSINESS,
        ENTERPRISE
    }

    /**
     * Community license — always valid, no enterprise features.
     */
    LicenseProvider COMMUNITY = new LicenseProvider() {
        private static final LicenseInfo COMMUNITY_LICENSE = new LicenseInfo(
                "community", Edition.COMMUNITY, null, 5, Set.of()
        );

        @Override
        public LicenseInfo getLicense() {
            return COMMUNITY_LICENSE;
        }

        @Override
        public boolean hasFeature(String feature) {
            return false;
        }

        @Override
        public boolean isValid() {
            return true;
        }
    };
}
