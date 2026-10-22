package name.krot.adbsshtunnel;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UiTextTest {
    @Test public void identifiesMissingSuIncludingPreviouslySavedErrors() {
        assertTrue(UiText.isSuMissing("Cannot run program \"su\": error=2, No such file or directory"));
        assertTrue(UiText.isSuMissing("Cannot run program \"su\": error=2"));
    }

    @Test public void doesNotMislabelDeniedRootOrMissingControllerAsMissingSu() {
        assertFalse(UiText.isSuMissing("Cannot run program \"su\": error=13, Permission denied"));
        assertFalse(UiText.isSuMissing("Cannot run program \"su\": error=20, Not a directory"));
        assertFalse(UiText.isSuMissing("Root controller request failed (exit 127): No such file or directory"));
        assertFalse(UiText.isSuMissing("Cannot run program \"sh\": error=2, No such file or directory"));
        assertFalse(UiText.isSuMissing(null));
    }
}
