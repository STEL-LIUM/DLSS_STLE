package com.dlssstyle.mixin.iris;

import com.dlssstyle.DLSSStyle;
import com.dlssstyle.DSConfig;
import com.dlssstyle.render.DSPreset;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales the pack's shadow map resolution with the scaling presets - the
 * same pixels-for-fps trade the presets already make, applied to the most
 * expensive pass a shader pack runs. 2048 at Quality becomes 1536; at
 * Performance, 1024 - a quarter of the shadow texels. DLAA and Off never
 * touch it: their promise is the pack exactly as authored.
 *
 * <p>Resolution is read at pipeline creation, so preset switches apply it
 * live through the existing automatic pack reload. Composes with the
 * shadow cache: the frames that DO render shadows render cheaper ones.
 */
@Mixin(PackShadowDirectives.class)
public class MixinShadowResolution {

    private static int dlssstyle$lastLogged;

    @Inject(method = "getResolution", at = @At("RETURN"),
            cancellable = true, remap = false)
    private void dlssstyle$scaleResolution(CallbackInfoReturnable<Integer> cir) {
        try {
            double scale = dlssstyle$scaleForPreset();
            int original = cir.getReturnValueI();
            if (scale >= 0.999 || original <= 256) {
                return;
            }
            // Multiples of 128 keep drivers and pack math comfortable; the
            // floor keeps a degenerate pack config from vanishing entirely.
            int scaled = Math.max(256,
                    Math.round(original * (float) scale / 128.0F) * 128);
            if (scaled >= original) {
                return;
            }
            if (dlssstyle$lastLogged != scaled) {
                dlssstyle$lastLogged = scaled;
                DLSSStyle.LOGGER.info("Shadow resolution: {} -> {} ({} preset)",
                        original, scaled, DSConfig.preset().label());
            }
            cir.setReturnValue(scaled);
        } catch (Throwable ignored) {
            // The pack keeps its own resolution.
        }
    }

    private static double dlssstyle$scaleForPreset() {
        double configured = DSConfig.shadowScale();
        if (configured > 0.0) {
            return configured;
        }
        DSPreset preset = DSConfig.preset();
        return switch (preset) {
            case QUALITY, DYNAMIC -> 0.75;  // DYNAMIC: fixed conservative cut -
                                            // shadow res cannot follow a live
                                            // governor without pipeline rebuilds
            case BALANCED -> 0.66;
            case PERFORMANCE, ULTRA_PERFORMANCE -> 0.5;
            default -> 1.0;     // DLAA, OFF: the pack exactly as authored
        };
    }
}
