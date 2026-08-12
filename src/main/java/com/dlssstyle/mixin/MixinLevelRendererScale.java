package com.dlssstyle.mixin;

import com.dlssstyle.render.DSRenderScale;
import com.dlssstyle.DSHooks;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the world render inside the scaled target for the WHOLE frame.
 *
 * <p>The bind-steal (found 2026-08-11 by a 12-agent source sweep after two
 * buffer dumps proved the hand never reached the scaled buffer):
 * {@code renderLevel}'s entity-outline branch runs every normal frame -
 * {@code shouldShowEntityOutlines()} is not gated on anything actually
 * glowing - and rebinds the MAIN target right after opaque terrain. From
 * there entities, block entities, water, particles, weather and the
 * first-person hand all drew at full resolution into main, and the resolve
 * then covered them with the scaled buffer. The redirect reroutes those
 * rebinds into the scaled target while scaling is active and passes them
 * through untouched otherwise (the two Fabulous-only sites can never see
 * scaling active - shouldScale() refuses Fabulous).
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRendererScale {

    @Redirect(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V"))
    private void dlssstyle$keepScaledBound(RenderTarget requested,
                                                  boolean setViewport) {
        DSHooks.fired(DSHooks.SCALE_BIND);
        DSRenderScale.bindWorldWrite(requested, setViewport);
    }
}
