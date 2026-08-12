package com.dlssstyle.mixin;

import com.dlssstyle.DSFramerateCeiling;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clamps the value vanilla's OWN limiter runs with. The number changes, the
 * clock does not - no pacer, no second timing source. This also converts
 * "Unlimited" into a real limit in fullscreen, which matters because vanilla
 * skips its limiter entirely at 260 and that is exactly how framerate ends
 * up past the top of a VRR panel's range.
 */
@Mixin(Minecraft.class)
public class MixinFramerateCeiling {

    @Inject(method = "getFramerateLimit()I", at = @At("RETURN"), cancellable = true)
    private void dlssstyle$vrrCeiling(CallbackInfoReturnable<Integer> cir) {
        int clamped = DSFramerateCeiling.clamp(cir.getReturnValue());
        if (clamped != cir.getReturnValue()) {
            cir.setReturnValue(clamped);
        }
    }
}
