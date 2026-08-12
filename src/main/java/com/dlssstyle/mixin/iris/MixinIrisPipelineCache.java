package com.dlssstyle.mixin.iris;

import com.dlssstyle.render.DSShadowCache;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Decides at the start of level rendering whether this frame can reuse the
 * shadow map, and suppresses the clears that would otherwise wipe it.
 *
 * <p>Suppressing the clears is what makes skipping the render worth
 * anything: Iris clears the shadow depth buffer in beginLevelRendering,
 * before the shadow renderer runs. Cancel the render alone and the pack
 * samples a freshly-blanked map - no shadows at all, which looks far worse
 * than no optimisation.
 */
@Mixin(IrisRenderingPipeline.class)
public class MixinIrisPipelineCache {

    @Inject(method = "beginLevelRendering", at = @At("HEAD"), remap = false)
    private void dlssstyle$decide(CallbackInfo ci) {
        // Guarded because this body executes inside ANOTHER MOD's class
        // context. Anything that goes wrong reaching our classes from there
        // - a classloader boundary, a jar swapped under a running client -
        // must cost the shadow cache, never the frame. Learned the hard way
        // 2026-08-12: a ClassNotFoundException here crashed the game.
        try {
            DSShadowCache.decide();
        } catch (Throwable ignored) {
            // No cache this frame; Iris renders shadows exactly as normal.
        }
    }

    /**
     * The shadow depth clear. Redirected rather than cancelled wholesale so
     * every other clear in the method still happens exactly as Iris
     * intends - only the frames that reuse the map skip it.
     */
    @Redirect(method = "beginLevelRendering", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void dlssstyle$skipShadowClear(int mask, boolean onOsx) {
        boolean skip;
        try {
            skip = DSShadowCache.skipping();
        } catch (Throwable ignored) {
            skip = false;   // never skip a clear we are unsure about
        }
        if (!skip) {
            RenderSystem.clear(mask, onOsx);
        }
    }

    /**
     * The pack's "prepare" composite stage - atmospheric scattering and sky
     * lookup tables on most packs. These depend on the SUN, not the camera,
     * which makes them a better memoisation target than shadows: walking
     * around does not invalidate them at all. Skipped on the same frames the
     * shadow map is reused, so a pack that ties the two together stays
     * consistent.
     */
    @Redirect(method = "renderShadows", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
    private void dlssstyle$skipPrepare(net.irisshaders.iris.pipeline.CompositeRenderer renderer) {
        boolean skip;
        try {
            skip = DSShadowCache.skippingPrepare();
        } catch (Throwable ignored) {
            skip = false;
        }
        if (!skip) {
            renderer.renderAll();
        }
    }
}
