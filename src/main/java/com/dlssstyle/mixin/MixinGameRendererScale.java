package com.dlssstyle.mixin;

import com.dlssstyle.render.DSRenderScale;
import com.dlssstyle.DSHooks;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wraps the world pass so it renders into a reduced-size target.
 *
 * <p>Vanilla's own structure gives a clean seam: {@code render} calls {@code renderLevel}
 * and only afterwards rebinds the main target for the GUI. Binding around that call means
 * the world scales while the interface stays sharp at full resolution.
 */
@Mixin(GameRenderer.class)
public class MixinGameRendererScale {

    @Inject(
            method = "render(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;"
                            + "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
                    shift = At.Shift.BEFORE))
    private void dlssstyle$beforeLevel(float partialTick, long nanoTime,
                                              boolean renderLevel, CallbackInfo ci) {
        DSHooks.fired(DSHooks.RENDER_SCALE);
        DSRenderScale.beginWorld();
    }

    @Inject(
            method = "render(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;"
                            + "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
                    shift = At.Shift.AFTER))
    private void dlssstyle$afterLevel(float partialTick, long nanoTime,
                                             boolean renderLevel, CallbackInfo ci) {
        DSRenderScale.endWorld();
    }

    /**
     * Belt and braces on the proxy flag. If renderLevel ever throws, or a
     * mod cancels it, the AFTER inject never runs - and a leaked proxy makes
     * Minecraft resize OUR buffer instead of the screen. Clearing it at the
     * head of every frame bounds the damage to a single frame.
     */
    @Inject(method = "render(FJZ)V", at = @At("HEAD"))
    private void dlssstyle$frameHeadSafety(float partialTick, long nanoTime,
                                           boolean renderLevel, CallbackInfo ci) {
        DSRenderScale.disengageProxy();
    }

    /**
     * Captures world depth at the last instant it still exists.
     *
     * <p>Inside renderLevel, vanilla clears the depth buffer immediately
     * before drawing the first-person hand (GameRenderer:1131 - the only
     * RenderSystem.clear in the method). Our resolve runs after that, so
     * the depth it read for reprojection was 1.0 everywhere: the temporal
     * path had no depth input at all. Redirecting the clear lets us copy
     * depth out first and then perform vanilla's clear unchanged.
     */
    // WITHDRAWN 2026-08-11: shipping this made the world invisible on
    // Aryan's machine with no shader pack loaded. The mechanism is not yet
    // understood - suspects are the mid-frame framebuffer creation and the
    // read/draw rebind around the depth blit - so it goes back to the dev
    // client with buffer dumps before it goes anywhere near a play session.
    // With no snapshot, depth reads 1.0 everywhere, every pixel takes the
    // far-plane path, and reprojection degrades to the previously-shipped
    // rotation-only behaviour. Restore by re-enabling this redirect AND
    // the body of DSRenderScale.snapshotWorldDepth().
    //
    // @Redirect(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
    //         at = @At(value = "INVOKE",
    //                 target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    // private void dlssstyle$snapshotDepth(int mask, boolean onOsx) {
    //     DSRenderScale.snapshotWorldDepth();
    //     com.mojang.blaze3d.systems.RenderSystem.clear(mask, onOsx);
    // }

    /**
     * Sub-pixel jitter: nudges the projection by a fraction of a scaled
     * pixel each frame (clip-space translation, the standard TAA form).
     * Only while the scaled world pass is active, so menus, panoramas and
     * item icons never wobble; every call within one frame (world, hand)
     * gets the same offset, keeping the frame self-consistent.
     */
    @Inject(method = "getProjectionMatrix(D)Lorg/joml/Matrix4f;",
            at = @At("RETURN"), cancellable = true)
    private void dlssstyle$jitterProjection(double fov,
                                            CallbackInfoReturnable<Matrix4f> cir) {
        if (DSRenderScale.jitterActive()) {
            cir.setReturnValue(new Matrix4f()
                    .translation(com.dlssstyle.render.DSJitter.ndcX(),
                                 com.dlssstyle.render.DSJitter.ndcY(), 0.0F)
                    .mul(cir.getReturnValue()));
        }
    }
}
