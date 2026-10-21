package name.krot.adbsshtunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ServiceResolveQueueTest {
    private static final class LegacyNsd implements ServiceResolveQueue.Resolver<String> {
        final List<String> started = new ArrayList<>();
        final List<Runnable> callbacks = new ArrayList<>();
        boolean resolving;
        @Override public void resolve(String value, Runnable completed) {
            assertFalse("Legacy NSD rejects overlapping resolution with ALREADY_ACTIVE", resolving);
            resolving = true;
            started.add(value);
            callbacks.add(completed);
        }
        void finish(int index) { resolving = false; callbacks.get(index).run(); }
    }

    @Test public void ownPhoneIsNotDroppedBehindOtherLanPhones() {
        LegacyNsd nsd = new LegacyNsd();
        ServiceResolveQueue<String> queue = new ServiceResolveQueue<>(nsd);
        queue.offer("other", "other");
        queue.offer("self", "self:old-port");
        queue.offer("self", "self:new-port");
        assertEquals(Collections.singletonList("other"), nsd.started);
        nsd.finish(0);
        assertEquals(Arrays.asList("other", "self:new-port"), nsd.started);
    }

    @Test public void lostServiceAndStoppedDiscoveryDoNotStartStaleWork() {
        LegacyNsd nsd = new LegacyNsd();
        ServiceResolveQueue<String> queue = new ServiceResolveQueue<>(nsd);
        queue.offer("a", "a"); queue.offer("lost", "lost"); queue.offer("b", "b");
        queue.remove("lost");
        nsd.finish(0);
        assertEquals(Arrays.asList("a", "b"), nsd.started);
        queue.offer("c", "c"); queue.close(); queue.offer("after-close", "after-close");
        nsd.finish(1);
        assertEquals(Arrays.asList("a", "b"), nsd.started);
    }

    @Test public void duplicateCompletionCannotReleaseTheNextRequest() {
        LegacyNsd nsd = new LegacyNsd();
        ServiceResolveQueue<String> queue = new ServiceResolveQueue<>(nsd);
        queue.offer("a", "a"); queue.offer("b", "b"); queue.offer("c", "c");
        nsd.finish(0);
        // Invoke only the old queue completion, without changing the fake backend state.
        // A duplicate framework callback must not start c while b is resolving.
        nsd.callbacks.get(0).run();
        assertEquals(Arrays.asList("a", "b"), nsd.started);
        nsd.finish(1);
        assertEquals(Arrays.asList("a", "b", "c"), nsd.started);
    }

    @Test public void synchronousResolverFailureDoesNotWedgeTheQueue() {
        List<String> started = new ArrayList<>();
        List<Runnable> callbacks = new ArrayList<>();
        ServiceResolveQueue<String> queue = new ServiceResolveQueue<>((value, done) -> {
            started.add(value);
            if (value.equals("invalid")) throw new IllegalArgumentException("NSD rejected request");
            callbacks.add(done);
        });
        queue.offer("invalid", "invalid"); queue.offer("good", "good");
        assertEquals(Arrays.asList("invalid", "good"), started);
        callbacks.get(0).run();
    }
}
