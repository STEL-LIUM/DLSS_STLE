package com.dlssstyle.render;

import com.dlssstyle.DSConfig;
import com.dlssstyle.DLSSStyle;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Renders the world into a reduced-resolution target and upscales it into the main
 * target, leaving the GUI to draw at full resolution afterwards.
 *
 * <p>This is where the performance actually comes from: fragment work scales with pixel
 * count, so 70% scale is roughly half the pixels. Upscaling filters (FSR, NIS) only
 * decide how good the result looks - they do not make it faster.
 *
 * <p>Known interactions, stated rather than discovered later:
 * <ul>
 *   <li>Fabulous graphics keeps its own targets and may conflict; scaling is skipped there.</li>
 *   <li>Vanilla post effects (spectating a creeper/spider/enderman) bind the main target,
 *       so they process the upscaled image rather than the source.</li>
 * </ul>
 */
public final class DSRenderScale {
    /** Below this the scale is treated as off, avoiding a pointless full-size copy. */
    private static final double OFF_THRESHOLD = 0.995;

    private static TextureTarget target;
    private static int lastWidth;
    private static int lastHeight;
    private static boolean active;
    private static boolean warned;

    private DSRenderScale() {
    }

    /** True when the world should be, or is being, rendered at reduced resolution. */
    public static boolean shouldScale() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        // Fabulous manages its own chain of targets; do not fight it.
        if (minecraft.options.graphicsMode().get()
                == net.minecraft.client.GraphicsStatus.FABULOUS) {
            return false;
        }
        // Sodium-family chunk renderers used to need a standdown here: they
        // draw terrain outside vanilla's seams, so scaling only what vanilla
        // drew produced an x-ray world. The main-render-target proxy fixes
        // that at the source - Embeddium asks the same accessor everyone
        // else does - so this is now only a diagnostic note in the log.
        com.dlssstyle.compat.DSChunkRendererCompat.replacementChunkRenderer();
        // A dedicated Off bypasses the whole path. The resolve pass costs real time -
        // a five-tap shader over every output pixel, plus a full-resolution copy -
        // and above a certain framerate that outweighs the pixels saved.
        DSPreset preset = DSConfig.preset();
        if (!preset.enabled()) {
            return false;
        }
        // Under an Iris-family shader pack the proxy makes the PACK render
        // at reduced size - every gbuffer, deferred and composite pass
        // shades s^2 of the pixels, which is where the frames come from on
        // a raytraced setup.
        //
        // But the engagement decision is LATCHED for the pack session, and
        // that is not a nicety. Iris caches a depth-buffer VERSION per
        // render target to decide when to re-attach its depth attachment
        // (Blaze3dRenderTargetExt.iris$getDepthBufferVersion). Our proxy and
        // the real main target keep INDEPENDENT counters, so handing Iris a
        // different target mid-session lets the two collide - Iris then
        // keeps a depth attachment pointing at the other target's texture
        // and the pack renders garbage (reported live: "if I have the
        // presets off the shaders go crazy"). Once engaged we stay engaged
        // for the whole pack session; Off simply means scale 1.0, a
        // pass-through, and Iris only ever sees one target.
        if (shaderPackActive()) {
            // Engaging unconditionally was tried and reverted: at DLAA it
            // adds a full-size pass-through of the pack's output for zero
            // pixels saved, which measurably COST fps. Only engage when the
            // preset actually reduces pixels.
            if (packSessionEngaged == null) {
                packSessionEngaged = preset == DSPreset.QUALITY
                        || preset == DSPreset.BALANCED
                        || preset == DSPreset.PERFORMANCE
                        || preset == DSPreset.DYNAMIC;
                DLSSStyle.LOGGER.info("Shader session: {} (set a scaling preset then"
                        + " reload the pack to change this)",
                        packSessionEngaged ? "scaling engaged" : "not engaged");
            }
            return packSessionEngaged;
        }
        packSessionEngaged = null;
        // DLAA runs the pipeline AT native size on purpose - the temporal
        // pass is the product there, so the near-native bypass below must
        // not apply to it. Dynamic stays engaged at native too: its scale
        // crosses 1.0 routinely, and disengaging there would destroy and
        // recreate the framebuffer every time it grazes the ceiling (at
        // the ceiling it simply behaves as DLAA).
        if (preset == DSPreset.DLAA || preset == DSPreset.DYNAMIC) {
            return true;
        }
        return DSConfig.effectiveRenderScale() < OFF_THRESHOLD;
    }

    /**
     * Whether the proxy is engaged for the current shader-pack session.
     * null = undecided (next frame with a pack active decides). Cleared
     * whenever the pack is toggled or the world changes, which are the only
     * moments Iris rebuilds its pipeline anyway.
     */
    private static Boolean packSessionEngaged;

    public static void resetShaderSession() {
        packSessionEngaged = null;
    }

    /** The scale the current frame actually rendered at. */
    public static double currentScale() {
        if (target == null || lastWidth == 0) {
            return 1.0;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        return main.width > 0 ? (double) target.width / main.width : 1.0;
    }

    /** True while an Iris-family shader pack owns the world pipeline. */
    public static boolean shaderPackActive() {
        return com.dlssstyle.compat.DSIrisIntegration.shaderPackActive();
    }

    /** Resolves a preset to this frame's render scale. */
    public static double presetScale(DSPreset preset) {
        if (!preset.enabled()) {
            return 1.0;
        }
        // DLAA means native resolution; under a pack that is a pass-through
        // (the pack's own TAA is doing the anti-aliasing, ours is off).
        if (preset == DSPreset.DLAA) {
            return 1.0;
        }
        if (preset == DSPreset.DYNAMIC) {
            // Under a pack the discrete governor picks from the three preset
            // ratios and holds each for seconds; elsewhere the continuous one.
            return shaderPackActive() ? DSDynamicScale.packCurrent() : DSDynamicScale.current();
        }
        return preset.scale();
    }

    /** Preset switches invalidate the governor AND the temporal history. */
    public static void onPresetChanged(DSPreset preset) {
        DSDynamicScale.reset();
        invalidateHistory();
    }

    /**
     * True while this frame's world pass renders with a jittered projection.
     * Never under a shader pack: the pack's own TAA would resolve our jitter
     * as motion and smear the frame.
     */
    public static boolean jitterActive() {
        return active && !shaderPackActive();
    }

    // NOTE (2026-08-11): a viewport-region dynamic-res scheme (full-size
    // target, world in a corner region, scale = two ints) was tried here
    // to kill per-resize hitches - and shipped three live regressions in
    // one build (heavy stutter, GUI hover ghosting, severe blur). Reverted
    // to the proven resize-on-change target; region rendering only comes
    // back after it survives a buffer-dump session in dev.

    /** Pixels actually rendered, for reporting. */
    public static int scaledWidth() {
        return target == null ? 0 : target.width;
    }

    public static int scaledHeight() {
        return target == null ? 0 : target.height;
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Reroutes vanilla's mid-frame main-target rebinds into the scaled
     * target (2026-08-11, the bind-steal: LevelRenderer.renderLevel's
     * entity-outline branch runs EVERY frame - no glowing mob required -
     * and rebinds MAIN after opaque terrain. From that instant entities,
     * block entities, WATER, particles, weather and the first-person hand
     * all rendered at full res into main, and the resolve then painted the
     * scaled buffer over the lot. One steal, three symptoms: the invisible
     * hand, the invisible water, and a scale that only half-scaled).
     *
     * <p>bindWrite(true) on our target is load-bearing: the outline
     * target's clear() leaves a full-window viewport, so the scaled
     * viewport must be re-established. Glowing-outline overlays are lost
     * while scaling - documented trade, revisit if anyone glows.
     */
    public static void bindWorldWrite(RenderTarget requested, boolean setViewport) {
        // Compare against the proxy DIRECTLY: while the proxy is engaged,
        // getMainRenderTarget() returns it, so testing against that accessor
        // would be tautological. The job here is now only to force the
        // viewport - vanilla's outline branch clears with a full-window
        // viewport and then rebinds with setViewport=false, which would
        // leave every later draw rasterizing at window size into our
        // smaller target (the corner bug).
        if (active && target != null && requested == target) {
            target.bindWrite(true);
            return;
        }
        requested.bindWrite(setViewport);
    }

    // ── the main-render-target proxy ──
    // Armed for the duration of the world pass only, on the render thread
    // only, released in a finally. While armed, Minecraft.getMainRenderTarget()
    // hands out the scaled target, so EVERY renderer that asks - vanilla,
    // Embeddium, Iris - sizes its buffers and viewports from it.
    private static boolean proxyEngaged;

    public static boolean proxyEngaged() {
        return proxyEngaged;
    }

    public static RenderTarget proxy() {
        return target;
    }

    /** Releases the proxy. Safe to call at any time; must never be skipped. */
    public static void disengageProxy() {
        proxyEngaged = false;
    }

    /**
     * Binds the reduced-size target. Called immediately before the world pass.
     */
    public static void beginWorld() {
        active = false;
        proxyEngaged = false;
        frameCounter++;
        if (DSConfig.preset() == DSPreset.DYNAMIC
                && Minecraft.getInstance().level != null) {
            DSDynamicScale.tick(Minecraft.getInstance());
        }
        if (!shouldScale()) {
            // Hold no framebuffer while disabled. Keeping it would pin a full-size
            // colour and depth texture in VRAM for a feature that is switched off.
            if (target != null) {
                release();
            }
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        double scale = DSConfig.effectiveRenderScale();

        int width = Math.max(1, (int) Math.round(main.width * scale));
        int height = Math.max(1, (int) Math.round(main.height * scale));

        try {
            if (target == null) {
                target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
                target.setFilterMode(GL11.GL_LINEAR);
                lastWidth = width;
                lastHeight = height;
                logEngaged(width, height, main);
            } else if (width != lastWidth || height != lastHeight) {
                target.resize(width, height, Minecraft.ON_OSX);
                target.setFilterMode(GL11.GL_LINEAR);
                lastWidth = width;
                lastHeight = height;
                logEngaged(width, height, main);
            }

            target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            applyMipBias((float) (Math.log(scale) / Math.log(2.0)));
            // Jitter is the technique, not an option: without it, temporal
            // accumulation samples the same grid points every frame and can
            // only average, never reconstruct. It stays off under a shader
            // pack, whose own TAA would read it as motion.
            if (!shaderPackActive()) {
                DSJitter.advance(width, height, scale);
            } else {
                DSJitter.clear();
            }
            active = true;
            // Arm LAST: everything above needed the real main target.
            proxyEngaged = true;
        } catch (Throwable t) {
            // Never let a scaling problem stop the frame from rendering.
            if (!warned) {
                warned = true;
                DLSSStyle.LOGGER.error("Render scaling disabled after failure", t);
            }
            release();
            active = false;
        }
    }

    /**
     * The "is it even on?" answer, in the log: fires on every engage and
     * every resolution change, so a session's log shows exactly what
     * resolution the world rendered at and when it moved.
     */
    private static void logEngaged(int width, int height, RenderTarget main) {
        // Dynamic legitimately moves all session long, and menu boost fires
        // on every inventory visit; per-step lines are debug so a normal log
        // holds one line per deliberate change.
        if (DSConfig.preset() == DSPreset.DYNAMIC || DSConfig.menuBoosting()) {
            DLSSStyle.LOGGER.debug("Dynamic: world renders at {}x{} ({}% of {}x{})",
                    width, height,
                    Math.round(100.0 * width / Math.max(1, main.width)),
                    main.width, main.height);
            return;
        }
        DLSSStyle.LOGGER.info("{}: world renders at {}x{} ({}% of {}x{})",
                DSConfig.preset().label(), width, height,
                Math.round(100.0 * width / Math.max(1, main.width)),
                main.width, main.height);
    }

    /**
     * Upscales the world into the main target and rebinds it, so the GUI draws at full
     * resolution.
     */
    /** Cost of the resolve pass, averaged. Upscaling that costs more than it saves
     *  is worse than none, so it reports its own price. */
    private static double resolveCostMs;

    public static double resolveCostMs() {
        return resolveCostMs;
    }

    /**
     * What the pass actually costs, every 15s. Upscaling that costs more
     * than it saves is worse than none - and on a CPU-bound frame (vanilla
     * at 200+ fps) that is exactly what happens, because there is no GPU
     * time to give back. This line is how we tell, instead of guessing.
     */
    private static long lastCostReportNanos;

    private static void reportCost(RenderTarget main) {
        long now = System.nanoTime();
        if (now - lastCostReportNanos < 15_000_000_000L) {
            return;
        }
        lastCostReportNanos = now;
        double frameMs = Minecraft.getInstance().getFrameTimeNs() / 1_000_000.0;
        long saved = (long) main.width * main.height
                - (long) target.width * target.height;
        DLSSStyle.LOGGER.info(
                "Cost: resolve {}ms of {}ms frame ({}%) | {} pixels {} | {}",
                String.format("%.3f", resolveCostMs),
                String.format("%.2f", frameMs),
                frameMs > 0 ? Math.round(100.0 * resolveCostMs / frameMs) : 0,
                Math.abs(saved / 1000), saved > 0 ? "saved (k)" : "extra (k)",
                saved <= 0 ? "DLAA/native - pure cost unless GPU-bound"
                        : shaderPackActive() ? "shader pack passes scaled too"
                        : "vanilla - only pays when GPU-bound");
    }

    public static void endWorld() {
        // FIRST, unconditionally: from here on getMainRenderTarget() must
        // hand back the REAL screen buffer again - the resolve writes into
        // it, and the GUI is drawn into it right after us.
        proxyEngaged = false;
        if (!active || target == null) {
            return;
        }
        active = false;
        long resolveBegan = System.nanoTime();

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();

        try {
            target.unbindWrite();

            boolean shaded = false;
            boolean dumpThis = DSBufferDump.armed();
            if (dumpThis) {
                DSBufferDump.one(main, "main-before");
            }

            // Under a shader pack the resolve is SPATIAL only: the pack has
            // already run its own temporal accumulation over these pixels,
            // and stacking ours on top double-accumulates - ghosting on
            // every moving thing. Without a pack, the full temporal path.
            boolean temporal = !shaderPackActive();

            if (DSUpscaleShader.isAvailable()) {
                RenderTarget history = temporal ? ensureHistory(main) : null;

                // Screen and history bound together: the resolve writes both
                // in ONE pass. The old path drew to the screen and then
                // copied the whole frame into history - a second full-res
                // operation every frame, for pixels that were already there.
                boolean dual = history != null && bindDual(main, history);
                if (!dual) {
                    main.bindWrite(true);
                }
                shaded = DSUpscaleShader.draw(target, history, main.width, main.height,
                        effectiveSharpness(), historyBlend(), temporal);
                if (dual) {
                    unbindDual(main);
                }

                if (shaded && temporal && history != null) {
                    if (!dual) {
                        copyInto(main, history);
                    }
                    historyValid = true;
                }
            }

            if (!shaded) {
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
                GL30.glBlitFramebuffer(
                        0, 0, target.width, target.height,
                        0, 0, main.width, main.height,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            }

            main.bindWrite(true);
            // LOOK AT THE OUTPUT: a DUMP_BUFFERS marker file writes what the
            // pipeline actually produced this frame - the debugging lever for
            // the water/FSR reports that GL state reading could not settle.
            if (dumpThis) {
                DSBufferDump.dump(target, main, history);
            }
            // Roll unconditionally: the matrices describe THIS frame's camera
            // whether or not the resolve succeeded. Rolling only on success
            // leaves prev pointing at a camera two or more frames old, which
            // reads as a jump the next time it works.
            rollViewProj();
            resolveCostMs = resolveCostMs * 0.95 + ((System.nanoTime() - resolveBegan) / 1_000_000.0) * 0.05;
            reportCost(main);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                DLSSStyle.LOGGER.error("Render scale resolve failed", t);
            }
            main.bindWrite(true);
        }
    }

    /**
     * How much of the previous frame survives into this one. Higher resolves more
     * detail but holds errors longer; this is the single most important temporal
     * tuning knob. With sub-pixel jitter the history IS the detail store - the
     * 8-phase sequence needs several frames alive at once to integrate - so it
     * earns a longer memory; without jitter the old value stands.
     */
    private static final float HISTORY_BLEND = 0.55F;
    private static final float HISTORY_BLEND_JITTERED = 0.75F;

    private static float historyBlend() {
        // Jitter is always on outside shader packs, and the history IS the
        // detail store there - the phase sequence needs several frames alive
        // at once to integrate, so it earns the longer memory.
        return shaderPackActive() ? HISTORY_BLEND : HISTORY_BLEND_JITTERED;
    }

    // ── camera matrices for temporal reprojection (the custom-DLSS core) ──
    // Captured once per frame at the AFTER_CUTOUT event (which already has
    // pose + projection in hand); rolled to "previous" after each temporal
    // resolve consumes them.
    private static org.joml.Matrix4f currViewProj;
    private static org.joml.Matrix4f prevViewProj;

    // The camera POSITION, in doubles. renderLevel's PoseStack carries only
    // rotation (GameRenderer:1119-1122 applies roll/pitch/yaw and nothing
    // else), so a viewProj built from it has NO translation term - which is
    // why reprojection only ever tracked looking around, never walking.
    // Doubles are load-bearing: at |x| = 3e7 a float has lost whole blocks.
    private static org.joml.Vector3d currCam;
    private static org.joml.Vector3d prevCam;

    /** Called once per frame with projection * modelView and the camera position. */
    public static void noteViewProj(org.joml.Matrix4f viewProj, net.minecraft.world.phys.Vec3 camPos) {
        // The event hands us the JITTERED projection (the mixin nudged it).
        // Reprojection must be jitter-free or every frame's offset becomes
        // reprojection error - so strip it: T(-j) * (T(j)*P*MV) = P*MV.
        if (jitterActive()) {
            currViewProj = new org.joml.Matrix4f()
                    .translation(-DSJitter.ndcX(), -DSJitter.ndcY(), 0.0F)
                    .mul(viewProj);
        } else {
            // Defensive copy: the event's matrix is reused by the caller.
            currViewProj = new org.joml.Matrix4f(viewProj);
        }
        currCam = new org.joml.Vector3d(camPos.x, camPos.y, camPos.z);
    }

    /**
     * prevViewProj * inverse(currViewProj): carries an NDC+depth point from
     * this frame's camera to last frame's screen. Identity until two frames
     * of matrices exist - identity reprojection is exactly the old same-UV
     * behaviour, so the first frame is merely unimproved, never wrong.
     */
    public static org.joml.Matrix4f reprojectionMatrix() {
        if (currViewProj == null || prevViewProj == null
                || currCam == null || prevCam == null) {
            return new org.joml.Matrix4f();
        }
        // NDC(now) -> camera-relative(now) -> camera-relative(then) -> NDC(then).
        // The translation is the camera's movement since last frame: a static
        // point sat (curr - prev) further along in the previous camera's
        // space. Without this term the reprojection tracks rotation only,
        // and walking forward smears the whole screen.
        float dx = (float) (currCam.x - prevCam.x);
        float dy = (float) (currCam.y - prevCam.y);
        float dz = (float) (currCam.z - prevCam.z);
        return new org.joml.Matrix4f(prevViewProj)
                .mul(new org.joml.Matrix4f().translation(dx, dy, dz))
                .mul(new org.joml.Matrix4f(currViewProj).invert());
    }

    /**
     * Rotation-only twin, for pixels on the far plane. The sky, sun, moon and
     * stars write no depth (LevelRenderer:1789) so they sit at 1.0; giving
     * them the translation term would hand them infinite parallax and smear
     * the sky across the screen every time the player walks.
     */
    public static org.joml.Matrix4f reprojectionMatrixSky() {
        if (currViewProj == null || prevViewProj == null) {
            return new org.joml.Matrix4f();
        }
        return new org.joml.Matrix4f(prevViewProj)
                .mul(new org.joml.Matrix4f(currViewProj).invert());
    }

    /** The temporal resolve consumed this frame's matrices; roll them. */
    private static void rollViewProj() {
        if (currViewProj != null) {
            prevViewProj = new org.joml.Matrix4f(currViewProj);
        }
        if (currCam != null) {
            prevCam = new org.joml.Vector3d(currCam);
        }
    }

    /**
     * History is meaningless after a discontinuity - a fresh (or resized)
     * buffer, a re-engage, a preset change, a teleport. Blending against it
     * paints garbage over the world; because vanilla clears render targets
     * to WHITE (RenderTarget:34-36), that garbage is specifically a white
     * wash for several frames. One flag closes all of it: the resolve uses
     * pure current-frame output until a real history exists.
     */
    private static boolean historyValid;

    public static boolean historyValid() {
        return historyValid;
    }

    public static void invalidateHistory() {
        historyValid = false;
        prevViewProj = null;
        prevCam = null;
    }

    /** Test seam: rolls the matrices as a temporal resolve would. */
    public static void rollViewProjForTest() {
        rollViewProj();
    }

    // ── world-depth snapshot (the reprojection's actual input) ──
    // vanilla clears the depth buffer inside renderLevel, right before it
    // draws the first-person hand (GameRenderer:1131), so by the time our
    // resolve runs the scaled target's depth is 1.0 everywhere except the
    // hand. The reprojection was therefore reading a blank buffer - the
    // temporal path has been structurally dead. Snapshot depth at the last
    // moment it is still the world's.
    private static TextureTarget depthWorld;
    private static long snapshotFrame = -1;
    private static long frameCounter;

    /** Disabled with its mixin - see MixinGameRendererScale for why. */
    private static final boolean DEPTH_SNAPSHOT_ENABLED = false;

    public static void snapshotWorldDepth() {
        if (!DEPTH_SNAPSHOT_ENABLED || !active || target == null) {
            return;
        }
        try {
            if (depthWorld == null
                    || depthWorld.width != target.width
                    || depthWorld.height != target.height) {
                if (depthWorld != null) {
                    depthWorld.destroyBuffers();
                }
                depthWorld = new TextureTarget(target.width, target.height, true,
                        Minecraft.ON_OSX);
            }
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthWorld.frameBufferId);
            // NEAREST is mandatory: a depth blit may not filter.
            GL30.glBlitFramebuffer(0, 0, target.width, target.height,
                    0, 0, depthWorld.width, depthWorld.height,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            target.bindWrite(true);
            snapshotFrame = frameCounter;
        } catch (Throwable t) {
            // Reprojection degrades to rotation-only; never break the frame.
            snapshotFrame = -1;
        }
    }

    /** World depth for this frame, or the live target if the snapshot missed. */
    static int worldDepthTextureId() {
        if (depthWorld != null && snapshotFrame == frameCounter) {
            return depthWorld.getDepthTextureId();
        }
        return target.getDepthTextureId();
    }

    private static TextureTarget history;

    /** Full-resolution buffer holding the last resolved frame. */
    private static RenderTarget ensureHistory(RenderTarget main) {
        try {
            if (history == null) {
                history = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
                history.setFilterMode(GL11.GL_LINEAR);
                // A fresh target is cleared to WHITE by vanilla; blending
                // against it washes the world out for several frames.
                invalidateHistory();
            } else if (history.width != main.width || history.height != main.height) {
                history.resize(main.width, main.height, Minecraft.ON_OSX);
                history.setFilterMode(GL11.GL_LINEAR);
                invalidateHistory();
            }
            return history;
        } catch (Throwable t) {
            DLSSStyle.LOGGER.warn("Could not allocate temporal history buffer", t);
            return null;
        }
    }

    // ── one-pass resolve: screen + history attached to a single FBO ──
    private static int dualFbo;
    private static int dualMainTex;
    private static int dualHistTex;
    private static boolean dualBroken;

    /**
     * Attaches the screen's and the history's colour textures to our own
     * framebuffer so a single draw fills both. Returns false if the driver
     * will not have it, in which case the caller falls back to the old
     * draw-then-copy path.
     */
    /**
     * WITHDRAWN 2026-08-11: broke rendering in WINDOWED mode ("goes haywire,
     * literally can't see anything") while fullscreen was fine - the
     * signature of a size/attachment fault, not shader math. Cause: the
     * attachment cache below compares texture IDs, but GL reuses IDs after
     * a RenderTarget resize, so a reallocated texture keeps its old ID, the
     * cache reports "unchanged", and the framebuffer stays attached to a
     * texture of the wrong size. Re-enable only with attachments refreshed
     * on every size change AND a windowed resize test.
     */
    private static final boolean DUAL_FBO_ENABLED = false;

    private static boolean bindDual(RenderTarget main, RenderTarget history) {
        if (!DUAL_FBO_ENABLED || dualBroken) {
            return false;
        }
        try {
            int mainTex = main.getColorTextureId();
            int histTex = history.getColorTextureId();
            if (dualFbo == 0) {
                dualFbo = GL30.glGenFramebuffers();
                dualMainTex = 0;
                dualHistTex = 0;
            }
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, dualFbo);
            // Re-attach only when a texture actually changed: attachments
            // survive between frames, and re-attaching every frame is exactly
            // the kind of per-frame driver work this change exists to remove.
            if (mainTex != dualMainTex) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, mainTex, 0);
                dualMainTex = mainTex;
            }
            if (histTex != dualHistTex) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1,
                        GL11.GL_TEXTURE_2D, histTex, 0);
                dualHistTex = histTex;
            }
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                    != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("incomplete dual framebuffer");
            }
            GlStateManager._viewport(0, 0, main.width, main.height);
            return true;
        } catch (Throwable t) {
            dualBroken = true;
            DLSSStyle.LOGGER.warn("One-pass resolve unavailable, using draw-then-copy: {}",
                    t.toString());
            releaseDual();
            main.bindWrite(true);
            return false;
        }
    }

    private static void unbindDual(RenderTarget main) {
        main.bindWrite(true);
    }

    private static void releaseDual() {
        if (dualFbo != 0) {
            try {
                GL30.glDeleteFramebuffers(dualFbo);
            } catch (Throwable ignored) {
                // Shutting down.
            }
            dualFbo = 0;
            dualMainTex = 0;
            dualHistTex = 0;
        }
    }

    private static void copyInto(RenderTarget from, RenderTarget to) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, from.width, from.height, 0, 0, to.width, to.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
    }

    /**
     * Texture LOD bias currently applied to the block atlas. Rendering at reduced
     * resolution enlarges every pixel's footprint in texture space, so the GPU picks a
     * higher (blurrier, darker) mip level than a full-resolution render would - this is
     * why scaled rendering looked soft and dim. FSR's own integration guide prescribes
     * the antidote: bias sampling by log2(scale), which restores the full-resolution
     * mip choice. Applied only while scaling is active, zeroed the moment it is not.
     */
    private static float appliedBias;

    /**
     * Called after a resource reload recreated the atlas: the texture object holding the
     * bias is gone, so the cache must not claim the new one already has it.
     */
    public static void atlasReset() {
        appliedBias = 0.0F;
    }

    /**
     * DLSS raises sharpening as the scale drops (fewer real pixels need a
     * stronger reconstruction); same idea here. At Quality this is nearly
     * the configured value; at Ultra Performance roughly 1.5x it.
     */
    private static float effectiveSharpness() {
        double scale = DSConfig.effectiveRenderScale();
        double boosted = DSConfig.upscaleSharpness() * (1.0 + (1.0 - scale) * 0.8);
        return (float) Math.min(1.0, boosted);
    }

    /** GL_TEXTURE_MAX_ANISOTROPY_EXT - core-equivalent constant since GL4.6. */
    private static final int GL_TEXTURE_MAX_ANISOTROPY = 0x84FE;

    private static void applyMipBias(float bias) {
        if (Math.abs(bias - appliedBias) < 0.01F) {
            return;
        }
        try {
            var atlas = Minecraft.getInstance().getTextureManager()
                    .getTexture(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS);
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GlStateManager._bindTexture(atlas.getId());
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL14.GL_TEXTURE_LOD_BIAS, bias);
            // Anisotropic filtering rides along with the bias: the negative
            // LOD bias sharpens flat-on surfaces but grazing angles (ground
            // ahead of the player) still smear at reduced res - AF is the
            // fix, and vanilla never turns it on at all. Applied only while
            // scaling (bias != 0), restored to default when it releases.
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D,
                    GL_TEXTURE_MAX_ANISOTROPY, bias != 0.0F ? 4.0F : 1.0F);
            GlStateManager._bindTexture(previous);
            appliedBias = bias;
        } catch (Throwable t) {
            // A missing atlas or GL quirk must not take the frame down with it.
            appliedBias = bias;
        }
    }

    /** Drops the target, e.g. when scaling is turned off or the window changes. */
    public static void release() {
        proxyEngaged = false;
        applyMipBias(0.0F);
        invalidateHistory();
        releaseDual();
        if (depthWorld != null) {
            try {
                depthWorld.destroyBuffers();
            } catch (Throwable ignored) {
                // Shutting down.
            }
            depthWorld = null;
        }
        if (history != null) {
            try {
                history.destroyBuffers();
            } catch (Throwable ignored) {
                // Shutting down.
            }
            history = null;
        }
        if (target != null) {
            try {
                target.destroyBuffers();
            } catch (Throwable ignored) {
                // Shutting down; nothing useful to do.
            }
            target = null;
        }
        lastWidth = 0;
        lastHeight = 0;
    }
}
