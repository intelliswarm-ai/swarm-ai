package ai.intelliswarm.swarmai.tool.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("KillSwitch Unit Tests")
class KillSwitchTest {

    @AfterEach
    void clear() {
        System.clearProperty(KillSwitch.PROP);
    }

    @Test
    @DisplayName("with no env var and no system property the kill-switch is OFF")
    void offByDefault() {
        // Env var SWARMAI_TOOLS_DISABLED is presumed unset in the test env;
        // if it's set externally this test will be intentionally noisy.
        assertFalse(KillSwitch.isDisabled(),
                "Test env should not set " + KillSwitch.ENV);
        assertDoesNotThrow(KillSwitch::checkOrThrow);
    }

    @Test
    @DisplayName("'true' / '1' / 'yes' / 'y' (case-insensitive) all disable; other values do not")
    void truthyValuesDisable() {
        for (String v : new String[]{"true", "TRUE", "True", "1", "yes", "Y", "y"}) {
            System.setProperty(KillSwitch.PROP, v);
            assertTrue(KillSwitch.isDisabled(), "expected disabled for value: " + v);
        }
        for (String v : new String[]{"false", "0", "no", "off", " ", ""}) {
            System.setProperty(KillSwitch.PROP, v);
            assertFalse(KillSwitch.isDisabled(), "expected enabled for value: " + v);
        }
    }

    @Test
    @DisplayName("checkOrThrow throws KillSwitchEngagedException when set, with a useful message")
    void throwWhenEngaged() {
        System.setProperty(KillSwitch.PROP, "true");
        KillSwitchEngagedException ex = assertThrows(KillSwitchEngagedException.class, KillSwitch::checkOrThrow);
        assertTrue(ex.getMessage().contains(KillSwitch.ENV)
                || ex.getMessage().contains(KillSwitch.PROP), ex.getMessage());
    }
}
