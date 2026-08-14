package com.dlssstyle.render;

import com.dlssstyle.DLSSStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Shadow memoisation - the resolution-INDEPENDENT win.
 *
 * <p>Iris redraws the sun's shadow map from scratch every frame. But that
 * map depends on only three things: the sun's angle, where the shadow
 * camera sits, and whether any blocks changed. In Minecraft the sun moves
 * ~0.005 degrees per frame at 60fps, blocks change only when someone
 * places or breaks one, and Iris already snaps the shadow camera to a
 * grid. So the identical picture is being redrawn dozens of times in a
 * row. At a pack's HIGH shadow setting (2048^2) that pass rasterizes
 * several times as many pixels as the entire world pass, plus a second
 * full submission of the world's geometry.
 *
 * <p>Unlike everything else in this mod, skipping it costs the same
 * whatever the render resolution - so it pays at DLAA exactly as much as
 * at Performance, which is the point.
 *
 * <p>Conservative by construction: a bounded number of consecutive skips,
 * so any staleness self-heals within a fraction of a second; and any
 * doubt at all re-renders. A shadow map that is one frame old is
 * invisible; one that is a second old is not.
 */
public final class DSShadowCache {
    /**
     * Hard ceiling on how STALE the map may get, in real time rather than
     * frames. Perception works in milliseconds: 3 frames is 9ms at 320fps
     * but 50ms at 60fps, so a frame count is either wastefully strict when
     * the game is fast or genuinely visible when it is slow. 25ms is half a
     * tick - an entity shadow can lag by at most that, which is under the
     * threshold where the eye reads it as detached.
     *
     * <p>This is the safety net for every source of staleness no validity
     * key here can see, entity shadows above all.
     */
    // Measured live 2026-08-12: 3 frames = 75% skips, clean. 25ms = 86%,
    // and the floor visibly glitched (a stale map under a moving camera
    // detaches from what casts it). The frame cap is the primary control
    // because it sets the ratio exactly - 4 skips per render is 80% - while
    // the time cap bounds staleness when frames are long.
    private static final long MAX_STALE_NANOS = 16_000_000L;

    /** 4 skips then 1 render = exactly 80% of shadow passes eliminated. */
    private static final int MAX_SKIPS = 4;

    private static long lastRenderNanos;

    /**
     * Camera cell size in blocks. MixinShadowInterval forces Iris's own
     * shadow snap grid to this same value, which is what makes reuse exact
     * rather than approximate - and is why the cell can be this large.
     *
     * <p>Sized for MOVEMENT, the case where fps was worst: at 8 blocks a
     * walking player invalidated every ~1.5s and a sprinting one twice as
     * often. At 16 the cache survives ordinary movement, which is most of
     * playing.
     */
    private static final double CELL = 16.0;

    public static double cellSize() {
        return CELL;
    }

    /** Sun quantisation: skip only while the sun has barely moved. */
    private static final float SUN_EPSILON = 0.0015F;

    private static boolean available;
    private static boolean skipping;
    /** Sky/atmosphere LUTs depend on the SUN only - tracked separately. */
    private static boolean skippingPrepare;
    private static float lastPrepareSun = Float.NaN;
    private static int consecutiveSkips;

    private static long cellX = Long.MIN_VALUE;
    private static long cellY;
    private static long cellZ;
    private static Vec3 lastCamPos;
    private static float lastCamYRot;
    private static float lastCamXRot;
    private static float lastSunAngle = Float.NaN;
    private static String lastDimension = "";

    private static long framesRendered;
    private static long framesSkipped;
    private static long lastReportNanos;

    private DSShadowCache() {
    }

    /** Set by the Iris mixins when they successfully apply. */
    public static void markAvailable() {
        if (!available) {
            available = true;
            DLSSStyle.LOGGER.info("Shadow cache armed - the shadow pass will be reused"
                    + " across frames when the sun, the camera cell and the world are unchanged");
        }
    }

    /** True while this frame reuses the previous shadow map. */
    public static boolean skipping() {
        return skipping;
    }

    /**
     * IDEA 2 - the prepare stage decoupled from the shadow map.
     *
     * <p>A pack's sky and atmospheric-scattering tables depend on the sun
     * and nothing else: not the camera, not the world. Gating them on the
     * shadow decision therefore threw away most of their reuse, because
     * walking invalidates shadows while leaving the sky identical. Tracked
     * on its own they skip far more often - and unlike shadows there is no
     * staleness ceiling to respect, since the only input is the sun.
     */
    public static boolean skippingPrepare() {
        return skippingPrepare;
    }

    /**
     * Decides once per frame, before Iris begins level rendering, whether
     * this frame's shadow pass can be skipped.
     */
    public static void decide() {
        skipping = false;
        skippingPrepare = false;
        // Gated on the mod's own preset, not just on a shader pack being
        // present. Two reasons, both Aryan's: a feature that cannot be
        // switched off cannot be measured against a baseline, and "Off"
        // should mean the mod is doing nothing - the caches are driven by
        // Iris mixins that never look at the preset, so without this check
        // the biggest win in the mod kept running with the preset Off.
        if (!available || !DSConfigBridge.shadowCacheEnabled()
                || !com.dlssstyle.DSConfig.preset().enabled()) {
            reset();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            reset();
            return;
        }
        try {
            float sunNow = minecraft.level.getSunAngle(1.0F);
            // IDEA 2: the sky tables ride the sun alone.
            skippingPrepare = !Float.isNaN(lastPrepareSun)
                    && Math.abs(sunNow - lastPrepareSun) < SUN_EPSILON;
            if (!skippingPrepare) {
                lastPrepareSun = sunNow;
            }

            // IDEA 3 - nobody is looking. A menu covers the world and an
            // unfocused window is not being watched at all; in both cases
            // the shadow map can simply be held. This is the case Aryan
            // hits constantly, working on his second monitor while the game
            // runs.
            if (minecraft.screen != null || !minecraft.isWindowActive()) {
                skipping = true;
                skippingPrepare = true;
                framesSkipped++;
                report();
                return;
            }

            long now = System.nanoTime();
            if (consecutiveSkips >= maxSkips(minecraft)
                    || now - lastRenderNanos >= MAX_STALE_NANOS) {
                renderThisFrame();
                return;
            }
            // The sun. A pack's shadows follow it exactly, so any visible
            // movement invalidates - but at 20 minutes per day it barely
            // moves between frames, which is the whole opportunity.
            float sun = minecraft.level.getSunAngle(1.0F);
            String dimension = minecraft.level.dimension().location().toString();

            // The camera cell. Iris snaps the shadow modelview to a grid, so
            // within one cell the map it would draw is unchanged.
            Vec3 pos = minecraft.gameRenderer.getMainCamera().getPosition();
            long cx = Math.floorDiv((long) Math.floor(pos.x), (long) CELL);
            long cy = Math.floorDiv((long) Math.floor(pos.y), (long) CELL);
            long cz = Math.floorDiv((long) Math.floor(pos.z), (long) CELL);

            // THE MOTION GATE (1.2.7, field-reported: "when looking around
            // and walking can see the ground flicker"). While the camera
            // moves, fresh and reused shadow frames alternate at the
            // refresh cadence and packs read the sub-block offset as ground
            // shimmer - too subtle for burst metrics, obvious to eyes. So
            // skipping is EARNED only by stillness: any translation or
            // look-around renders shadows every frame, exactly stock, and
            // the moment the camera settles the skips resume. Standing,
            // building, menus and AFK - most of real play - still bank the
            // ~78% pass savings.
            float yRot = minecraft.gameRenderer.getMainCamera().getYRot();
            float xRot = minecraft.gameRenderer.getMainCamera().getXRot();
            boolean still = lastCamPos != null
                    && pos.distanceToSqr(lastCamPos) < 1.0e-6
                    && Math.abs(yRot - lastCamYRot) < 0.02F
                    && Math.abs(xRot - lastCamXRot) < 0.02F;
            lastCamPos = pos;
            lastCamYRot = yRot;
            lastCamXRot = xRot;

            boolean same = still
                    && cx == cellX && cy == cellY && cz == cellZ
                    && dimension.equals(lastDimension)
                    && !Float.isNaN(lastSunAngle)
                    && Math.abs(sun - lastSunAngle) < SUN_EPSILON
                    && !DSWorldRevision.changedSince();

            // Fully enclosed from the sky? Then nothing in view can receive a
            // sun or moon shadow, and the whole pass is wasted every frame -
            // not merely re-drawable. Unlike the cache this is a GATE: it
            // holds indefinitely, because being underground does not go
            // stale. Sampled generously (+-16 blocks) so a window, a doorway
            // or a cave mouth anywhere nearby cancels it.
            if (skyIsSealed(minecraft)) {
                skipping = true;
                framesSkipped++;
                report();
                return;
            }

            if (same) {
                skipping = true;
                consecutiveSkips++;
                framesSkipped++;
            } else {
                cellX = cx;
                cellY = cy;
                cellZ = cz;
                lastSunAngle = sun;
                lastDimension = dimension;
                DSWorldRevision.consume();
                renderThisFrame();
            }
            report();
        } catch (Throwable t) {
            // Any doubt renders. A missed skip costs performance; a wrong
            // skip costs correctness.
            reset();
        }
    }

    /**
     * IDEA 4 - a ceiling that follows the sun's elevation.
     *
     * <p>The sun's angular speed is constant, but what shadows do with it
     * is not: near the horizon a degree of elevation swings shadow length
     * enormously, while around noon it barely moves them. So the map can be
     * held far longer at midday than at dawn - the same staleness budget
     * buys several times more skips when it is cheap to spend.
     */
    private static int maxSkips(Minecraft minecraft) {
        try {
            // getTimeOfDay maps a day to 0..1; 0.25 is noon, 0.0/0.5 horizon.
            float t = minecraft.level.getTimeOfDay(1.0F);
            float fromHorizon = Math.abs(0.25F - (t % 0.5F)) * 4.0F;  // 0 at horizon, 1 at peak
            return MAX_SKIPS + Math.round(fromHorizon * MAX_SKIPS);   // 4..8
        } catch (Throwable t) {
            return MAX_SKIPS;
        }
    }

    private static void renderThisFrame() {
        skipping = false;
        consecutiveSkips = 0;
        lastRenderNanos = System.nanoTime();
        framesRendered++;
    }

    /**
     * The mixin could not honour a skip (no valid snapshot yet, or the
     * copy failed): this frame renders for real, and the matrix freeze
     * must not apply to a frame that is drawing a fresh map.
     */
    public static void renderingInsteadOfSkipping() {
        if (skipping) {
            renderThisFrame();
        }
    }

    private static void reset() {
        skipping = false;
        consecutiveSkips = 0;
        cellX = Long.MIN_VALUE;
        lastSunAngle = Float.NaN;
    }

    /** Invalidate from outside - block change, dimension change, teleport. */
    public static void invalidate() {
        reset();
    }

    private static void report() {
        long now = System.nanoTime();
        if (now - lastReportNanos < 30_000_000_000L) {
            return;
        }
        lastReportNanos = now;
        long total = framesRendered + framesSkipped;
        if (total > 0) {
            DLSSStyle.LOGGER.info("Shadow cache: {}% of shadow passes skipped ({} of {})",
                    Math.round(100.0 * framesSkipped / total), framesSkipped, total);
        }
        framesRendered = 0;
        framesSkipped = 0;
    }

    /** True when no sampled block within 16 blocks can see the sky. */
    private static boolean skyIsSealed(Minecraft minecraft) {
        try {
            Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();
            net.minecraft.core.BlockPos.MutableBlockPos probe =
                    new net.minecraft.core.BlockPos.MutableBlockPos();
            for (int dx = -16; dx <= 16; dx += 16) {
                for (int dz = -16; dz <= 16; dz += 16) {
                    for (int dy = 0; dy <= 16; dy += 16) {
                        probe.set((int) eye.x + dx, (int) eye.y + dy, (int) eye.z + dz);
                        if (!minecraft.level.isLoaded(probe)) {
                            return false;   // unknown chunk: assume open sky
                        }
                        if (minecraft.level.getBrightness(
                                net.minecraft.world.level.LightLayer.SKY, probe) > 0) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
