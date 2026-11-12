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

    private static final String[] STATES = {
        null, "Starting", "Stopped", "Checking root ADB protection", "Checking local ADB 127.0.0.1:5555",
        "SSH :19191 → ADB :37211 · client target 127.0.0.1:5555",
        "Waiting for Wireless Debugging", "Open the app to identify Wi-Fi",
        "No available Wi-Fi or mobile Internet network",
        "SSH closed: no allowed network. Check lists and Android permissions",
        "Waiting for local ADB: configure the selected mode", "Local ADB unavailable; configure debugging first",
        "ADB discovery unavailable", "ADB discovery unavailable: timeout",
        "Wireless Debugging stopped; enable it in Developer options", "SSH listener failed",
        "SSH exited; enable the tunnel to retry", "Import between 1 and 32 public keys",
        "Use ssh-ed25519 or ssh-rsa public keys without options", "Invalid Base64 public key",
        "Key input too large", "RSA key must be at least 2048 bits", "Invalid Ed25519 point",
        "Cannot run program \"su\": error=2, No such file or directory",
        "Cannot run program \"su\": error=13, Permission denied",
        "Root controller request failed (exit 1)", "Unsupported controller ABI", "Cannot prepare root controller",
        "Something nobody expected",
    };

    @Test public void explainsEveryStateThatNeedsAttention() {
        for (String state : STATES)
            assertEquals(state, UiText.tone(true, state) == UiText.Tone.ATTENTION,
                UiText.problem(state) != UiText.Problem.NONE);
    }

    @Test public void mapsStatesToPlainProblems() {
        assertEquals(UiText.Problem.NONE, UiText.problem("Starting"));
        assertEquals(UiText.Problem.NONE, UiText.problem("SSH :19191 → ADB :37211 · client target 127.0.0.1:5555"));
        assertEquals(UiText.Problem.KEYS, UiText.problem("Import between 1 and 32 public keys"));
        assertEquals(UiText.Problem.KEYS, UiText.problem("Invalid Ed25519 point"));
        assertEquals(UiText.Problem.WIFI_IDENTITY, UiText.problem("Open the app to identify Wi-Fi"));
        assertEquals(UiText.Problem.NO_NETWORK, UiText.problem("No available Wi-Fi or mobile Internet network"));
        assertEquals(UiText.Problem.NETWORK_BLOCKED,
            UiText.problem("SSH closed: no allowed network. Check lists and Android permissions"));
        assertEquals(UiText.Problem.WIRELESS, UiText.problem("Waiting for Wireless Debugging"));
        assertEquals(UiText.Problem.WIRELESS, UiText.problem("Wireless Debugging stopped; enable it in Developer options"));
        assertEquals(UiText.Problem.ADB_NOT_READY, UiText.problem("Local ADB unavailable; configure debugging first"));
        assertEquals(UiText.Problem.DISCOVERY, UiText.problem("ADB discovery unavailable: timeout"));
        assertEquals(UiText.Problem.ROOT_MISSING,
            UiText.problem("Cannot run program \"su\": error=2, No such file or directory"));
        assertEquals(UiText.Problem.ROOT_FAILED, UiText.problem("Cannot run program \"su\": error=13, Permission denied"));
        assertEquals(UiText.Problem.ROOT_FAILED, UiText.problem("Root controller request failed (exit 1)"));
        assertEquals(UiText.Problem.SSH_FAILED, UiText.problem("SSH listener failed"));
        assertEquals(UiText.Problem.UNKNOWN, UiText.problem("Something nobody expected"));
    }

    @Test public void everyProblemHasIconTextAndAFix() {
        for (UiText.Problem problem : UiText.Problem.values()) {
            if (problem == UiText.Problem.NONE) continue;
            assertNotEquals(problem.name(), 0, problem.icon);
            assertNotEquals(problem.name(), 0, problem.title);
            assertNotEquals(problem.name(), 0, problem.what);
            assertNotEquals(problem.name(), 0, problem.todo);
            assertNotNull(problem.name(), problem.fix);
        }
    }

    @Test public void keepsOnlyStartFailuresVisibleWhileOff() {
        assertEquals(UiText.Problem.KEYS, UiText.visibleProblem(false, "Invalid Base64 public key"));
        assertEquals(UiText.Problem.SSH_FAILED, UiText.visibleProblem(false, "SSH exited; enable the tunnel to retry"));
        assertEquals(UiText.Problem.UNKNOWN, UiText.visibleProblem(false, "Something nobody expected"));
        assertEquals(UiText.Problem.NONE, UiText.visibleProblem(false, "Waiting for Wireless Debugging"));
        assertEquals(UiText.Problem.NONE, UiText.visibleProblem(false, "No available Wi-Fi or mobile Internet network"));
        assertEquals(UiText.Problem.NONE, UiText.visibleProblem(false, "Stopped"));
        for (String state : STATES) assertEquals(state, UiText.problem(state), UiText.visibleProblem(true, state));
    }
}
