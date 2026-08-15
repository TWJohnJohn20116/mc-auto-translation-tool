package org.universaltranslator.core;

/** Dependency-free timing and color helpers shared by every settings-screen generation. */
public final class SettingsUiAnimation {
    public static final long OPEN_DURATION_NANOS = 420_000_000L;
    private static final long SWEEP_DURATION_NANOS = 2_800_000_000L;

    private SettingsUiAnimation() {
    }

    public static float openProgress(long startedAtNanos, long nowNanos) {
        if (nowNanos <= startedAtNanos) {
            return 0.0F;
        }
        float linear = Math.min(1.0F,
                (float) (nowNanos - startedAtNanos) / (float) OPEN_DURATION_NANOS);
        // Smoothstep avoids a hard start or stop while remaining deterministic across frame rates.
        return linear * linear * (3.0F - 2.0F * linear);
    }

    public static int openingOverlayAlpha(float progress) {
        float normalized = clamp(progress);
        return Math.round(150.0F * (1.0F - normalized));
    }

    public static int expandingHalfWidth(int finalHalfWidth, float progress) {
        if (finalHalfWidth <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(finalHalfWidth * clamp(progress)));
    }

    public static int sweepX(int left, int right, long nowNanos) {
        if (right <= left) {
            return left;
        }
        long phase = Math.floorMod(nowNanos, SWEEP_DURATION_NANOS);
        float progress = (float) phase / (float) SWEEP_DURATION_NANOS;
        return left + Math.round((right - left) * progress);
    }

    public static int pulseColor(long nowNanos) {
        double wave = (Math.sin(nowNanos / 380_000_000.0D) + 1.0D) * 0.5D;
        int green = 190 + (int) Math.round(wave * 45.0D);
        int blue = 215 + (int) Math.round(wave * 40.0D);
        return 0xFF000000 | (0x55 << 16) | (green << 8) | blue;
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
