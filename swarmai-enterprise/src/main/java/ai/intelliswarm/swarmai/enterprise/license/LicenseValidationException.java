package ai.intelliswarm.swarmai.enterprise.license;

/**
 * Thrown when a license key is invalid, expired, or cannot be decoded.
 */
public class LicenseValidationException extends RuntimeException {

    public LicenseValidationException(String message) {
        super(message);
    }

    public LicenseValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
