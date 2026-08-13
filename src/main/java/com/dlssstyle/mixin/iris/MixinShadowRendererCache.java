package com.dlssstyle.mixin.iris;

import com.dlssstyle.render.DSShadowCache;
import com.dlssstyle.render.DSShadowSnapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The shadow cache's execution layer, v2 - rebuilt 2026-08-13 after the
 * v1 approach was field-proven to strobe Complementary's volumetric light
 * and read as stutter (the whole sun wash flapped on/off, ~20 mean-luma
 * swings at ~1-2Hz, live-bisected by Aryan: flicker died the moment the
 * preset - and with it this cache - went Off).
 *
 * <p>What v1 got wrong, and v2's answers:
 * <ul>
 *   <li>v1 suppressed RenderSystem.clear calls in beginLevelRendering so
 *       the old map would survive - but a @Redirect there catches EVERY
 *       clear at that site, and skip frames inherited buffers Iris meant
 *       to wipe. v2 touches no clears: Iris wipes everything as stock and
 *       a skip frame REPAINTS the cached textures (DSShadowSnapshot,
 *       glCopyImageSubData - bandwidth, not rasterization).</li>
 *   <li>v1 cancelled renderShadows at HEAD, which also cancelled the
 *       composite stage Iris runs inside it - on Complementary that is
 *       SHADOWCOMP, the floodfill colored-light compute that needs a pass
 *       EVERY frame (the documented 1.1.1 lesson, half-unlearned: the
 *       standalone skip was removed but the HEAD cancel still starved it
 *       on skip frames). v2 invokes the composite explicitly on every
 *       skipped frame before cancelling.</li>
 *   <li>v1 froze the shadow matrix by handing back the SAME PoseStack
 *       instance every frame - anything downstream that mutates the stack
 *       corrupts every later frame. v2 hands out a fresh defensive copy
 *       per call.</li>
 * </ul>
 * Any failure in the snapshot machinery falls through to a stock shadow
 * render - the cache can only ever cost its own savings, never the frame.
 */
@Mixin(ShadowRenderer.class)
public class MixinShadowRendererCache {

    @Shadow(remap = false)
    @Final
    private ShadowRenderTargets targets;

    /**
     * The composite field is fetched by REFLECTION, not @Shadow: its TYPE
     * differs across Iris-family builds (CompositeRenderer in the dev
     * Oculus, ShadowCompositeRenderer in the installed Mekalus 1.8.0.1),
     * and a @Shadow descriptor mismatch fails the WHOLE mixin - which is
     * exactly what happened on first deploy (InvalidMixinException in the
     * live log, cache silently inert). Name-only lookup survives both.
     */
    private static java.lang.reflect.Field dlssstyle$compositeField;
    private static java.lang.reflect.Method dlssstyle$renderAll;
    private static boolean dlssstyle$compositeProbed;

    private void dlssstyle$runComposite() throws Exception {
        if (!dlssstyle$compositeProbed) {
            dlssstyle$compositeProbed = true;
            dlssstyle$compositeField =
                    ShadowRenderer.class.getDeclaredField("compositeRenderer");
            dlssstyle$compositeField.setAccessible(true);
        }
        if (dlssstyle$compositeField == null) {
            return;
        }
        Object composite = dlssstyle$compositeField.get(this);
        if (composite == null) {
            return;
        }
        if (dlssstyle$renderAll == null) {
            dlssstyle$renderAll = composite.getClass().getMethod("renderAll");
        }
        dlssstyle$renderAll.invoke(composite);
    }

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, remap = false)
    private void dlssstyle$maybeSkip(LevelRendererAccessor levelRenderer, Camera camera,
                                     CallbackInfo ci) {
        try {
            DSShadowCache.markAvailable();
            DSShadowCache.decide();
            if (DSShadowCache.skipping()
                    && DSShadowSnapshot.hasCapture(this.targets)
                    && DSShadowSnapshot.restore(this.targets)) {
                // The composite (SHADOWCOMP - e.g. Complementary's floodfill
                // colored light) is per-frame work that merely LIVES inside
                // the shadow pass; it runs even when the map is reused.
                dlssstyle$runComposite();
                ci.cancel();
                return;
            }
            // No valid snapshot: this frame renders for real (and recaptures).
            DSShadowCache.renderingInsteadOfSkipping();
        } catch (Throwable ignored) {
            // Render shadows normally.
        }
    }

    @Inject(method = "renderShadows", at = @At("RETURN"), remap = false)
    private void dlssstyle$capture(LevelRendererAccessor levelRenderer, Camera camera,
                                   CallbackInfo ci) {
        try {
            DSShadowSnapshot.capture(this.targets);
        } catch (Throwable ignored) {
            // Next frame renders stock; the snapshot marks itself broken.
        }
    }

    /** The frozen shadow matrix, snapshotted on real renders. */
    private static Matrix4f dlssstyle$cachedShadowPose;

    @Inject(method = "createShadowModelView", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void dlssstyle$frozenModelView(float sunPathRotation, float intervalSize,
                                                  CallbackInfoReturnable<PoseStack> cir) {
        try {
            if (DSShadowCache.skipping() && dlssstyle$cachedShadowPose != null) {
                // A FRESH stack per call: v1 handed out one shared instance,
                // and any downstream mutation of it poisoned every frame
                // that reused the map.
                PoseStack copy = new PoseStack();
                copy.mulPoseMatrix(new Matrix4f(dlssstyle$cachedShadowPose));
                cir.setReturnValue(copy);
            }
        } catch (Throwable ignored) {
            // Fall through to Iris's own matrix.
        }
    }

    @Inject(method = "createShadowModelView", at = @At("RETURN"), remap = false)
    private static void dlssstyle$captureModelView(float sunPathRotation, float intervalSize,
                                                   CallbackInfoReturnable<PoseStack> cir) {
        try {
            if (!DSShadowCache.skipping()) {
                dlssstyle$cachedShadowPose =
                        new Matrix4f(cir.getReturnValue().last().pose());
            }
        } catch (Throwable ignored) {
            // Keep whatever we had.
        }
    }
}
