package ai.intelliswarm.swarmai.enterprise.license;

import ai.intelliswarm.swarmai.spi.LicenseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central license management for the enterprise module.
 * Implements the core LicenseProvider SPI so that the framework can
 * gate features based on the active license.
 */
public class LicenseManager implements LicenseProvider {

    private static final Logger logger = LoggerFactory.getLogger(LicenseManager.class);

    private final LicenseKey licenseKey;
    private final LicenseInfo licenseInfo;

    public LicenseManager(String encodedKey) {
        LicenseValidator validator = new LicenseValidator();
        this.licenseKey = validator.validate(encodedKey);
        this.licenseInfo = new LicenseInfo(
                licenseKey.tenantId(),
                licenseKey.edition(),
                licenseKey.expiresAt(),
                licenseKey.maxAgents(),
                licenseKey.features()
        );
        logger.info("Enterprise license activated: edition={}, tenant={}, maxAgents={}",
                licenseKey.edition(), licenseKey.tenantId(), licenseKey.maxAgents());
    }

    @Override
    public LicenseInfo getLicense() {
        return licenseInfo;
    }

    @Override
    public boolean hasFeature(String feature) {
        return licenseKey.hasFeature(feature);
    }

    @Override
    public boolean isValid() {
        return !licenseKey.isExpired();
    }

    public LicenseKey getLicenseKey() {
        return licenseKey;
    }

    public Edition getEdition() {
        return licenseKey.edition();
    }

    public int getMaxAgents() {
        return licenseKey.maxAgents();
    }
}
