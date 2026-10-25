package studio.invrse.mero;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One read at a time, with callbacks invalidated whenever Home is hidden. */
final class HomeAssistantPoller implements AutoCloseable {
    interface Listener { void onSnapshot(HomeAssistantClient.Snapshot snapshot); }
    private final HomeAssistantClient client;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "home-assistant-read"));
    private final Runnable refresh = this::read;
    private Future<?> pending;
    private boolean running;
    private boolean closed;
    private int generation;

    HomeAssistantPoller(HomeAssistantClient client, Listener listener) {
        this.client = client;
        this.listener = listener;
    }

    void start() {
        if (running || closed || !client.isEnabled()) return;
        running = true;
        generation++;
        read();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        handler.removeCallbacks(refresh);
        if (pending != null) pending.cancel(true);
        client.cancel();
    }

    private void read() {
        if (!running || closed) return;
        final int requestGeneration = generation;
        pending = executor.submit(() -> {
            HomeAssistantClient.Snapshot snapshot = client.fetch();
            handler.post(() -> {
                if (!running || closed || requestGeneration != generation) return;
                listener.onSnapshot(snapshot);
                if (!snapshot.enabled || snapshot.status.equals("Reconnect Home Assistant")) {
                    running = false;
                    return;
                }
                handler.postDelayed(refresh, 60_000L);
            });
        });
    }

    @Override public void close() {
        stop();
        closed = true;
        executor.shutdownNow();
    }
}
