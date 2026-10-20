package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Remote-friendly settings owned by the launcher; system settings stay one tap away. The window is
 * translucent (Theme.TvHome.Settings), so this page floats over Home while every control sits on its
 * own glass card. Rows are plain {@link TextView}s driven by {@link #refresh()}; the column count is
 * a {@link GlassSlider} instead of four chips.
 */
public final class LauncherSettingsActivity extends Activity {

    /** Entrance: cards fade and rise 12dp, 40ms apart, top to bottom. */
    private static final long ENTRANCE_MS = 320L;
    private static final long ENTRANCE_STAGGER_MS = 40L;
    private static final float ENTRANCE_RISE_DP = 12f;

    private SharedPreferences choices;
    private GlassSlider columnsSlider;
    private TextView columnsValue;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        // A theme may set any grid width; this picker only owns the 4..7 range it can show.
        columns = Math.max(4, Math.min(7, config.columns));
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        columnsSlider = findViewById(R.id.columnsSlider);
        columnsValue = findViewById(R.id.columnsValue);
        columnsSlider.setValue(columns - 4);
        columnsSlider.setOnValueChanged(index -> {
            int value = index + 4;
            if (columns == value) return;
            columns = value;
            choices.edit().putInt("columns", value).apply();
            setResult(RESULT_OK);
            refresh();
        });

        final TextView rotationChoice = findViewById(R.id.rotationChoice);
        final int[] mins = {0, 15, 60, 360};
        final String[] labels = {"Every visit", "15 min", "1 hour", "6 hours"};
        final SharedPreferences wp = getSharedPreferences("velvet_wallpaper", MODE_PRIVATE);
        final Runnable paint = new Runnable() {
            @Override public void run() {
                int cur = wp.getInt("interval_min", 0);
                String label = labels[0];
                for (int i = 0; i < mins.length; i++) if (mins[i] == cur) label = labels[i];
                rotationChoice.setText(getString(R.string.velvet_rotation) + ": " + label);
            }
        };
        paint.run();
        rotationChoice.setOnClickListener(v -> {
            int cur = wp.getInt("interval_min", 0);
            int idx = 0;
            for (int i = 0; i < mins.length; i++) if (mins[i] == cur) idx = i;
            wp.edit().putInt("interval_min", mins[(idx + 1) % mins.length]).apply();
            paint.run();
        });

        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").commit();
            Config current = Config.load(this);
            columns = Math.max(4, Math.min(7, current.columns));
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));

        refresh();
        columnsSlider.requestFocus();
        playEntrance(config);
    }

    /** Fade and rise each direct child of the column, 40ms apart. Off for reduced motion. */
    private void playEntrance(Config config) {
        if (!config.animations || config.reducedMotion) return;
        ViewGroup column = findViewById(R.id.settingsColumn);
        float rise = ENTRANCE_RISE_DP * getResources().getDisplayMetrics().density;
        for (int i = 0; i < column.getChildCount(); i++) {
            View part = column.getChildAt(i);
            part.setAlpha(0f);
            part.setTranslationY(rise);
            part.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(ENTRANCE_MS)
                    .setStartDelay(ENTRANCE_STAGGER_MS * i)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();
        }
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        columnsSlider.setValue(columns - 4);
        columnsValue.setText(getString(R.string.velvet_columns_value, columns));
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
