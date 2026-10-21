package name.krot.adbsshtunnel;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keep legacy NSD resolutions serial, including asynchronous completions. */
final class ServiceResolveQueue<T> implements AutoCloseable {
    interface Resolver<T> { void resolve(T value, Runnable completed); }
    private final Resolver<T> resolver;
    private final Map<String, Entry<T>> waiting = new LinkedHashMap<>();
    private Entry<T> active;
    private boolean closed;

    private static final class Entry<T> {
        final T value;
        Entry(T value) { this.value = value; }
    }

    ServiceResolveQueue(Resolver<T> resolver) { this.resolver = resolver; }

    void offer(String name, T value) {
        synchronized (this) {
            if (closed) return;
            waiting.put(name, new Entry<>(value));
        }
        drain();
    }

    synchronized void remove(String name) { waiting.remove(name); }

    private void drain() {
        final Entry<T> request;
        synchronized (this) {
            if (closed || active != null || waiting.isEmpty()) return;
            String name = waiting.keySet().iterator().next();
            request = waiting.remove(name);
            active = request;
        }
        try { resolver.resolve(request.value, () -> complete(request)); }
        catch (RuntimeException e) { complete(request); }
    }

    private void complete(Entry<T> request) {
        // An obsolete or duplicate callback cannot release another resolution.
        synchronized (this) {
            if (active != request) return;
            active = null;
        }
        drain();
    }

    @Override public synchronized void close() { closed = true; waiting.clear(); }
}
