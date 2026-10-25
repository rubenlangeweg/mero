package studio.invrse.mero;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import java.util.Arrays;

/** A neutral glass finish over the caller's blurred artwork, with no layout insets. */
final class LiquidGlassDrawable extends Drawable {
    private final float radius;
    private final float stroke;
    private final boolean focused;
    private final RectF body = new RectF();
    private final RectF edge = new RectF();
    private final RectF interior = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint depth = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;
    private int tintAlpha = 140;

    LiquidGlassDrawable(float radiusPx, float density, boolean focused) {
        radius = Math.max(0, radiusPx);
        stroke = Math.max(0.6f, density * 0.6f);
        this.focused = focused;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(stroke);
        depth.setStyle(Paint.Style.STROKE);
        depth.setStrokeWidth(stroke);
    }

    /** Samples the visible centre if the bitmap contains blur padding. Never retains the image. */
    void setBackdrop(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || focused) return;
        Rect bounds = getBounds();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int visibleWidth = bounds.width() > 0 ? Math.min(width, bounds.width()) : width;
        int visibleHeight = bounds.height() > 0 ? Math.min(height, bounds.height()) : height;
        int left = (width - visibleWidth) / 2;
        int top = (height - visibleHeight) / 2;
        float[] samples = new float[96];
        float total = 0;
        int n = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 12; x++) {
                int pixel = bitmap.getPixel(left + Math.min(visibleWidth - 1, (2 * x + 1) * visibleWidth / 24),
                        top + Math.min(visibleHeight - 1, (2 * y + 1) * visibleHeight / 16));
                float value = (0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel)
                        + 0.0722f * Color.blue(pixel)) / 255f;
                samples[n++] = value;
                total += value;
            }
        }
        Arrays.sort(samples);
        float brightness = 0.35f * total / n + 0.65f * samples[n * 4 / 5];
        // Bright artwork needs a stronger neutral tint for the small white labels.
        float adaptation = Math.max(0, Math.min(1, (brightness - 0.12f) / 0.72f));
        int next = Math.round(255 * (0.35f + 0.29f * adaptation));
        if (next != tintAlpha) { tintAlpha = next; invalidateSelf(); }
    }

    @Override protected void onBoundsChange(Rect bounds) {
        body.set(bounds);
        edge.set(body);
        edge.inset(stroke / 2, stroke / 2);
        interior.set(body);
        interior.inset(stroke * 1.5f, stroke * 1.5f);
        if (body.isEmpty()) return;
        sheen.setShader(new LinearGradient(body.left, body.top, body.left, body.bottom,
                focused ? new int[]{0x25FFFFFF, 0x08FFFFFF, 0x10FFFFFF}
                        : new int[]{0x10FFFFFF, 0x00FFFFFF, 0x05FFFFFF},
                new float[]{0, 0.48f, 1}, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(body.left, body.top, body.right, body.bottom,
                focused ? new int[]{0xCBFFFFFF, 0x48FFFFFF, 0x20FFFFFF, 0x88FFFFFF}
                        : new int[]{0x9CFFFFFF, 0x2CFFFFFF, 0x12FFFFFF, 0x57FFFFFF},
                new float[]{0, 0.3f, 0.7f, 1}, Shader.TileMode.CLAMP));
        depth.setShader(new LinearGradient(body.left, body.top, body.left, body.bottom,
                new int[]{0x20000000, 0x00000000, 0x30000000},
                new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        if (body.isEmpty() || alpha == 0) return;
        fill.setColor(focused ? Color.argb(26, 255, 255, 255) : Color.argb(tintAlpha, 0, 0, 0));
        fill.setAlpha(Math.round((focused ? 26 : tintAlpha) * alpha / 255f));
        sheen.setAlpha(alpha);
        rim.setAlpha(alpha);
        depth.setAlpha(alpha);
        canvas.drawRoundRect(body, radius, radius, fill);
        canvas.drawRoundRect(body, radius, radius, sheen);
        canvas.drawRoundRect(edge, Math.max(0, radius - stroke / 2), Math.max(0, radius - stroke / 2), rim);
        if (!interior.isEmpty()) canvas.drawRoundRect(interior, Math.max(0, radius - stroke * 1.5f),
                Math.max(0, radius - stroke * 1.5f), depth);
    }

    @Override public void setAlpha(int value) {
        value = Math.max(0, Math.min(255, value));
        if (alpha != value) { alpha = value; invalidateSelf(); }
    }

    @Override public int getAlpha() { return alpha; }

    @Override public void setColorFilter(ColorFilter filter) {
        fill.setColorFilter(filter);
        sheen.setColorFilter(filter);
        rim.setColorFilter(filter);
        depth.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
