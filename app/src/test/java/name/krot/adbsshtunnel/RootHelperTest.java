package name.krot.adbsshtunnel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RootHelperTest {
    @Test public void cancelledGuardLeavesConfigurationRunning() throws Exception {
        WaitingProcess guard = new WaitingProcess("checked");
        WaitingProcess configuration = new WaitingProcess("configured");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> check = workers.submit(() -> RootHelper.awaitResult(guard));
            Future<String> configure = workers.submit(() -> RootHelper.awaitResult(configuration));
            assertTrue(guard.waiting.await(2, TimeUnit.SECONDS));
            assertTrue(configuration.waiting.await(2, TimeUnit.SECONDS));
            check.cancel(true);
            assertTrue(guard.destroyed.await(2, TimeUnit.SECONDS));
            assertFalse(configure.isDone());
            assertEquals(1, configuration.destroyed.getCount());
            configuration.finished.countDown();
            assertEquals("configured", configure.get(2, TimeUnit.SECONDS));
            assertEquals(0, configuration.destroyed.getCount());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static final class WaitingProcess extends Process {
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch destroyed = new CountDownLatch(1);
        private final byte[] output;
        WaitingProcess(String output) { this.output = output.getBytes(StandardCharsets.UTF_8); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(output); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException {
            waiting.countDown(); finished.await(); return 0;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waiting.countDown(); return finished.await(timeout, unit);
        }
        @Override public int exitValue() {
            if (finished.getCount() != 0) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public void destroy() { destroyed.countDown(); finished.countDown(); }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }
}
