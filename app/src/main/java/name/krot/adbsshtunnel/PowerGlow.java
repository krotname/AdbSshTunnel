package name.krot.adbsshtunnel;

import android.animation.TimeAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;

/**
 * Light around the power button. Connecting: a bright comet circles the button. Ready: a calm,
 * slowly breathing glow with a soft flash on arrival. Turning off: the light spins down, fades and
 * the ring shrinks. Attention: a steady glow without motion. Purely decorative, never announced.
 * Runs one frame callback only while something moves; with system animations off it stays still.
 */
final class PowerGlow extends View {
    private static final float WORKING_SPEED = 0.33f, READY_SPEED = 0.05f; // degrees per millisecond
    private static final float TWO_PI = (float) (2 * Math.PI);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private TimeAnimator timer;
    private UiText.Tone tone = UiText.Tone.OFF;
    private int startColor = Color.LTGRAY, endColor = Color.GRAY;
    private float angle = -90f, speed, sweep, glow, track, scale = 0.92f, depth, burst, breath;
    private float targetSpeed, targetSweep, targetGlow, targetTrack, targetScale = 0.92f, targetDepth;
    private float breathRate; // radians per millisecond

    PowerGlow(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        trackPaint.setStyle(Paint.Style.STROKE);
        sweepPaint.setStyle(Paint.Style.STROKE);
    }

    /** Moves to a new state; {@code animate} is false when the screen is first shown. */
    void show(UiText.Tone next, boolean animate) {
        UiText.Tone previous = tone;
        tone = next;
        switch (next) {
            case WORKING:
                targetSpeed = WORKING_SPEED; targetSweep = 1f; targetGlow = 0.7f; targetTrack = 0.3f;
                targetScale = 1f; targetDepth = 0.3f; breathRate = TWO_PI / 900f; break;
            case READY:
                targetSpeed = READY_SPEED; targetSweep = 0.35f; targetGlow = 1f; targetTrack = 0.9f;
                targetScale = 1f; targetDepth = 0.35f; breathRate = TWO_PI / 2400f; break;
            case ATTENTION:
                targetSpeed = 0f; targetSweep = 0f; targetGlow = 0.8f; targetTrack = 0.9f;
                targetScale = 1f; targetDepth = 0f; breathRate = 0f; break;
            default:
                targetSpeed = 0f; targetSweep = 0f; targetGlow = 0f; targetTrack = 0.35f;
                targetScale = 0.92f; targetDepth = 0f; breathRate = 0f;
        }
        boolean motion = ValueAnimator.areAnimatorsEnabled();
        if (motion && animate && next == UiText.Tone.READY && previous != UiText.Tone.READY) burst = 1f;
        if (!animate || !motion) snap();
        if (motion && !settled()) startTimer(); else stopTimer();
        invalidate();
    }

    /** Colours of the current state; the activity cross-fades them. */
    void setColors(int start, int end) {
        if (start == startColor && end == endColor) return;
        startColor = start; endColor = end;
        rebuild();
        invalidate();
    }

    /** Stops every frame callback, for example when the screen is no longer visible. */
    void stop() {
        stopTimer();
        snap();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    @Override protected void onDetachedFromWindow() {
        stopTimer();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float size = Math.min(w, h), cx = w / 2f, cy = h / 2f, radius = size * 0.455f;
        canvas.save();
        float grow = scale + 0.035f * burst;
        canvas.scale(grow, grow, cx, cy);
        float light = glow * (1f - depth * (0.5f - 0.5f * (float) Math.sin(breath))) + 0.6f * burst;
        if (light > 0.004f) {
            glowPaint.setAlpha(alpha(light));
            canvas.drawCircle(cx, cy, size * 0.5f, glowPaint);
        }
        float ringLevel = track + 0.5f * burst;
        if (ringLevel > 0.004f) {
            trackPaint.setColor(startColor);
            trackPaint.setAlpha(alpha(ringLevel));
            canvas.drawCircle(cx, cy, radius, trackPaint);
        }
        if (sweep > 0.004f) {
            canvas.rotate(angle, cx, cy);
            sweepPaint.setAlpha(alpha(sweep));
            canvas.drawArc(ring, 180f, 180f, false, sweepPaint);
            headPaint.setAlpha(alpha(sweep));
            canvas.drawCircle(cx + radius, cy, size * 0.044f, headPaint);
        }
        canvas.restore();
    }

    private void rebuild() {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float size = Math.min(w, h), cx = w / 2f, cy = h / 2f, radius = size * 0.455f;
        ring.set(cx - radius, cy - radius, cx + radius, cy + radius);
        int glint = blend(startColor, Color.WHITE, 0.45f);
        glowPaint.setShader(new RadialGradient(cx, cy, size * 0.5f,
            new int[]{fade(startColor, 0.6f), fade(startColor, 0.6f), fade(endColor, 0f)},
            new float[]{0f, 0.76f, 1f}, Shader.TileMode.CLAMP));
        trackPaint.setStrokeWidth(size * 0.026f);
        sweepPaint.setStrokeWidth(size * 0.04f);
        sweepPaint.setShader(new SweepGradient(cx, cy,
            new int[]{fade(startColor, 0f), fade(startColor, 0f), startColor, glint},
            new float[]{0f, 0.55f, 0.9f, 1f}));
        headPaint.setShader(new RadialGradient(cx + radius, cy, size * 0.044f,
            new int[]{Color.WHITE, fade(glint, 0.7f), fade(glint, 0f)},
            new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    private void step(long deltaMs) {
        float dt = Math.min(deltaMs, 64L);
        speed = approach(speed, targetSpeed, dt, 500f);
        angle = (angle + speed * dt) % 360f;
        sweep = approach(sweep, targetSweep, dt, 450f);
        glow = approach(glow, targetGlow, dt, 400f);
        track = approach(track, targetTrack, dt, 400f);
        scale = approach(scale, targetScale, dt, 300f);
        depth = approach(depth, targetDepth, dt, 600f);
        burst = approach(burst, 0f, dt, 350f);
        if (breathRate > 0f) breath = (breath + breathRate * dt) % TWO_PI;
        if (settled()) { snap(); stopTimer(); }
        invalidate();
    }

    private boolean settled() {
        return targetSpeed == 0f && breathRate == 0f && speed < 0.001f && burst < 0.003f && depth < 0.003f
            && Math.abs(sweep - targetSweep) < 0.003f && Math.abs(glow - targetGlow) < 0.003f
            && Math.abs(track - targetTrack) < 0.003f && Math.abs(scale - targetScale) < 0.001f;
    }

    private void snap() {
        speed = targetSpeed; sweep = targetSweep; glow = targetGlow; track = targetTrack;
        scale = targetScale; depth = targetDepth; burst = 0f;
    }

    private void startTimer() {
        if (timer != null) return;
        timer = new TimeAnimator();
        timer.setTimeListener((animation, total, delta) -> step(delta));
        timer.start();
    }

    private void stopTimer() {
        if (timer == null) return;
        TimeAnimator old = timer;
        timer = null;
        old.setTimeListener(null);
        old.cancel();
    }

    private static float approach(float value, float target, float dt, float tau) {
        return target + (value - target) * (float) Math.exp(-dt / tau);
    }

    private static int alpha(float level) {
        return Math.round(255f * Math.max(0f, Math.min(1f, level)));
    }

    private static int fade(int color, float level) {
        return (color & 0x00FFFFFF) | (alpha(level * Color.alpha(color) / 255f) << 24);
    }

    private static int blend(int from, int to, float amount) {
        float keep = 1f - amount;
        return Color.argb(Color.alpha(from),
            Math.round(Color.red(from) * keep + Color.red(to) * amount),
            Math.round(Color.green(from) * keep + Color.green(to) * amount),
            Math.round(Color.blue(from) * keep + Color.blue(to) * amount));
    }
}
