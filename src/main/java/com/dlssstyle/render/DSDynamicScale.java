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
 * target.
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
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNanos != 0) {
            double ms = (now - lastFrameNanos) / 1_000_000.0;
            // A one-second frame is a world load or a freeze, not a signal.
            if (ms < 1000.0) {
                frameMsEma = frameMsEma < 0 ? ms : frameMsEma * 0.97 + ms * 0.03;
            }
        }
        lastFrameNanos = now;

        if (frameMsEma <= 0 || now - lastAdjustNanos < ADJUST_EVERY_MS * 1_000_000L) {
            return;
        }
        lastAdjustNanos = now;

        int configured = com.dlssstyle.DSConfig.dynamicTargetFps();
        int limit = minecraft.options.framerateLimit().get();
        int targetFps = configured > 0 ? configured
                : limit < 260 ? limit
                : Math.max(30, minecraft.getWindow().getRefreshRate());
        double fps = 1000.0 / frameMsEma;

        // Under a shader pack the discrete governor owns the scale.
        if (DSRenderScale.shaderPackActive()) {
            packTick(minecraft, now, fps, targetFps);
            return;
        }

        // Wide dead band (96%..110%): the first live session hunted between
        // 92% and 99% scale because ordinary fps noise crossed both of the
        // old thresholds. Growth is also gated on a full-step margin so the
        // step itself cannot push fps back under the shrink line.
        if (fps < targetFps * 0.96) {
            scale = Math.max(MIN_SCALE, scale - 0.05);
        } else if (fps > targetFps * 1.10 && scale < MAX_SCALE) {
            scale = Math.min(MAX_SCALE, scale + 0.03);
        }
    }

    /** Forget stale frame times across pauses and preset changes. */
    static void reset() {
        lastFrameNanos = 0;
        lastAdjustNanos = 0;
        frameMsEma = -1.0;
    }
}
