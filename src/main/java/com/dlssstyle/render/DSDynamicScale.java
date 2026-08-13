package com.dlssstyle.render;

import net.minecraft.client.Minecraft;

/**
 * Dynamic resolution: holds a target framerate by moving the render scale
 * instead of letting frames arrive late. The target is the player's fps
 * cap, or the monitor's refresh rate when uncapped - the two numbers a
 * player has already declared they want to hit.
 *
 * <p>Asymmetric steps (down fast, up slow) with a dead band between the
 * thresholds, so the scale settles instead of see-sawing around the
 * target. On top of that, streak hysteresis: a step is only taken after
 * the framerate has sat on the wrong side of its threshold for a run of
 * CONSECUTIVE frames, so a single hitch (chunk build, GC, autosave) can
 * never move the scale. Rapid scale oscillation is not merely cosmetic -
 * every step invalidates the temporal history (visible re-convergence
 * flicker) and resizes framebuffers (a load spike that itself dents fps,
 * feeding the oscillation). Hysteresis on the scale CHOICE only: this
 * class never sleeps, paces or times the frame loop.
 */
final class DSDynamicScale {
    /** Same floor as the preset ladder - below 1/2 the image pays too much. */
    private static final double MIN_SCALE = 0.5;
    private static final double MAX_SCALE = 1.0;
    /**
     * Milliseconds between adjustments. Time-based, not frame-based: a
     * 40-frame cadence at 240fps meant six framebuffer resizes a second
     * (proven in the first live session's log) - each one a reallocation
     * the frame pays for.
     */
    private static final long ADJUST_EVERY_MS = 750;

    private static double scale = 2.0 / 3.0;
    private static long lastFrameNanos;
    private static long lastAdjustNanos;
    private static double frameMsEma = -1.0;

    // ── streak hysteresis ──
    // Separate, asymmetric thresholds around the target with a dead band
    // between them: below (target - 3) counts toward stepping DOWN, above
    // (target + 8) counts toward stepping UP, anywhere between resets both
    // counters. The wide upward margin exists because a step up costs real
    // pixels and must not immediately push fps back under the shrink line.
    // A step fires only once the per-frame fps has been past its threshold
    // for STREAK_FRAMES consecutive frames - one frame inside the band
    // starts the count over.
    private static final int STREAK_FRAMES = 30;
    private static final double DOWN_MARGIN_FPS = 3.0;
    private static final double UP_MARGIN_FPS = 8.0;
    private static int downStreak;
    private static int upStreak;

    private DSDynamicScale() {
    }

    static double current() {
        return scale;
    }

    // ── shader-pack mode: rare, discrete steps ──
    // Under a pack every scale change rebuilds the pack's whole buffer set,
    // so the fast continuous governor above is not usable. This one snaps to
    // the three preset ratios and holds each for seconds - it still delivers
    // what makes Dynamic worth having (a flat frame-time line; fixed presets
    // let fps swing with scene load) while changing size rarely enough that
    // the rebuild cost is lost in the noise.
    // Same ratios as the fixed presets, including Quality's 0.660 rather
    // than an exact 2/3 - see DSPreset.QUALITY for the bloom-clamp reason.
    private static final double[] PACK_STEPS = {0.5, 0.58, 0.660};
    private static final long PACK_DWELL_NANOS = 5_000_000_000L;
    private static int packStep = PACK_STEPS.length - 1;
    private static long packLastChangeNanos;

    static double packCurrent() {
        return PACK_STEPS[packStep];
    }

    /** Called per world frame while Dynamic is active under a shader pack. */
    static void packTick(Minecraft minecraft, long now, double fps, int targetFps) {
        if (now - packLastChangeNanos < PACK_DWELL_NANOS) {
            return;
        }
        int wanted = packStep;
        if (fps < targetFps * 0.92 && packStep > 0) {
            wanted = packStep - 1;
        } else if (fps > targetFps * 1.25 && packStep < PACK_STEPS.length - 1) {
            // Wide upward margin: stepping up costs ~35% more pixels, so only
            // climb when there is clearly room, never on a marginal reading.
            wanted = packStep + 1;
        }
        if (wanted != packStep) {
            packStep = wanted;
            packLastChangeNanos = now;
            com.dlssstyle.DLSSStyle.LOGGER.info(
                    "Dynamic (shader): {} fps vs {} target -> scale {}",
                    Math.round(fps), targetFps, PACK_STEPS[packStep]);
        }
    }

    /** Called once per world frame while the Dynamic preset is active. */
    static void tick(Minecraft minecraft) {
        // Windows degrades timers for unfocused windows, so frame times
        // measured while the player is on another monitor are garbage -
        // and adjusting on garbage meant a resize hitch parade visible
        // from the other screen (reported live). Hold the scale and the
        // history until focus returns.
        if (!minecraft.isWindowActive()) {
            lastFrameNanos = 0;
            frameMsEma = -1.0;
            downStreak = 0;
            upStreak = 0;
            return;
        }
        long now = System.nanoTime();
        double frameMs = -1.0;
        if (lastFrameNanos != 0) {
            double ms = (now - lastFrameNanos) / 1_000_000.0;
            // A one-second frame is a world load or a freeze, not a signal.
            if (ms < 1000.0) {
                frameMsEma = frameMsEma < 0 ? ms : frameMsEma * 0.97 + ms * 0.03;
                frameMs = ms;
            }
        }
        lastFrameNanos = now;

        if (frameMsEma <= 0) {
            return;
        }

        int configured = com.dlssstyle.DSConfig.dynamicTargetFps();
        int limit = minecraft.options.framerateLimit().get();
        int targetFps = configured > 0 ? configured
                : limit < 260 ? limit
                : Math.max(30, minecraft.getWindow().getRefreshRate());

        // Streaks count EVERY frame; the 750ms cadence below only spaces the
        // steps themselves. Consecutive is meant literally: one frame inside
        // the dead band restarts the count, so only a sustained shortfall or
        // sustained headroom ever moves the scale.
        if (frameMs > 0) {
            double frameFps = 1000.0 / frameMs;
            if (frameFps < targetFps - DOWN_MARGIN_FPS) {
                downStreak++;
                upStreak = 0;
            } else if (frameFps > targetFps + UP_MARGIN_FPS) {
                upStreak++;
                downStreak = 0;
            } else {
                downStreak = 0;
                upStreak = 0;
            }
        }

        if (now - lastAdjustNanos < ADJUST_EVERY_MS * 1_000_000L) {
            return;
        }
        lastAdjustNanos = now;

        // Under a shader pack the discrete governor owns the scale.
        if (DSRenderScale.shaderPackActive()) {
            packTick(minecraft, now, 1000.0 / frameMsEma, targetFps);
            return;
        }

        // Asymmetric steps behind the streak gate. Streaks reset after a
        // step so the NEXT step needs its own full run of evidence - the
        // step's own cost (history invalidation, framebuffer resize) lands
        // in the frames right after it and must not be double-counted.
        if (downStreak >= STREAK_FRAMES) {
            scale = Math.max(MIN_SCALE, scale - 0.05);
            downStreak = 0;
            upStreak = 0;
        } else if (upStreak >= STREAK_FRAMES && scale < MAX_SCALE) {
            scale = Math.min(MAX_SCALE, scale + 0.03);
            downStreak = 0;
            upStreak = 0;
        }
    }

    /** Forget stale frame times across pauses and preset changes. */
    static void reset() {
        lastFrameNanos = 0;
        lastAdjustNanos = 0;
        frameMsEma = -1.0;
        downStreak = 0;
        upStreak = 0;
    }
}
