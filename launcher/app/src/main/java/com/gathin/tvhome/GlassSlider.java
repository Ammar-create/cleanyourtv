package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Four-stop column picker for the remote: a frosted track, a filled run up to the thumb, and a glass
 * thumb that springs between stops. No blur anywhere; the material is a stack of gradients, a top
 * specular band and a 1dp rim, the same recipe as {@link GlassView} and the popup panels.
 *
 * <p>D-pad left/right moves exactly one stop. The thumb rides a {@link SpringAnimation} (stiffness
 * 380, damping 0.82) and stretches along its travel axis while it has speed, relaxing as it settles.
 * Focus lifts the thumb from dim glass to near-white and adds a ring, so it is obvious from across
 * a living room which control the remote is on.
 */
public final class GlassSlider extends View {

    /** Called after the user moves the thumb; never called by {@link #setValue(int)}. */
    public interface OnValueChanged {
        void onValueChanged(int index);
    }

    private static final int STOPS = 4;
    private static final String[] STOP_LABELS = {"4", "5", "6", "7"};

    /** Horizontal stretch at full travel speed, as a fraction of the thumb radius. */
    private static final float STRETCH_MAX = 0.16f;
    /** Velocity (dp/s) that counts as "full speed" for the stretch. */
    private static final float STRETCH_VELOCITY = 1800f;

    private final FloatPropertyCompat<GlassSlider> thumbProperty =
            new FloatPropertyCompat<GlassSlider>("glassSliderThumbX") {
                @Override
                public float getValue(GlassSlider slider) {
                    return slider.thumbX;
                }

                @Override
                public void setValue(GlassSlider slider, float x) {
                    slider.thumbX = x;
                    slider.invalidate();
                }
            };

    private final float density;
    private final float trackHeight;
    private final float thumbRadius;
    private final float trackInset;
    private final float labelSize;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbHotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF trackRect = new RectF();
    private final RectF thumbRect = new RectF();
    private final RectF glossRect = new RectF();

    private final SpringAnimation spring;
    private ValueAnimator focusAnimator;
    private OnValueChanged listener;

    private int value;
    private float thumbX;
    /** 0 at rest, 1 at full travel speed; drives the horizontal squash of the thumb. */
    private float stretch;
    /** 0 unfocused, 1 focused, animated so the material lifts instead of snapping. */
    private float focus;
    private boolean laidOut;

    public GlassSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        trackHeight = 8f * density;
        thumbRadius = 11f * density;
        trackInset = thumbRadius + 6f * density;
        labelSize = 13f * density;

        trackRim.setStyle(Paint.Style.STROKE);
        trackRim.setStrokeWidth(1f * density);
        trackRim.setColor(0x1FFFFFFF);

        thumbRim.setStyle(Paint.Style.STROKE);
        thumbRim.setStrokeWidth(1f * density);

        glossPaint.setColor(0x33FFFFFF);

        glowPaint.setStyle(Paint.Style.STROKE);

        Typeface face = null;
        try {
            face = getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException ignored) {
            // A stripped build without the font still gets readable numbers.
        }
        if (face == null) face = Typeface.DEFAULT_BOLD;

        labelPaint.setTypeface(face);
        labelPaint.setTextSize(labelSize);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(0x66FFFFFF);
        labelActivePaint.set(labelPaint);
        labelActivePaint.setColor(0xFFFFFFFF);

        setFocusable(true);
        setFocusableInTouchMode(false);
        setSoundEffectsEnabled(true);

        spring = new SpringAnimation(this, thumbProperty)
                .setSpring(new SpringForce().setStiffness(380f).setDampingRatio(0.82f));
        spring.addUpdateListener((animation, animatedValue, velocity) -> {
            float next = Math.min(1f, Math.abs(velocity) / (STRETCH_VELOCITY * density));
            if (Math.abs(next - stretch) > 0.004f) {
                stretch = next;
                invalidate();
            }
        });
    }

    /** Jumps the thumb (and the value) to a stop without animating or notifying the listener. */
    public void setValue(int index) {
        int next = Math.max(0, Math.min(STOPS - 1, index));
        if (next == value) return;
        value = next;
        if (laidOut) spring.animateToFinalPosition(xForStop(value));
        invalidate();
    }

    /** The current stop as an index, 0..3. Add 4 for the column count it stands for. */
    public int getValue() {
        return value;
    }

    public void setOnValueChanged(OnValueChanged changed) {
        listener = changed;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int wantedHeight = Math.round(76f * density);
        int wantedWidth = Math.round(240f * density);
        setMeasuredDimension(resolve(widthSpec, wantedWidth), resolve(heightSpec, wantedHeight));
    }

    private static int resolve(int spec, int wanted) {
        int mode = MeasureSpec.getMode(spec);
        int size = MeasureSpec.getSize(spec);
        if (mode == MeasureSpec.EXACTLY) return size;
        if (mode == MeasureSpec.AT_MOST) return Math.min(wanted, size);
        return wanted;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float centerY = h * 0.44f;
        trackRect.set(trackInset, centerY - trackHeight / 2f, w - trackInset, centerY + trackHeight / 2f);
        buildShaders(centerY);
        if (!laidOut) {
            laidOut = true;
            thumbX = xForStop(value);
        } else {
            // A resize (never expected in landscape-locked Home) must not strand the thumb.
            thumbX = Math.max(trackRect.left, Math.min(trackRect.right, thumbX));
        }
    }

    private void buildShaders(float centerY) {
        trackPaint.setShader(new LinearGradient(0f, trackRect.top, 0f, trackRect.bottom,
                0x38FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        fillPaint.setShader(new LinearGradient(0f, trackRect.top, 0f, trackRect.bottom,
                0x8CFFFFFF, 0x3DFFFFFF, Shader.TileMode.CLAMP));

        float top = centerY - thumbRadius;
        float bottom = centerY + thumbRadius;
        // Resting thumb: quiet, smoked glass. Focused thumb: the same surface lifted to near-white.
        thumbPaint.setShader(new LinearGradient(0f, top, 0f, bottom,
                0x59FFFFFF, 0x21FFFFFF, Shader.TileMode.CLAMP));
        thumbHotPaint.setShader(new LinearGradient(0f, top, 0f, bottom,
                new int[]{0xFFFFFFFF, 0xF7FAFCFE, 0xFFD2DAE3},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

        shadowPaint.setShader(new RadialGradient(0f, 0f, thumbRadius * 2.6f,
                0x4A000000, 0x00000000, Shader.TileMode.CLAMP));
    }

    private float xForStop(int index) {
        float span = trackRect.right - trackRect.left;
        if (span <= 0f) return trackRect.left;
        return trackRect.left + span * (index / (float) (STOPS - 1));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (trackRect.width() <= 0f) return;

        final float centerY = trackRect.centerY();
        final float cap = trackHeight / 2f;

        // Track: frosted bar with a hairline rim, plus a brighter rim once the row owns focus.
        canvas.drawRoundRect(trackRect, cap, cap, trackPaint);
        if (focus > 0.01f) {
            glowPaint.setStrokeWidth(1f * density);
            glowPaint.setColor(withAlpha(0x30FFFFFF, focus));
            canvas.drawRoundRect(trackRect, cap, cap, glowPaint);
        }
        canvas.drawRoundRect(trackRect, cap, cap, trackRim);

        // Filled run up to the thumb, kept at least one thumb wide so the left cap stays round.
        if (thumbX > trackRect.left + 0.5f) {
            float right = Math.max(thumbX, trackRect.left + trackHeight);
            canvas.drawRoundRect(trackRect.left, trackRect.top, right, trackRect.bottom,
                    cap, cap, fillPaint);
        }

        // Thumb: a soft contact shadow, the glass body, a specular gloss and the rim.
        canvas.save();
        canvas.translate(thumbX, centerY + 1.5f * density);
        canvas.drawCircle(0f, 0f, thumbRadius * 2.6f, shadowPaint);
        canvas.restore();

        canvas.save();
        canvas.translate(thumbX, centerY);
        canvas.scale(1f + STRETCH_MAX * stretch, 1f - 0.04f * stretch);
        canvas.translate(-thumbX, -centerY);

        thumbRect.set(thumbX - thumbRadius, centerY - thumbRadius,
                thumbX + thumbRadius, centerY + thumbRadius);
        canvas.drawOval(thumbRect, thumbPaint);
        if (focus > 0.01f) {
            thumbHotPaint.setAlpha((int) (255f * focus));
            canvas.drawOval(thumbRect, thumbHotPaint);
        }

        glossRect.set(thumbRect.left + thumbRadius * 0.26f,
                thumbRect.top + thumbRadius * 0.2f,
                thumbRect.right - thumbRadius * 0.26f,
                thumbRect.top + thumbRadius * 0.88f);
        // Solid fill, so the alpha is baked into the colour: setAlpha() would replace it, not scale it.
        glossPaint.setColor(withAlpha(0x33FFFFFF, 1f - 0.65f * focus));
        canvas.drawOval(glossRect, glossPaint);

        thumbRim.setColor(mix(0x5CFFFFFF, 0x2E000000, focus));
        canvas.drawOval(thumbRect, thumbRim);
        canvas.restore();

        // Focus ring stays circular while the body squashes, so the two read as separate layers.
        if (focus > 0.01f) {
            glowPaint.setStrokeWidth(2f * density);
            glowPaint.setColor(withAlpha(0xE6FFFFFF, focus));
            canvas.drawCircle(thumbX, centerY, thumbRadius + 5f * density, glowPaint);

            glowPaint.setStrokeWidth(1f * density);
            glowPaint.setColor(withAlpha(0x3DFFFFFF, focus));
            canvas.drawCircle(thumbX, centerY, thumbRadius + 9f * density, glowPaint);
        }

        // Stop numbers under the track, the active one solid white.
        float baseline = getHeight() - 7f * density;
        for (int i = 0; i < STOPS; i++) {
            canvas.drawText(STOP_LABELS[i], xForStop(i), baseline,
                    i == value ? labelActivePaint : labelPaint);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            move(rtl ? 1 : -1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            move(rtl ? -1 : 1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Moves one stop; a press at either end is swallowed so focus never leaves the slider. */
    private void move(int delta) {
        int next = Math.max(0, Math.min(STOPS - 1, value + delta));
        if (next == value) return;
        value = next;
        playSoundEffect(SoundEffectConstants.CLICK);
        if (laidOut) spring.animateToFinalPosition(xForStop(value));
        if (listener != null) listener.onValueChanged(value);
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocused) {
        super.onFocusChanged(gainFocus, direction, previouslyFocused);
        if (focusAnimator != null) focusAnimator.cancel();
        float target = gainFocus ? 1f : 0f;
        if (Math.abs(focus - target) < 0.001f) return;
        focusAnimator = ValueAnimator.ofFloat(focus, target);
        focusAnimator.setDuration(gainFocus ? 190 : 150);
        focusAnimator.setInterpolator(new DecelerateInterpolator(1.7f));
        focusAnimator.addUpdateListener(animator -> {
            focus = (float) animator.getAnimatedValue();
            invalidate();
        });
        focusAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (spring.isRunning()) spring.cancel();
        if (focusAnimator != null) {
            focusAnimator.cancel();
            focusAnimator = null;
        }
    }

    private static int withAlpha(int argb, float t) {
        int alpha = Math.round(Color.alpha(argb) * Math.max(0f, Math.min(1f, t)));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private static int mix(int from, int to, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * k);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * k);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * k);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k);
        return Color.argb(a, r, g, b);
    }
}
