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
 * Four-stop column picker for the remote: a thin frosted track, a filled run up to the thumb, and a
 * soft, low, wide glass pill that springs between stops. No blur anywhere; the material is a stack
 * of gradients, a sheen band and a 1dp rim, the same recipe as {@link GlassView} and the popup
 * panels.
 *
 * <p>The thumb is sized like a media slider's scrubber rather than a fat knob: 86x24dp at a 4dp
 * track, fully rounded ends, a gradient body over a soft contact shadow. The track is inset by half
 * a thumb so the pill never overhangs the view, and the outer stop numbers are drawn just outside
 * the track's ends, below the travel lane, so the thumb can never cover or clip them.
 *
 * <p>D-pad left/right moves exactly one stop. The thumb rides a {@link SpringAnimation} (stiffness
 * 380, damping 0.82) and stretches a little along its travel axis while it has speed, relaxing as
 * it settles. Focus lifts the thumb from dim glass to near-white, brightens its rim and adds a soft
 * halo, so it is obvious from across a living room which control the remote is on.
 */
public final class GlassSlider extends View {

    /** Called after the user moves the thumb; never called by {@link #setValue(int)}. */
    public interface OnValueChanged {
        void onValueChanged(int index);
    }

    private static final int STOPS = 4;
    private static final String[] STOP_LABELS = {"4", "5", "6", "7"};

    /** Thin frosted line, as in the mockup. */
    private static final float TRACK_HEIGHT_DP = 4f;
    /** The scrubber pill: wide, low, fully rounded. */
    private static final float THUMB_WIDTH_DP = 86f;
    private static final float THUMB_HEIGHT_DP = 24f;
    /** Gap between a thumb parked at a stop and the view's own edge (room for the halo). */
    private static final float THUMB_TRAVEL_GAP_DP = 6f;
    /** How far outside the track's ends the first and last stop numbers sit. */
    private static final float LABEL_GAP_DP = 10f;

    /** Horizontal stretch at full travel speed, as a fraction of the thumb width. */
    private static final float STRETCH_MAX = 0.10f;
    /** Velocity (dp/s) that counts as "full speed" for the stretch. */
    private static final float STRETCH_VELOCITY = 1600f;

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
    private final float thumbWidth;
    private final float thumbHeight;
    private final float trackInset;
    private final float labelGap;
    private final float labelSize;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF trackRect = new RectF();
    /** Thumb body in its own local space: centred on (0,0), so the sheen and rim share the space. */
    private final RectF thumbShape = new RectF();
    private final RectF sheenShape = new RectF();
    private final RectF ringShape = new RectF();

    private final SpringAnimation spring;
    private ValueAnimator focusAnimator;
    private OnValueChanged listener;

    private int value;
    private float thumbX;
    /** 0 at rest, 1 at full travel speed; drives the horizontal stretch of the pill. */
    private float stretch;
    /** 0 unfocused, 1 focused, animated so the material lifts instead of snapping. */
    private float focus;
    private boolean laidOut;

    public GlassSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        trackHeight = TRACK_HEIGHT_DP * density;
        thumbWidth = THUMB_WIDTH_DP * density;
        thumbHeight = THUMB_HEIGHT_DP * density;
        // Half a thumb on each side, plus a hair of breathing room for the focus halo.
        trackInset = thumbWidth / 2f + THUMB_TRAVEL_GAP_DP * density;
        labelGap = LABEL_GAP_DP * density;
        labelSize = 13f * density;

        trackRim.setStyle(Paint.Style.STROKE);
        trackRim.setStrokeWidth(1f * density);
        trackRim.setColor(0x1FFFFFFF);

        thumbRim.setStyle(Paint.Style.STROKE);
        thumbRim.setStrokeWidth(1f * density);

        ringPaint.setStyle(Paint.Style.STROKE);

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
        // The track owns the upper lane; the stop numbers get a lane of their own underneath, so a
        // pill parked on a stop can never sit on top of a number.
        float centerY = h * 0.43f;
        trackRect.set(trackInset, centerY - trackHeight / 2f,
                w - trackInset, centerY + trackHeight / 2f);
        buildShaders();
        if (!laidOut) {
            laidOut = true;
            thumbX = xForStop(value);
        } else {
            // A resize (never expected in landscape-locked Home) must not strand the thumb.
            thumbX = Math.max(trackRect.left, Math.min(trackRect.right, thumbX));
        }
    }

    private void buildShaders() {
        trackPaint.setShader(new LinearGradient(0f, trackRect.top, 0f, trackRect.bottom,
                0x2EFFFFFF, 0x0FFFFFFF, Shader.TileMode.CLAMP));
        fillPaint.setShader(new LinearGradient(0f, trackRect.top, 0f, trackRect.bottom,
                0x8CFFFFFF, 0x3AFFFFFF, Shader.TileMode.CLAMP));

        // Thumb shaders live in the pill's own space: centred on (0,0), drawn after translating.
        float top = -thumbHeight / 2f;
        float bottom = thumbHeight / 2f;
        // Resting thumb: quiet, smoked glass. The alpha is baked into the gradient stops, never
        // applied with setAlpha(), which would replace a solid colour's alpha instead of scaling it.
        thumbPaint.setShader(new LinearGradient(0f, top, 0f, bottom,
                new int[]{0x3CFFFFFF, 0x2CFFFFFF, 0x1CFFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

        float sheenTop = top + 1.5f * density;
        float sheenBottom = sheenTop + thumbHeight * 0.46f;
        sheenPaint.setShader(new LinearGradient(0f, sheenTop, 0f, sheenBottom,
                0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));

        shadowPaint.setShader(new RadialGradient(0f, 0f, thumbHeight * 0.95f,
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

        // Track: a thin frosted line with a hairline rim, plus a brighter rim once the row owns focus.
        canvas.drawRoundRect(trackRect, cap, cap, trackPaint);
        if (focus > 0.01f) {
            ringPaint.setStrokeWidth(1f * density);
            ringPaint.setColor(withAlpha(0x30FFFFFF, focus));
            canvas.drawRoundRect(trackRect, cap, cap, ringPaint);
        }
        canvas.drawRoundRect(trackRect, cap, cap, trackRim);

        // Filled run up to the thumb, kept at least one track-height wide so the left cap stays round.
        if (thumbX > trackRect.left + 0.5f) {
            float right = Math.min(Math.max(thumbX, trackRect.left + trackHeight), trackRect.right);
            canvas.drawRoundRect(trackRect.left, trackRect.top, right, trackRect.bottom,
                    cap, cap, fillPaint);
        }

        final float halfW = thumbWidth / 2f;
        final float halfH = thumbHeight / 2f;
        final float stretchX = 1f + STRETCH_MAX * stretch;
        final float stretchY = 1f - 0.035f * stretch;

        // Contact shadow: a soft ellipse sitting just under the pill, squashed on Y.
        canvas.save();
        canvas.translate(thumbX, centerY + 6f * density);
        canvas.scale(1.9f, 0.75f);
        canvas.drawCircle(0f, 0f, thumbHeight * 0.95f, shadowPaint);
        canvas.restore();

        // The pill itself, in its own space so the stretch never touches the shadow or the halo.
        canvas.save();
        canvas.translate(thumbX, centerY);
        canvas.scale(stretchX, stretchY);

        thumbShape.set(-halfW, -halfH, halfW, halfH);
        canvas.drawRoundRect(thumbShape, halfH, halfH, thumbPaint);

        // Focus lift: solid white with a computed alpha, so it blends over the resting gradient
        // instead of punching a hole through to the wallpaper.
        if (focus > 0.01f) {
            hotPaint.setColor(withAlpha(0xFFFFFFFF, 0.92f * focus));
            canvas.drawRoundRect(thumbShape, halfH, halfH, hotPaint);
        }

        // Subtle top sheen: one soft band across the upper half, fading to nothing before the bottom.
        float sheenInset = halfH * 0.55f;
        sheenShape.set(-halfW + sheenInset, -halfH + 1.5f * density,
                halfW - sheenInset, -halfH + 1.5f * density + thumbHeight * 0.46f);
        canvas.drawRoundRect(sheenShape, sheenShape.height() / 2f, sheenShape.height() / 2f,
                sheenPaint);

        // 1dp rim, brighter as the pill lifts.
        thumbRim.setColor(mix(0x38FFFFFF, 0x8CFFFFFF, focus));
        canvas.drawRoundRect(thumbShape, halfH, halfH, thumbRim);
        canvas.restore();

        // Halo: follows the pill, not a circle, so the two layers read as one object.
        if (focus > 0.01f) {
            float pad = 3f * density;
            float hw = halfW * stretchX + pad;
            float hh = halfH * stretchY + pad;
            ringShape.set(thumbX - hw, centerY - hh, thumbX + hw, centerY + hh);
            ringPaint.setStrokeWidth(1.5f * density);
            ringPaint.setColor(withAlpha(0xA8FFFFFF, focus));
            canvas.drawRoundRect(ringShape, hh, hh, ringPaint);

            hw += 4f * density;
            hh += 4f * density;
            ringShape.set(thumbX - hw, centerY - hh, thumbX + hw, centerY + hh);
            ringPaint.setStrokeWidth(1f * density);
            ringPaint.setColor(withAlpha(0x24FFFFFF, focus));
            canvas.drawRoundRect(ringShape, hh, hh, ringPaint);
        }

        // Stop numbers in their own lane below the travel band. The first and last sit just outside
        // the track's ends and are clamped to the view, so they can never clip on a narrow width.
        float baseline = getHeight() - 7f * density;
        for (int i = 0; i < STOPS; i++) {
            Paint paint = i == value ? labelActivePaint : labelPaint;
            float half = paint.measureText(STOP_LABELS[i]) / 2f;
            float x;
            if (i == 0) {
                x = Math.max(half + 2f * density, trackRect.left - labelGap);
            } else if (i == STOPS - 1) {
                x = Math.min(getWidth() - half - 2f * density, trackRect.right + labelGap);
            } else {
                x = xForStop(i);
            }
            canvas.drawText(STOP_LABELS[i], x, baseline, paint);
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
