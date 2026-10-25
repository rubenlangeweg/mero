package studio.invrse.mero;

import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONObject;
import io.nayuki.qrcodegen.QrCode;

/** A small home screen, operated entirely with the TV remote. */
public final class HomeActivity extends Activity {
    private static final String STREMIO = "com.stremio.one";
    private static final String NORDVPN = "com.nordvpn.android";
    private static final int PICK_WALLPAPER = 10;
    private static final float DOCK_SCALE = 0.60f;
    private static final float CLOCK_SCALE = 0.70f;
    private static final long ART_MODE_IDLE_MS = 60_000L;
    private ImageView wallpaper;
    private GlassPanel glassDock;
    private GlassPanel clockGlass;
    private GlassPanel sensorGlass;
    private GlassPanel artworkGlass;
    private final Set<GlassPanel> dialogGlassPanels = new HashSet<>();
    private TextView artworkArtist;
    private TextView artworkTitle;
    private TextView artworkYear;
    private final TextView[] sensorValues = new TextView[2];
    private final TextView[] sensorLabels = new TextView[2];
    private final LinearLayout[] sensorRows = new LinearLayout[2];
    private TextView sensorStatus;
    private HomeAssistantPoller homeAssistant;
    private HomeAssistantStore homeAssistantStore;
    private HomeAssistantClient.Snapshot sensorSnapshot = HomeAssistantClient.disconnected();
    private LinearLayout favorites;
    private DockFocusView dockFocus;
    private View firstFavorite;
    private Dialog appsDialog;
    private Dialog wallpaperDialog;
    private Dialog managerDialog;
    private Dialog settingsDialog;
    private TextView managerAddress;
    private TextView managerCode;
    private TextView managerHelp;
    private ImageView managerQr;
    private boolean importingWallpaper;
    private ArtworkLibrary library;
    private ArtworkServer artworkServer;
    private ArtworkCatalog catalog;
    private Set<String> selectedWorks;
    private String currentWork;
    private ArtworkRotation rotation;
    private boolean rotationPaused;
    private String wallpaperFit = "fill";
    private float wallpaperFocusX = .5f;
    private float wallpaperFocusY = .5f;
    private boolean artModeHidden;
    private long lastInteractionMs;
    private final Set<Integer> wakeKeys = new HashSet<>();
    private boolean wakeTouch;
    private boolean resumed;
    private final Handler rotationHandler = new Handler(Looper.getMainLooper());
    private final Runnable rotate = () -> {
        if (rotation.isRunning()) nextArtwork();
    };
    private final Runnable artModeIdle = this::updateArtMode;
    private final Runnable managerStatus = new Runnable() {
        @Override public void run() {
            if (managerDialog == null || !managerDialog.isShowing()) return;
            if (!artworkServer.isRunning()) {
                managerAddress.setText("Session closed");
                clearManagerCode();
                managerHelp.setText("Close this window and open Manage library again to reconnect.");
                return;
            }
            rotationHandler.postDelayed(this, 1000);
        }
    };
    private final BroadcastReceiver screenState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) {
                artworkServer.stop();
                clearManagerCode();
                if (dockFocus != null) dockFocus.reset();
            } else if (dockFocus != null && favorites != null && favorites.findFocus() != null) {
                dockFocus.moveTo(favorites.findFocus(), false);
            }
            lastInteractionMs = SystemClock.elapsedRealtime();
            setArtModeHidden(false, false);
            updateArtMode();
            updateRotation();
            updateSensors();
        }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        library = new ArtworkLibrary(this);
        catalog = new ArtworkCatalog(this, library);
        artworkServer = new ArtworkServer(this, library, () -> runOnUiThread(() -> {
            if (!isDestroyed() && !isFinishing() && resumed) reloadArtworkLibrary(true);
        }), new ArtworkServer.Playback() {
            @Override public JSONObject snapshot() throws Exception {
                return playbackOnMain(HomeActivity.this::playbackSnapshot);
            }
            @Override public JSONObject command(JSONObject input) throws Exception {
                long deadline = SystemClock.elapsedRealtime() + 2500;
                return playbackOnMain(() -> playbackCommand(input, deadline));
            }
        });
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        rotationPaused = preferences.getBoolean("rotation_paused", false);
        if ("custom".equals(getIntent().getStringExtra("background")))
            preferences.edit().putString("background_mode", "custom").apply();
        selectedWorks = enabledWorks(catalog);
        currentWork = preferences.getString("current_work", catalog.works.get(0).key);
        long interval = catalog.intervalMinutes * 60_000L;
        long remaining = preferences.getLong("rotation_interval", interval) == interval
                ? preferences.getLong("rotation_remaining", interval) : interval;
        rotation = new ArtworkRotation(remaining, interval);
        homeAssistantStore = new HomeAssistantStore(this);
        homeAssistant = new HomeAssistantPoller(new HomeAssistantClient(homeAssistantStore), this::renderSensors);
        IntentFilter screenEvents = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenEvents.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenState, screenEvents);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.MATRIX);
        wallpaper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        wallpaper.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            applyArtworkFraming();
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) refreshGlass();
        });
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.END);
        clock.setPadding(dp(11 * CLOCK_SCALE), dp(6 * CLOCK_SCALE), dp(11 * CLOCK_SCALE), dp(6 * CLOCK_SCALE));
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(17);
        time.setTextColor(Color.WHITE);
        time.setShadowLayer(dp(1), 0, dp(.5f), 0x66000000);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(9);
        date.setTextColor(0xD1FFFFFF);
        date.setShadowLayer(dp(1), 0, dp(.5f), 0x66000000);
        clock.addView(date);
        FrameLayout.LayoutParams clockPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clockPosition.setMargins(dp(48), dp(30), dp(48), 0);
        clockGlass = new GlassPanel(14 * CLOCK_SCALE);
        clockGlass.addView(clock, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout information = new LinearLayout(this);
        information.setOrientation(LinearLayout.VERTICAL);
        information.setGravity(Gravity.END);
        information.addView(clockGlass);
        sensorGlass = new GlassPanel(14 * CLOCK_SCALE);
        LinearLayout readings = new LinearLayout(this);
        readings.setOrientation(LinearLayout.VERTICAL);
        readings.setPadding(dp(8), dp(6), dp(8), dp(5));
        for (int i = 0; i < sensorValues.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            sensorRows[i] = row;
            sensorLabels[i] = text("", 9, 0xD1FFFFFF);
            sensorLabels[i].setSingleLine(true);
            sensorLabels[i].setEllipsize(TextUtils.TruncateAt.END);
            row.addView(sensorLabels[i], new LinearLayout.LayoutParams(0, -2, 1));
            sensorValues[i] = text("—", 10, Color.WHITE);
            sensorValues[i].setGravity(Gravity.END);
            row.addView(sensorValues[i]);
            readings.addView(row, new LinearLayout.LayoutParams(dp(100), dp(18)));
        }
        sensorStatus = text("", 7.5f, 0xD1FFFFFF);
        sensorStatus.setGravity(Gravity.END);
        sensorStatus.setPadding(0, dp(3), 0, 0);
        readings.addView(sensorStatus, new LinearLayout.LayoutParams(-1, -2));
        sensorGlass.addView(readings, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams sensorPosition = new LinearLayout.LayoutParams(-2, -2);
        sensorPosition.topMargin = dp(6);
        information.addView(sensorGlass, sensorPosition);
        sensorGlass.setVisibility(View.GONE);
        root.addView(information, clockPosition);

        glassDock = new GlassPanel(24 * DOCK_SCALE);
        favorites = new LinearLayout(this);
        favorites.setGravity(Gravity.CENTER);
        favorites.setPadding(dp(6 * DOCK_SCALE), dp(6 * DOCK_SCALE), dp(6 * DOCK_SCALE), dp(6 * DOCK_SCALE));
        dockFocus = new DockFocusView(this, dp(18 * DOCK_SCALE));
        glassDock.addView(dockFocus, new FrameLayout.LayoutParams(-1, -1));
        glassDock.addView(favorites, new FrameLayout.LayoutParams(-1, -1));
        favorites.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                dockFocus.reconcileLayout());
        FrameLayout.LayoutParams dockPosition = new FrameLayout.LayoutParams(dp(440 * DOCK_SCALE), dp(48 * DOCK_SCALE), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dockPosition.bottomMargin = dp(28);
        root.addView(glassDock, dockPosition);

        artworkGlass = new GlassPanel(14 * CLOCK_SCALE);
        LinearLayout caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setPadding(dp(8), dp(5), dp(8), dp(5));
        int captionWidth = Math.max(1, Math.min(dp(240),
                (getResources().getDisplayMetrics().widthPixels - dockPosition.width) / 2 - dp(80)));
        artworkArtist = text("", 9, 0xEDFFFFFF);
        artworkTitle = text("", 8.5f, 0xD1FFFFFF);
        artworkYear = text("", 8.5f, 0xD1FFFFFF);
        for (TextView line : new TextView[]{artworkArtist, artworkTitle, artworkYear}) {
            line.setSingleLine(true);
            line.setEllipsize(TextUtils.TruncateAt.END);
            line.setMaxWidth(captionWidth);
            caption.addView(line);
        }
        artworkGlass.addView(caption, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams captionPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        captionPosition.setMargins(dp(48), 0, 0, dp(28));
        root.addView(artworkGlass, captionPosition);
        loadWallpaper();
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        lastInteractionMs = SystemClock.elapsedRealtime();
        wakeKeys.clear();
        wakeTouch = false;
        setArtModeHidden(false, false);
        reloadArtworkLibrary(false);
        rebuildFavorites();
        updateRotation();
        updateSensors();
        updateArtMode();
    }

    @Override protected void onPause() {
        resumed = false;
        rotationHandler.removeCallbacks(artModeIdle);
        setArtModeHidden(false, false);
        wakeKeys.clear();
        wakeTouch = false;
        dockFocus.reset();
        artworkServer.stop();
        if (managerDialog != null) managerDialog.dismiss();
        if (settingsDialog != null) settingsDialog.dismiss();
        updateRotation();
        updateSensors();
        super.onPause();
    }

    @Override protected void onDestroy() {
        rotationHandler.removeCallbacks(rotate);
        rotationHandler.removeCallbacks(managerStatus);
        rotationHandler.removeCallbacks(artModeIdle);
        dialogGlassPanels.clear();
        dockFocus.reset();
        artworkServer.stop();
        clearManagerCode();
        homeAssistant.close();
        unregisterReceiver(screenState);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if ("custom".equals(intent.getStringExtra("background")))
            getPreferences(MODE_PRIVATE).edit().putString("background_mode", "custom").apply();
        if (appsDialog != null) appsDialog.dismiss();
        if (wallpaperDialog != null) wallpaperDialog.dismiss();
        if (managerDialog != null) managerDialog.dismiss();
        if (settingsDialog != null) settingsDialog.dismiss();
        loadWallpaper();
        updateRotation();
        dockFocus.reset();
        if (firstFavorite != null) {
            firstFavorite.requestFocus();
            dockFocus.moveTo(firstFavorite, false);
        }
        updateSensors();
        noteInteraction();
    }

    private View utility(String label, View.OnClickListener action) {
        TextView button = text(label, 9, 0xEDFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(5), dp(8 * DOCK_SCALE), dp(5), dp(8 * DOCK_SCALE));
        int height = dp(48 * DOCK_SCALE) - 2 * dp(6 * DOCK_SCALE);
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(0, height, 1f);
        button.setLayoutParams(position);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnFocusChangeListener((view, focused) -> {
            if (focused) dockFocus.moveTo(view, true);
            else dockFocus.focusLost();
        });
        button.setOnClickListener(action);
        return button;
    }

    private void noteInteraction() {
        lastInteractionMs = SystemClock.elapsedRealtime();
        setArtModeHidden(false, true);
        updateArtMode();
    }

    private void updateArtMode() {
        rotationHandler.removeCallbacks(artModeIdle);
        if (clockGlass == null || !resumed) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        boolean interactive = power.isInteractive();
        boolean sharing = artworkServer != null && artworkServer.isRunning();
        if (!catalog.artModeEnabled || !interactive || !hasWindowFocus() || sharing || !dialogGlassPanels.isEmpty()) {
            setArtModeHidden(false, false);
            // Sharing can expire without another UI event. Never keep the screen awake for it.
            if (catalog.artModeEnabled && interactive && sharing)
                rotationHandler.postDelayed(artModeIdle, 1000);
            return;
        }
        long remaining = ART_MODE_IDLE_MS - (SystemClock.elapsedRealtime() - lastInteractionMs);
        if (remaining <= 0) setArtModeHidden(true, true);
        else rotationHandler.postDelayed(artModeIdle, remaining);
    }

    private void setArtModeHidden(boolean hidden, boolean animate) {
        if (clockGlass == null) return;
        boolean changed = artModeHidden != hidden;
        artModeHidden = hidden;
        if (!changed && animate) return;
        boolean motion = animate && Settings.Global.getFloat(getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0;
        for (View view : new View[]{clockGlass, sensorGlass, glassDock, artworkGlass}) {
            if (view == null) continue;
            view.animate().cancel();
            view.setImportantForAccessibility(hidden ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (motion) view.animate().alpha(hidden ? 0f : 1f).setDuration(hidden ? 400 : 180).start();
            else view.setAlpha(hidden ? 0f : 1f);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) lastInteractionMs = SystemClock.elapsedRealtime();
        updateArtMode();
    }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        noteInteraction();
    }

    private boolean navigationKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK
                || code == KeyEvent.KEYCODE_MENU;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (wakeKeys.contains(code)) {
            if (event.getAction() == KeyEvent.ACTION_UP) wakeKeys.remove(code);
            return true;
        }
        if (artModeHidden && navigationKey(code)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) wakeKeys.add(code);
            noteInteraction();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (wakeTouch) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                wakeTouch = false;
            return true;
        }
        if (artModeHidden && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            wakeTouch = true;
            noteInteraction();
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private JSONObject playbackOnMain(Callable<JSONObject> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        FutureTask<JSONObject> result = new FutureTask<>(action);
        if (!rotationHandler.post(result)) throw new IllegalStateException("The TV home screen is unavailable");
        try { return result.get(3, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) {
            result.cancel(false);
            throw new IllegalStateException("The TV is busy. Check Now showing before retrying.");
        } catch (InterruptedException interrupted) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The TV connection ended. Check Now showing before retrying.");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("The TV could not complete this action");
        }
    }

    private JSONObject playbackSnapshot() throws Exception {
        ArtworkCatalog.Work active = null;
        if ("collection".equals(backgroundMode()))
            for (ArtworkCatalog.Work work : catalog.works) if (work.key.equals(currentWork)) active = work;
        String[] ids = active == null ? new String[]{"", ""} : active.key.split("/", 2);
        return new JSONObject().put("artistId", ids[0]).put("workId", ids[1])
                .put("title", active == null ? "" : active.title).put("artist", active == null ? "" : active.artist)
                .put("year", active == null ? "" : active.year)
                .put("remainingMs", rotation.remaining(SystemClock.elapsedRealtime()))
                .put("paused", rotationPaused).put("rotating", rotation.isRunning()).put("background", backgroundMode())
                .put("artModeEnabled", catalog.artModeEnabled).put("artModeHidden", artModeHidden);
    }

    private JSONObject playbackCommand(JSONObject input, long deadline) throws Exception {
        if (!resumed || isFinishing() || isDestroyed()) throw new IllegalStateException("Keep Mero open on the TV");
        reloadArtworkLibrary(false);
        // A slow metadata refresh must not execute a queued command after its caller timed out.
        if (SystemClock.elapsedRealtime() >= deadline)
            throw new IllegalStateException("The TV is busy. Check Now showing before retrying.");
        String action = input.getString("action");
        if ("show".equals(action) || "next".equals(action) || "previous".equals(action)) {
            // The pairing dialog may still be on screen after scanning its QR code.
            if (managerDialog != null) managerDialog.dismiss();
            if (wallpaperDialog != null) wallpaperDialog.dismiss();
            if (appsDialog != null) appsDialog.dismiss();
        }
        if ("show".equals(action)) {
            String key = input.getString("artistId") + "/" + input.getString("workId");
            if (!catalog.keys().contains(key)) throw new IllegalArgumentException("This artwork is no longer in the library");
            currentWork = key;
            rotation.reset(SystemClock.elapsedRealtime());
            getPreferences(MODE_PRIVATE).edit().putString("current_work", currentWork)
                    .putString("background_mode", "collection")
                    .putLong("rotation_remaining", rotation.getIntervalMs()).apply();
            loadWallpaper();
            updateRotation();
        } else if ("next".equals(action)) stepArtwork(1);
        else if ("previous".equals(action)) stepArtwork(-1);
        else if ("pause".equals(action) || "resume".equals(action)) {
            rotationPaused = "pause".equals(action);
            getPreferences(MODE_PRIVATE).edit().putBoolean("rotation_paused", rotationPaused).apply();
            updateRotation();
        } else throw new IllegalArgumentException("Choose Show, Previous, Next, Pause or Resume");
        noteInteraction();
        return playbackSnapshot();
    }

    private void focusable(View view, boolean tile) {
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(surface(false, tile));
        view.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(surface(focused, tile));
            v.animate().scaleX(focused && tile ? 1.05f : 1f)
                    .scaleY(focused && tile ? 1.05f : 1f).setDuration(140).start();
        });
    }

    private Drawable surface(boolean focused, boolean tile) {
        return focused ? new LiquidGlassDrawable(dp(tile ? 22 : 18 * DOCK_SCALE),
                getResources().getDisplayMetrics().density, true) : new ColorDrawable(Color.TRANSPARENT);
    }

    private final class GlassPanel extends FrameLayout {
        private final ImageView backdrop;
        private final View tint;
        private final LiquidGlassDrawable material;
        private final int blurPadding = dp(36);

        GlassPanel(float radius) {
            super(HomeActivity.this);
            setClipToOutline(true);
            setElevation(dp(2));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radius));
                }
            });
            backdrop = new ImageView(HomeActivity.this);
            backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
            backdrop.setScaleX(1.025f);
            backdrop.setScaleY(1.025f);
            backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (Build.VERSION.SDK_INT >= 31)
                backdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(12), dp(12), Shader.TileMode.CLAMP));
            tint = new View(HomeActivity.this);
            material = new LiquidGlassDrawable(dp(radius), getResources().getDisplayMetrics().density, false);
            tint.setBackground(material);
            // Decorative layers follow the content bounds without inflating wrap-content panels.
            addView(backdrop, new FrameLayout.LayoutParams(0, 0));
            addView(tint, new FrameLayout.LayoutParams(0, 0));
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            backdrop.layout(-blurPadding, -blurPadding, getWidth() + blurPadding, getHeight() + blurPadding);
            tint.layout(0, 0, getWidth(), getHeight());
            refreshBackdrop();
        }

        void refreshBackdrop() {
            if (getWidth() == 0 || getHeight() == 0) return;
            Bitmap image = Bitmap.createBitmap(getWidth() + 2 * blurPadding, getHeight() + 2 * blurPadding,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.drawColor(Color.BLACK);
            int[] artPosition = new int[2];
            int[] panelPosition = new int[2];
            wallpaper.getLocationOnScreen(artPosition);
            getLocationOnScreen(panelPosition);
            canvas.translate(artPosition[0] - panelPosition[0] + blurPadding,
                    artPosition[1] - panelPosition[1] + blurPadding);
            wallpaper.draw(canvas);
            material.setBounds(0, 0, getWidth(), getHeight());
            material.setBackdrop(image);
            backdrop.setImageBitmap(image);
        }
    }

    private void refreshGlass() {
        if (glassDock != null) glassDock.post(glassDock::refreshBackdrop);
        if (clockGlass != null) clockGlass.post(clockGlass::refreshBackdrop);
        if (sensorGlass != null) sensorGlass.post(sensorGlass::refreshBackdrop);
        if (artworkGlass != null) artworkGlass.post(artworkGlass::refreshBackdrop);
        for (GlassPanel glass : new ArrayList<>(dialogGlassPanels)) glass.post(glass::refreshBackdrop);
    }

    /** A background layer only: native dialog content still determines every measurement. */
    private void styleGlassDialog(Dialog dialog, boolean fullScreen) {
        Window window = dialog.getWindow();
        if (window == null || !(window.getDecorView() instanceof ViewGroup)) return;
        ViewGroup decor = (ViewGroup) window.getDecorView();
        Rect padding = new Rect(decor.getPaddingLeft(), decor.getPaddingTop(),
                decor.getPaddingRight(), decor.getPaddingBottom());
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        decor.setPadding(padding.left, padding.top, padding.right, padding.bottom);
        clearDialogPanels(decor);
        GlassPanel glass = new GlassPanel(fullScreen ? 0 : 22);
        glass.setElevation(0);
        glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        dialogGlassPanels.add(glass);
        glass.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                dialogGlassPanels.remove(glass);
                rotationHandler.post(HomeActivity.this::updateArtMode);
            }
        });
        decor.addView(glass, 0, new ViewGroup.LayoutParams(0, 0));
        decor.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            glass.layout(padding.left, padding.top, decor.getWidth() - padding.right, decor.getHeight() - padding.bottom);
        });
        decor.post(() -> glass.layout(padding.left, padding.top,
                decor.getWidth() - padding.right, decor.getHeight() - padding.bottom));
        if (dialog instanceof AlertDialog) {
            AlertDialog alert = (AlertDialog) dialog;
            if (alert.getListView() != null) alert.getListView().setSelector(
                    new LiquidGlassDrawable(dp(8), getResources().getDisplayMetrics().density, true));
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                View button = alert.getButton(which);
                if (button == null) continue;
                StateListDrawable states = new StateListDrawable();
                states.addState(new int[]{android.R.attr.state_focused}, surface(true, false));
                states.addState(new int[]{android.R.attr.state_pressed}, surface(true, false));
                states.addState(new int[0], surface(false, false));
                backgroundKeepingPadding(button, states);
            }
        }
    }

    private void clearDialogPanels(View view) {
        String name = "";
        try { if (view.getId() != View.NO_ID) name = getResources().getResourceEntryName(view.getId()); }
        catch (android.content.res.Resources.NotFoundException ignored) { }
        if (name.equals("parentPanel") || name.equals("topPanel") || name.equals("contentPanel")
                || name.equals("customPanel") || name.equals("buttonPanel") || view instanceof ListView)
            backgroundKeepingPadding(view, new ColorDrawable(Color.TRANSPARENT));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clearDialogPanels(group.getChildAt(i));
        }
    }

    private void backgroundKeepingPadding(View view, Drawable background) {
        int left = view.getPaddingLeft(), top = view.getPaddingTop();
        int right = view.getPaddingRight(), bottom = view.getPaddingBottom();
        view.setBackground(background);
        view.setPadding(left, top, right, bottom);
    }

    private void renderArtworkCaption(ArtworkCatalog.Work work) {
        artworkGlass.setVisibility(work == null ? View.GONE : View.VISIBLE);
        if (work == null) return;
        artworkArtist.setText(work.artist);
        artworkTitle.setText(work.title);
        artworkYear.setText(work.year);
        artworkYear.setVisibility(work.year.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private Set<String> enabledWorks(ArtworkCatalog source) {
        Set<String> enabled = new HashSet<>();
        for (ArtworkCatalog.Work work : source.works) if (work.enabled) enabled.add(work.key);
        if (enabled.isEmpty() && !source.works.isEmpty()) enabled.add(source.works.get(0).key);
        return enabled;
    }

    private void reloadArtworkLibrary(boolean refreshPicker) {
        try {
            if (catalog.revision == library.revision()) return;
            ArtworkCatalog updated = new ArtworkCatalog(this, library);
            long now = SystemClock.elapsedRealtime();
            long remaining = rotation.pause(now);
            long interval = updated.intervalMinutes * 60_000L;
            if (rotation.getIntervalMs() != interval) remaining = interval;
            catalog = updated;
            selectedWorks = enabledWorks(catalog);
            rotation = new ArtworkRotation(remaining, interval);
            getPreferences(MODE_PRIVATE).edit().putLong("rotation_interval", interval)
                    .putLong("rotation_remaining", remaining).apply();
            loadWallpaper();
            updateRotation();
            updateArtMode();
            if (refreshPicker && wallpaperDialog != null && wallpaperDialog.isShowing()) {
                wallpaperDialog.dismiss();
                chooseWallpaper();
            }
        } catch (Exception unavailable) {
            Toast.makeText(this, "Cannot refresh the artwork library. Try again.", Toast.LENGTH_LONG).show();
        }
    }

    private void updateSensors() {
        updateSensors(homeAssistantStore.load());
    }

    private void updateSensors(HomeAssistantStore.Config config) {
        // Lifecycle/settings refreshes invalidate old callbacks, including same-label server/entity changes.
        homeAssistant.stop();
        if (!config.enabled) {
            renderSensors(HomeAssistantClient.disconnected());
            return;
        }
        renderSensors(HomeAssistantClient.initial(config));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (resumed && power.isInteractive()) homeAssistant.start();
    }

    private void renderSensors(HomeAssistantClient.Snapshot snapshot) {
        sensorSnapshot = snapshot;
        if (sensorGlass == null) return;
        sensorGlass.setVisibility(snapshot.enabled ? View.VISIBLE : View.GONE);
        for (int i = 0; i < sensorValues.length; i++) {
            boolean visible = snapshot.enabled && i < snapshot.readings.size();
            sensorRows[i].setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) {
                HomeAssistantClient.Reading reading = snapshot.readings.get(i);
                sensorLabels[i].setText(reading.label);
                sensorValues[i].setText(reading.value + (reading.unit.isEmpty() ? "" : " " + reading.unit));
            }
        }
        String status = snapshot.status;
        if (status.isEmpty() && snapshot.updatedAtMs != 0)
            status = "Updated " + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(snapshot.updatedAtMs));
        sensorStatus.setText(status);
        sensorGlass.post(sensorGlass::refreshBackdrop);
    }

    private void rebuildFavorites() {
        dockFocus.reset();
        favorites.removeAllViews();
        firstFavorite = utility(shortcutLabel(0), v -> launchShortcut(0));
        favorites.addView(firstFavorite);
        favorites.addView(utility(shortcutLabel(1), v -> launchShortcut(1)));
        favorites.addView(utility("Apps", v -> showApps()));
        favorites.addView(utility("Artwork", v -> chooseWallpaper()));
        favorites.addView(utility("Settings", v -> openSettings()));
        firstFavorite.requestFocus();
        dockFocus.moveTo(firstFavorite, false);
    }

    private String shortcutPackage(int slot) {
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        String value = preferences.getString("favorite_" + slot, slot == 0 ? STREMIO : NORDVPN);
        if (value == null || value.isEmpty()) return null;
        try {
            PackageManager pm = getPackageManager();
            return pm.getLeanbackLaunchIntentForPackage(value) != null || pm.getLaunchIntentForPackage(value) != null
                    ? value : null;
        } catch (RuntimeException unavailable) { return null; }
    }

    private String shortcutLabel(int slot) {
        String packageName = shortcutPackage(slot);
        if (packageName == null) return "Choose app";
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0)).toString(); }
        catch (PackageManager.NameNotFoundException | SecurityException unavailable) { return "Choose app"; }
    }

    private void launchShortcut(int slot) {
        String packageName = shortcutPackage(slot);
        if (packageName == null) chooseShortcut(slot);
        else launch(packageName);
    }

    private void launch(String packageName) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(packageName);
        if (intent == null) intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null) {
            Toast.makeText(this, "App unavailable. Check Settings → Apps.", Toast.LENGTH_LONG).show();
            return;
        }
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, "Cannot open this app. Check Settings → Apps.", Toast.LENGTH_LONG).show();
        }
    }

    private void openSettings() {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Mero settings")
                .setItems(new String[]{"App shortcuts", "Home Assistant", "TV settings"}, (window, which) -> {
                    if (which == 0) shortcutSettings();
                    else if (which == 1) homeAssistantSettings();
                    else openTvSettings();
                }).setPositiveButton("Done", null).create();
        showSettingsDialog(dialog);
    }

    private void openTvSettings() {
        try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, "TV settings unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private void showSettingsDialog(AlertDialog dialog) {
        settingsDialog = dialog;
        dialog.setOnDismissListener(window -> {
            if (settingsDialog == dialog) settingsDialog = null;
            noteInteraction();
            rotationHandler.post(() -> {
                if (settingsDialog == null && resumed && firstFavorite != null) {
                    firstFavorite.requestFocus();
                    dockFocus.moveTo(firstFavorite, false);
                }
            });
        });
        dialog.show();
        styleGlassDialog(dialog, false);
    }

    private void shortcutSettings() {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("App shortcuts")
                .setItems(new String[]{"First shortcut  ·  " + shortcutLabel(0), "Second shortcut  ·  " + shortcutLabel(1)},
                        (window, which) -> chooseShortcut(which))
                .setNegativeButton("Back", (window, which) -> openSettings()).create();
        dialog.setOnCancelListener(window -> openSettings());
        showSettingsDialog(dialog);
    }

    private void chooseShortcut(int slot) {
        List<ResolveInfo> apps = installedApps();
        String[] labels = new String[apps.size() + 1];
        labels[0] = "No app selected";
        for (int i = 0; i < apps.size(); i++) labels[i + 1] = apps.get(i).loadLabel(getPackageManager()).toString();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(slot == 0 ? "First shortcut" : "Second shortcut")
                .setItems(labels, (window, which) -> {
                    String packageName = which == 0 ? "" : apps.get(which - 1).activityInfo.packageName;
                    getPreferences(MODE_PRIVATE).edit().putString("favorite_" + slot, packageName).apply();
                    rebuildFavorites();
                }).setNegativeButton("Back", (window, which) -> shortcutSettings()).create();
        dialog.setOnCancelListener(window -> shortcutSettings());
        showSettingsDialog(dialog);
    }

    private void homeAssistantSettings() {
        HomeAssistantStore.Config config = homeAssistantStore.load();
        updateSensors(config);
        String state = config.enabled ? "On" : config.configured ? "Off" : "Not configured";
        String action = config.enabled ? "Turn readings off" : config.configured ? "Turn readings on" : "Set up readings";
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Home Assistant  ·  " + state)
                .setItems(new String[]{action, "Setup instructions", "Remove connection"}, (window, which) -> {
                    if (which == 0 && config.configured) {
                        try {
                            homeAssistant.stop();
                            homeAssistantStore.setEnabled(!config.enabled);
                        } catch (Exception unavailable) {
                            Toast.makeText(this, "Cannot change Home Assistant setting. Try again.", Toast.LENGTH_LONG).show();
                        }
                        homeAssistantSettings();
                    } else if (which < 2) homeAssistantSetup();
                    else confirmRemoveHomeAssistant();
                }).setNegativeButton("Back", (window, which) -> openSettings()).create();
        dialog.setOnCancelListener(window -> openSettings());
        showSettingsDialog(dialog);
    }

    private void homeAssistantSetup() {
        String path = "/sdcard/Android/data/" + getPackageName() + "/files/home-assistant.json";
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Set up optional readings")
                .setMessage("Use the repository’s Home Assistant guide to create a private JSON file with your HTTPS address, "
                        + "access token and one or two sensor entities. Send it over ADB to:\n\n" + path
                        + "\n\nThen select Check configuration. No connection is enabled until setup is complete. "
                        + "Tokens stay encrypted on this TV and are excluded from artwork backups.")
                .setPositiveButton("Check configuration", (window, which) -> {
                    homeAssistant.stop();
                    try { homeAssistantStore.checkConfiguration(); }
                    catch (Exception unavailable) {
                        Toast.makeText(this, "Configuration could not be imported. Check the JSON file.", Toast.LENGTH_LONG).show();
                    }
                    homeAssistantSettings();
                }).setNegativeButton("Back", (window, which) -> homeAssistantSettings()).create();
        dialog.setOnCancelListener(window -> homeAssistantSettings());
        showSettingsDialog(dialog);
    }

    private void confirmRemoveHomeAssistant() {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Remove Home Assistant connection?")
                .setMessage("Remove the saved token and sensor settings from this TV? You can set up a new connection later.")
                .setPositiveButton("Remove connection", (window, which) -> {
                    homeAssistant.stop();
                    try { homeAssistantStore.clear(); }
                    catch (Exception unavailable) {
                        Toast.makeText(this, "Could not remove the complete connection. Readings are off.", Toast.LENGTH_LONG).show();
                    }
                    homeAssistantSettings();
                }).setNegativeButton("Keep connection", (window, which) -> homeAssistantSettings()).create();
        dialog.setOnCancelListener(window -> homeAssistantSettings());
        showSettingsDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    private List<ResolveInfo> installedApps() {
        Map<String, ResolveInfo> uniqueApps = new HashMap<>();
        PackageManager pm = getPackageManager();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                String pkg = info.activityInfo.packageName;
                if (!pkg.equals(getPackageName())) uniqueApps.putIfAbsent(pkg, info);
            }
        }
        List<ResolveInfo> apps = new ArrayList<>(uniqueApps.values());
        apps.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        return apps;
    }

    private void showApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = installedApps();

        appsDialog = new Dialog(this);
        appsDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(28), dp(48), dp(24));
        page.setBackgroundColor(Color.TRANSPARENT);
        TextView title = text("All apps", 27, Color.WHITE);
        page.addView(title);
        TextView hint = text("Press Back to return home", 14, 0xD1FFFFFF);
        hint.setPadding(0, dp(5), 0, dp(15));
        page.addView(hint);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(6), dp(6), dp(6), dp(6));
        GridLayout grid = new GridLayout(this);
        int columns = Math.max(3, Math.min(6, (int)(getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density - 108) / 150));
        grid.setColumnCount(columns);
        for (ResolveInfo app : apps) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams position = new GridLayout.LayoutParams();
            position.width = dp(140);
            position.height = dp(108);
            position.setMargins(dp(5), dp(5), dp(5), dp(5));
            tile.setLayoutParams(position);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.loadIcon(pm));
            tile.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
            String label = app.loadLabel(pm).toString();
            TextView name = text(label, 13, Color.WHITE);
            name.setMaxLines(2);
            name.setGravity(Gravity.CENTER);
            name.setPadding(dp(5), dp(6), dp(5), 0);
            tile.addView(name);
            tile.setContentDescription(label);
            focusable(tile, true);
            tile.setOnClickListener(v -> launch(app.activityInfo.packageName));
            grid.addView(tile);
        }
        scroll.addView(grid);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        appsDialog.setContentView(page);
        Window window = appsDialog.getWindow();
        if (window != null) {
            window.setTitle("Mero All apps");
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        appsDialog.show();
        if (window != null) window.setLayout(-1, -1);
        styleGlassDialog(appsDialog, true);
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    private void chooseWallpaper() {
        if (importingWallpaper) return;
        String[] names = new String[catalog.artists.size() + 3];
        for (int i = 0; i < catalog.artists.size(); i++) {
            ArtworkCatalog.Artist artist = catalog.artists.get(i);
            int selected = 0;
            for (ArtworkCatalog.Work work : artist.works) if (selectedWorks.contains(work.key)) selected++;
            names[i] = artist.name + "  ·  " + selected + "/" + artist.works.size() + " selected";
        }
        names[catalog.artists.size()] = "Manage library";
        names[catalog.artists.size() + 1] = "Art mode  ·  " + (catalog.artModeEnabled ? "On" : "Off");
        names[catalog.artists.size() + 2] = rotationPaused ? "Resume rotation" : "Pause rotation";
        wallpaperDialog = new AlertDialog.Builder(this)
                .setTitle(rotationPaused ? "Artists · rotation paused" : "Artists · every " + catalog.intervalMinutes + " minutes")
                .setItems(names, (dialog, which) -> {
                    if (which == catalog.artists.size()) showLibraryManager();
                    else if (which == catalog.artists.size() + 1) {
                        try {
                            library.updateSettings(catalog.intervalMinutes, !catalog.artModeEnabled);
                            reloadArtworkLibrary(false);
                            noteInteraction();
                        } catch (Exception unavailable) {
                            Toast.makeText(this, "Cannot save Art mode. Try again.", Toast.LENGTH_LONG).show();
                        }
                    } else if (which == catalog.artists.size() + 2) {
                        rotationPaused = !rotationPaused;
                        getPreferences(MODE_PRIVATE).edit().putBoolean("rotation_paused", rotationPaused).apply();
                        updateRotation();
                    }
                    else chooseWorks(catalog.artists.get(which));
                })
                .setNeutralButton("Next artwork", (dialog, which) -> nextArtwork())
                .setNegativeButton("Backgrounds", (dialog, which) -> backgroundOptions())
                .setPositiveButton("Done", null).create();
        wallpaperDialog.show();
        styleGlassDialog(wallpaperDialog, false);
        if (wallpaperDialog.getWindow() != null) wallpaperDialog.getWindow().setTitle("Mero Artwork");
    }

    private void showLibraryManager() {
        try {
            if (!artworkServer.isRunning()) artworkServer.start();
        } catch (Exception unavailable) {
            Toast.makeText(this, "Cannot open the artwork manager. Check the TV network and try again.", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView instructions = text("Scan with your phone, or open the address:", 14, 0xEDFFFFFF);
        page.addView(instructions);
        LinearLayout connection = new LinearLayout(this);
        connection.setGravity(Gravity.CENTER_VERTICAL);
        connection.setPadding(0, dp(12), 0, dp(12));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        managerAddress = text(artworkServer.address(), 22, Color.WHITE);
        managerAddress.setPadding(0, 0, 0, dp(12));
        details.addView(managerAddress);
        details.addView(text("Pairing code", 12, 0xD1FFFFFF));
        String code = artworkServer.pairingCode();
        managerCode = text(code.length() == 8 ? code.substring(0, 4) + " " + code.substring(4) : code, 28, Color.WHITE);
        managerCode.setPadding(0, dp(4), 0, 0);
        details.addView(managerCode);
        connection.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        managerQr = new ImageView(this);
        managerQr.setScaleType(ImageView.ScaleType.CENTER);
        managerQr.setBackgroundColor(Color.WHITE);
        managerQr.setContentDescription("Scan to open the artwork library with the pairing code filled in");
        managerQr.setImageBitmap(createManagerQr(artworkServer.address() + "/#code=" + code, dp(156)));
        LinearLayout.LayoutParams qrPosition = new LinearLayout.LayoutParams(dp(156), dp(156));
        qrPosition.leftMargin = dp(24);
        connection.addView(managerQr, qrPosition);
        page.addView(connection);
        managerHelp = text("Use the same home network. Keep Mero open while managing. This session closes after 30 minutes or when you leave the app.", 12, 0xD1FFFFFF);
        managerHelp.setMaxWidth(dp(620));
        page.addView(managerHelp);
        managerDialog = new AlertDialog.Builder(this).setTitle("Manage artwork library")
                .setView(page).setPositiveButton("Close", null)
                .setNegativeButton("Stop sharing", (dialog, which) -> artworkServer.stop()).create();
        managerDialog.setOnDismissListener(dialog -> {
            rotationHandler.removeCallbacks(managerStatus);
            clearManagerCode();
        });
        managerDialog.show();
        Window window = managerDialog.getWindow();
        if (window != null) window.setLayout(Math.min(dp(680), getResources().getDisplayMetrics().widthPixels - dp(96)), -2);
        styleGlassDialog(managerDialog, false);
        rotationHandler.removeCallbacks(managerStatus);
        rotationHandler.post(managerStatus);
        updateArtMode();
    }

    private Bitmap createManagerQr(String value, int dimension) {
        QrCode qr = QrCode.encodeText(value, QrCode.Ecc.MEDIUM);
        int scale = Math.max(1, dimension / (qr.size + 8));
        int offset = (dimension - qr.size * scale) / 2;
        Bitmap image = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.WHITE);
        Paint ink = new Paint();
        ink.setColor(Color.BLACK);
        ink.setAntiAlias(false);
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y))
                    canvas.drawRect(offset + x * scale, offset + y * scale,
                            offset + (x + 1) * scale, offset + (y + 1) * scale, ink);
            }
        }
        return image;
    }

    private void clearManagerCode() {
        if (managerCode != null) managerCode.setText("");
        if (managerQr != null) {
            managerQr.setImageDrawable(null);
            managerQr.setBackgroundColor(Color.TRANSPARENT);
            managerQr.setVisibility(View.GONE);
        }
    }

    private void chooseWorks(ArtworkCatalog.Artist artist) {
        if (artist.works.isEmpty()) {
            wallpaperDialog = new AlertDialog.Builder(this).setTitle(artist.name)
                    .setMessage("This folder is empty. Open Manage library to upload artworks from your phone or laptop.")
                    .setNegativeButton("Back", (dialog, which) -> chooseWallpaper())
                    .setPositiveButton("Manage library", (dialog, which) -> showLibraryManager()).create();
            wallpaperDialog.show();
            styleGlassDialog(wallpaperDialog, false);
            return;
        }
        String[] titles = new String[artist.works.size()];
        boolean[] checked = new boolean[titles.length];
        Set<String> pending = new HashSet<>(selectedWorks);
        for (int i = 0; i < titles.length; i++) {
            ArtworkCatalog.Work work = artist.works.get(i);
            titles[i] = work.title + (work.year.isEmpty() ? "" : " (" + work.year + ")");
            checked[i] = pending.contains(work.key);
        }
        AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle(artist.name)
                .setMultiChoiceItems(titles, checked, (dialog, which, selected) -> {
                    String key = artist.works.get(which).key;
                    if (selected) pending.add(key); else pending.remove(key);
                })
                .setNegativeButton("Back", (dialog, which) -> chooseWallpaper())
                .setPositiveButton("Use selection", null).create();
        picker.setOnCancelListener(dialog -> chooseWallpaper());
        wallpaperDialog = picker;
        picker.show();
        styleGlassDialog(picker, false);
        picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (pending.isEmpty()) {
                Toast.makeText(this, "Select at least one artwork", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                library.selectWorks(pending);
                getPreferences(MODE_PRIVATE).edit().putString("background_mode", "collection").apply();
                reloadArtworkLibrary(false);
                rotation.reset(SystemClock.elapsedRealtime());
                loadWallpaper();
                updateRotation();
                picker.dismiss();
            } catch (Exception unavailable) {
                Toast.makeText(this, "Cannot save this selection. Try again.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void backgroundOptions() {
        wallpaperDialog = new AlertDialog.Builder(this)
                .setTitle("Background")
                .setItems(new String[]{"Artist collection", "Choose image", "Plain dark"}, (dialog, which) -> {
                    if (which == 1) {
                        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                        try { startActivityForResult(picker, PICK_WALLPAPER); }
                        catch (ActivityNotFoundException unavailable) {
                            Toast.makeText(this, "No image picker installed. An image can also be sent from your computer.", Toast.LENGTH_LONG).show();
                        }
                    } else {
                        getPreferences(MODE_PRIVATE).edit().putString("background_mode", which == 0 ? "collection" : "plain").apply();
                        loadWallpaper();
                        updateRotation();
                    }
                }).setNegativeButton("Back", (dialog, which) -> chooseWallpaper()).create();
        wallpaperDialog.setOnCancelListener(dialog -> chooseWallpaper());
        wallpaperDialog.show();
        styleGlassDialog(wallpaperDialog, false);
    }

    private List<ArtworkCatalog.Work> playlist() {
        List<ArtworkCatalog.Work> playlist = new ArrayList<>();
        for (ArtworkCatalog.Work work : catalog.works) if (selectedWorks.contains(work.key)) playlist.add(work);
        return playlist;
    }

    private String backgroundMode() {
        String legacyMode = getPreferences(MODE_PRIVATE).getBoolean("plain", false) ? "plain"
                : (wallpaperFile().exists() ? "custom" : "collection");
        return getPreferences(MODE_PRIVATE).getString("background_mode", legacyMode);
    }

    private void nextArtwork() {
        stepArtwork(1);
    }

    private void stepArtwork(int direction) {
        List<ArtworkCatalog.Work> playlist = playlist();
        if (playlist.isEmpty()) return;
        int current = -1;
        for (int i = 0; i < playlist.size(); i++) if (playlist.get(i).key.equals(currentWork)) current = i;
        int next = current == -1 ? (direction > 0 ? 0 : playlist.size() - 1)
                : (current + direction + playlist.size()) % playlist.size();
        currentWork = playlist.get(next).key;
        rotation.reset(SystemClock.elapsedRealtime());
        getPreferences(MODE_PRIVATE).edit().putString("current_work", currentWork)
                .putString("background_mode", "collection").putLong("rotation_remaining", rotation.getIntervalMs()).apply();
        loadWallpaper();
        updateRotation();
    }

    private void updateRotation() {
        rotationHandler.removeCallbacks(rotate);
        long now = SystemClock.elapsedRealtime();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (resumed && power.isInteractive() && !rotationPaused && "collection".equals(backgroundMode())
                && (selectedWorks.size() > 1 || !selectedWorks.contains(currentWork))) {
            rotation.resume(now);
            rotationHandler.postDelayed(rotate, Math.max(1L, rotation.remaining(now)));
        } else {
            long remaining = rotation.pause(now);
            getPreferences(MODE_PRIVATE).edit().putLong("rotation_remaining", remaining)
                    .putLong("rotation_interval", rotation.getIntervalMs()).apply();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_WALLPAPER || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        importingWallpaper = true;
        new Thread(() -> {
            File destination = wallpaperFile();
            File temporary = new File(destination.getParentFile(), "wallpaper.tmp");
            Bitmap decoded = null;
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new java.io.IOException("Image unavailable");
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
                decoded = decodeWallpaper(temporary);
                if (decoded == null) throw new java.io.IOException("Not an image");
                if (!temporary.renameTo(destination)) decoded = null;
            } catch (Exception ignored) { /* Keep the existing wallpaper on a failed import. */ }
            temporary.delete();
            final Bitmap imported = decoded;
            runOnUiThread(() -> {
                importingWallpaper = false;
                if (isDestroyed()) return;
                if (imported != null) {
                    getPreferences(MODE_PRIVATE).edit().putString("background_mode", "custom").apply();
                    wallpaper.setImageBitmap(imported);
                    setArtworkFraming("fill", .5f, .5f);
                    renderArtworkCaption(null);
                    refreshGlass();
                    updateRotation();
                }
                else Toast.makeText(this, "Cannot use this image. Choose a JPEG or PNG.", Toast.LENGTH_LONG).show();
            });
        }, "wallpaper-import").start();
    }

    private Bitmap decodeWallpaper(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 2560 || options.outHeight / options.inSampleSize > 1440)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private void loadWallpaper() {
        String mode = backgroundMode();
        if (mode.equals("plain")) {
            wallpaper.setImageDrawable(null);
            renderArtworkCaption(null);
            refreshGlass();
            return;
        }
        File file = wallpaperFile();
        Bitmap custom = mode.equals("custom") && file.exists() ? decodeWallpaper(file) : null;
        if (custom != null) {
            wallpaper.setImageBitmap(custom);
            setArtworkFraming("fill", .5f, .5f);
            renderArtworkCaption(null);
        }
        else {
            List<ArtworkCatalog.Work> playlist = playlist();
            ArtworkCatalog.Work active = null;
            // "Show on TV" can temporarily display a work excluded from automatic rotation.
            for (ArtworkCatalog.Work work : catalog.works)
                if (work.key.equals(currentWork) && displayArtwork(work)) active = work;
            if (active == null) {
                for (ArtworkCatalog.Work work : playlist) {
                    if (displayArtwork(work)) {
                        active = work;
                        break;
                    }
                }
            }
            if (active != null) {
                currentWork = active.key;
                renderArtworkCaption(mode.equals("collection") ? active : null);
                getPreferences(MODE_PRIVATE).edit().putString("current_work", currentWork).apply();
            } else {
                wallpaper.setImageDrawable(null);
                renderArtworkCaption(null);
                Toast.makeText(this, "Artwork unavailable. Open Manage library to check your images.", Toast.LENGTH_LONG).show();
            }
        }
        refreshGlass();
    }

    private boolean displayArtwork(ArtworkCatalog.Work work) {
        try {
            if (work.file != null) {
                Bitmap image = decodeWallpaper(work.file);
                if (image == null) return false;
                wallpaper.setImageBitmap(image);
            } else {
                wallpaper.setImageResource(work.image);
            }
            setArtworkFraming(work.fit, work.focusX, work.focusY);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private File wallpaperFile() {
        File directory = getExternalFilesDir(null);
        return new File(directory == null ? getFilesDir() : directory, "wallpaper");
    }

    private void setArtworkFraming(String fit, float focusX, float focusY) {
        wallpaperFit = fit;
        wallpaperFocusX = focusX;
        wallpaperFocusY = focusY;
        applyArtworkFraming();
    }

    private void applyArtworkFraming() {
        Drawable image = wallpaper == null ? null : wallpaper.getDrawable();
        if (image == null || wallpaper.getWidth() <= 0 || wallpaper.getHeight() <= 0
                || image.getIntrinsicWidth() <= 0 || image.getIntrinsicHeight() <= 0) return;
        float width = wallpaper.getWidth(), height = wallpaper.getHeight();
        float imageWidth = image.getIntrinsicWidth(), imageHeight = image.getIntrinsicHeight();
        float scale = "fit".equals(wallpaperFit) ? Math.min(width / imageWidth, height / imageHeight)
                : Math.max(width / imageWidth, height / imageHeight);
        Matrix transform = new Matrix();
        transform.setScale(scale, scale);
        transform.postTranslate((width - imageWidth * scale) * wallpaperFocusX,
                (height - imageHeight * scale) * wallpaperFocusY);
        wallpaper.setImageMatrix(transform);
    }

    @Override public void dump(String prefix, FileDescriptor descriptor, PrintWriter writer, String[] arguments) {
        super.dump(prefix, descriptor, writer, arguments);
        writer.println(prefix + "Mero artwork=" + currentWork + " selected=" + selectedWorks.size()
                + " mode=" + backgroundMode() + " rotating=" + rotation.isRunning()
                + " remainingMs=" + rotation.remaining(SystemClock.elapsedRealtime()));
        writer.println(prefix + "Mero sensors=" + sensorSnapshot.status + " updatedAt=" + sensorSnapshot.updatedAtMs);
        writer.println(prefix + "Mero libraryRevision=" + catalog.revision + " intervalMinutes=" + catalog.intervalMinutes
                + " manager=" + artworkServer.isRunning());
        writer.println(prefix + "Mero artMode=" + catalog.artModeEnabled + " hidden=" + artModeHidden
                + " rotationPaused=" + rotationPaused + " fit=" + wallpaperFit
                + " focus=" + wallpaperFocusX + "," + wallpaperFocusY);
        writer.println(prefix + "Mero dockFocus=" + dockFocus.state());
    }

    @Override public void onBackPressed() {
        // Home is the endpoint of Back navigation; never expose a blank launcher.
        if (firstFavorite != null) firstFavorite.requestFocus();
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_MENU) {
            chooseWallpaper();
            return true;
        }
        return super.onKeyDown(code, event);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setShadowLayer(dp(1), 0, dp(.5f), 0x66000000);
        view.setFontFeatureSettings("tnum");
        return view;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
