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

    @Test public void mapsBackendStatesToMainScreenTone() {
        assertEquals(UiText.Tone.OFF, UiText.tone(false, "SSH :19191 → ADB :5555 · client target 127.0.0.1:5555"));
        assertEquals(UiText.Tone.OFF, UiText.tone(false, "Import between 1 and 32 public keys"));
        assertEquals(UiText.Tone.READY, UiText.tone(true, "SSH :19191 → ADB :37211 · client target 127.0.0.1:5555"));
        assertEquals(UiText.Tone.WORKING, UiText.tone(true, "Starting"));
        assertEquals(UiText.Tone.WORKING, UiText.tone(true, "Stopped"));
        assertEquals(UiText.Tone.WORKING, UiText.tone(true, "Checking local ADB 127.0.0.1:5555"));
        assertEquals(UiText.Tone.WORKING, UiText.tone(true, null));
        assertEquals(UiText.Tone.ATTENTION, UiText.tone(true, "Waiting for Wireless Debugging"));
        assertEquals(UiText.Tone.ATTENTION, UiText.tone(true, "SSH closed: no allowed network. Check lists and Android permissions"));
    }
}
