package com.dlssstyle.mixin.iris;

import com.dlssstyle.render.DSShadowCache;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skips the shadow pass on frames where the previous shadow map is still
 * correct, and - just as importantly - freezes the shadow camera while it
 * does.
 *
 * <p>The frozen matrix is not optional. Iris's MatrixUniforms calls
 * createShadowModelView directly rather than reading the cached static, so
 * a skipped render with a live matrix would hand the pack a shadow
 * transform that no longer matches the texture it is sampling: shadows
 * would visibly slide off the geometry casting them.
 */
@Mixin(ShadowRenderer.class)
public class MixinShadowRendererCache {

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, remap = false)
    private void dlssstyle$maybeSkip(LevelRendererAccessor levelRenderer, Camera camera,
                                     CallbackInfo ci) {
        try {
            DSShadowCache.markAvailable();
            if (DSShadowCache.skipping()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Render shadows normally.
        }
    }

    /** The frozen shadow camera, held for exactly as long as the map is. */
    private static PoseStack dlssstyle$cachedModelView;

    @Inject(method = "createShadowModelView", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void dlssstyle$frozenModelView(float sunPathRotation, float intervalSize,
                                                  CallbackInfoReturnable<PoseStack> cir) {
        try {
            if (DSShadowCache.skipping() && dlssstyle$cachedModelView != null) {
                cir.setReturnValue(dlssstyle$cachedModelView);
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
                dlssstyle$cachedModelView = cir.getReturnValue();
            }
        } catch (Throwable ignored) {
            // Keep whatever we had.
        }
    }
}
