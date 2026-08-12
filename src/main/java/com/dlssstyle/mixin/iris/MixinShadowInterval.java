package com.dlssstyle.mixin.iris;

import com.dlssstyle.render.DSShadowCache;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces the shadow camera's snap grid to match the cache's cell size.
 *
 * <p>Iris already quantises the shadow modelview to a grid so that small
 * camera movements do not shift the shadow map. The grid size comes from
 * the pack: some set 2 blocks, and BSL declares none at all. That is the
 * hidden flaw in caching by camera cell - if Iris re-snaps on a finer grid
 * than our cell, the map we reuse was rendered from a slightly different
 * origin than the one the pack is now sampling with, and shadows sit
 * subtly wrong. Almost certainly what made the floor glitch when the skip
 * rate was pushed up.
 *
 * <p>Matching the two makes the reuse exact: inside one cell, at the same
 * sun angle, the map Iris would render is the one already in the texture.
 * It also lets the cell be large, which is what keeps the cache alive
 * while the player is moving - the case where fps was worst.
 *
 * <p>The cost is that the shadow frustum's origin can lag the camera by up
 * to one cell out of a ~256-block half-width, and near-field texels
 * coarsen slightly. Both are far below the threshold of notice.
 */
@Mixin(PackShadowDirectives.class)
public class MixinShadowInterval {

    @Inject(method = "getIntervalSize", at = @At("RETURN"), cancellable = true, remap = false)
    private void dlssstyle$forceInterval(CallbackInfoReturnable<Float> cir) {
        try {
            float wanted = (float) DSShadowCache.cellSize();
            if (cir.getReturnValue() == null || cir.getReturnValueF() < wanted) {
                cir.setReturnValue(wanted);
            }
        } catch (Throwable ignored) {
            // Leave the pack's own interval alone.
        }
    }
}
