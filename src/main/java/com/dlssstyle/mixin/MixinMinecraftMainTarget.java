package com.dlssstyle.mixin;

import com.dlssstyle.render.DSRenderScale;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The whole scaled-rendering architecture, in one hook.
 *
 * <p>Every renderer - vanilla, the Sodium family (Embeddium/Rubidium), and
 * Iris/Oculus shader packs - asks Minecraft for the screen buffer through
 * this one accessor, and sizes its own buffers and viewports from what it
 * gets back. Handing them a reduced-size target for the duration of the
 * world pass therefore scales EVERYTHING, including a shader pack's own
 * gbuffer/deferred/composite passes - which is where the frames come from
 * on a raytraced setup.
 *
 * <p>Replaces the old approach of binding our target ourselves, which only
 * caught draws that went through vanilla's seams: Embeddium's terrain went
 * around it and the world rendered as x-ray (2026-08-11).
 *
 * <p>The flag is armed for exactly one call - inside the world pass, on the
 * render thread - and released in a finally. A leaked flag would make
 * Minecraft resize OUR buffer instead of the real screen and strand the
 * display, so the release is not optional.
 */
@Mixin(Minecraft.class)
public class MixinMinecraftMainTarget {

    @Inject(method = "getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;",
            at = @At("HEAD"), cancellable = true)
    private void dlssstyle$proxyMainTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (RenderSystem.isOnRenderThread() && DSRenderScale.proxyEngaged()) {
            RenderTarget proxy = DSRenderScale.proxy();
            if (proxy != null) {
                cir.setReturnValue(proxy);
            }
        }
    }
}
