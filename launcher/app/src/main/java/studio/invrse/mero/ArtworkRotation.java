package studio.invrse.mero;

/** Counts only time while Home is visible; the caller supplies elapsed realtime. */
public final class ArtworkRotation {
    public static final long INTERVAL_MS = 30 * 60 * 1000L;

    private final long intervalMs;
    private long remainingMs;
    private long startedAtMs;
    private boolean running;

    public ArtworkRotation(long remainingMs) {
        this(remainingMs, INTERVAL_MS);
    }

    public ArtworkRotation(long remainingMs, long intervalMs) {
        if (intervalMs <= 0) throw new IllegalArgumentException("The rotation interval must be positive");
        this.intervalMs = intervalMs;
        this.remainingMs = Math.max(0L, Math.min(intervalMs, remainingMs));
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void resume(long nowElapsedMs) {
        if (running) return;
        startedAtMs = nowElapsedMs;
        running = true;
    }

    public long pause(long nowElapsedMs) {
        if (running) {
            remainingMs = remaining(nowElapsedMs);
            running = false;
        }
        return remainingMs;
    }

    public long remaining(long nowElapsedMs) {
        return running ? Math.max(0L, remainingMs - (nowElapsedMs - startedAtMs)) : remainingMs;
    }

    public boolean isRunning() {
        return running;
    }

    public void reset(long nowElapsedMs) {
        remainingMs = intervalMs;
        startedAtMs = nowElapsedMs;
    }
}
