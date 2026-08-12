package com.dlssstyle.render;

import com.dlssstyle.DLSSStyle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * Owns the upscaling shader and the fullscreen pass that applies it.
 *
 * <p>Every failure path falls back to the plain framebuffer blit. A shader that will not
 * compile, or a driver that rejects the pass, must degrade to a slightly softer image -
 * never to a black screen.
 */
public final class DSUpscaleShader {
    private static ShaderInstance shader;
    private static boolean failed;

    private DSUpscaleShader() {
    }

    public static void setShader(ShaderInstance instance) {
        shader = instance;
        failed = false;
        DLSSStyle.LOGGER.info("Upscale shader loaded");
    }

    public static boolean isAvailable() {
        return shader != null && !failed;
    }

    /**
     * Draws the source target into the bound framebuffer through the upscaling shader.
     *
     * @param history previous resolved frame, or null for a spatial-only pass
     * @return false if the caller should fall back to a plain blit
     */
    public static boolean draw(RenderTarget source, RenderTarget history,
                               int outWidth, int outHeight,
                               float sharpness, float historyBlend, boolean temporal) {
        if (!isAvailable()) {
            return false;
        }

        try {
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);

            // 2026-08-11, proven by before/after buffer dumps and verified
            // line-by-line against the mapped sources: the pass was painting
            // an ENTITY SKIN across the frame. Root cause is in vanilla's own
            // helper - VertexBuffer._drawWithShader OVERWRITES Sampler0..11
            // from RenderSystem.getShaderTexture(i) immediately before
            // applying, so setSampler() here was clobbered by whatever
            // texture the last entity RenderType left in slot 0 (its
            // teardown never clears it). Raw glBindTexture pre-binds cannot
            // win either - ShaderInstance.clear() leaves the cache at
            // binding 0, so apply() executes a REAL rebind of the stale id
            // afterwards; and raw binds desync GlStateManager's caches for
            // everyone after us. The ONLY correct channel is the one the
            // helper reads: RenderSystem.setShaderTexture. Same trap family
            // as the identity-matrix fix - that helper stamps matrices AND
            // samplers. Slots are restored below or the GUI samples US.
            int src = source.getColorTextureId();
            int hist = history != null ? history.getColorTextureId() : src;
            int savedTex0 = RenderSystem.getShaderTexture(0);
            int savedTex1 = RenderSystem.getShaderTexture(1);
            int savedTex2 = RenderSystem.getShaderTexture(2);
            int savedTex3 = RenderSystem.getShaderTexture(3);
            RenderSystem.setShaderTexture(0, src);
            RenderSystem.setShaderTexture(1, hist);
            if (temporal) {
                // Slot 2 = WORLD depth, snapshotted before vanilla's hand
                // clear; slot 3 = the live depth, which after that clear is
                // 1.0 everywhere EXCEPT the first-person hand and screen
                // effects - an exact, free mask for view-locked pixels.
                RenderSystem.setShaderTexture(2, DSRenderScale.worldDepthTextureId());
                RenderSystem.setShaderTexture(3, source.getDepthTextureId());
                if (shader.getUniform("ReprojMat") != null) {
                    shader.getUniform("ReprojMat").set(
                            DSRenderScale.reprojectionMatrix());
                }
                if (shader.getUniform("ReprojMatSky") != null) {
                    shader.getUniform("ReprojMatSky").set(
                            DSRenderScale.reprojectionMatrixSky());
                }
                if (shader.getUniform("HistoryValid") != null) {
                    shader.getUniform("HistoryValid").set(
                            DSRenderScale.historyValid() ? 1.0F : 0.0F);
                }
            }

            if (shader.getUniform("InSize") != null) {
                shader.getUniform("InSize").set((float) source.width, (float) source.height);
            }
            // OutSize was declared but never read by the fragment shader, so the
            // GL compiler eliminated it and ShaderInstance warned on every load.
            // Removed from the .fsh, the .json and here together.
            if (shader.getUniform("Sharpness") != null) {
                shader.getUniform("Sharpness").set(sharpness);
            }
            if (shader.getUniform("HistoryBlend") != null) {
                shader.getUniform("HistoryBlend").set(temporal ? historyBlend : 0.0F);
            }
            if (shader.getUniform("KernelBias") != null) {
                // FSR2's ComputeMaxKernelWeight: hold the reconstruction
                // footprint at a constant ~2 OUTPUT pixels whatever the
                // scale, or Performance turns to mush.
                double scale = DSRenderScale.currentScale();
                shader.getUniform("KernelBias").set(
                        (float) Math.min(1.99, 1.0 / Math.max(0.1, scale)));
            }
            if (shader.getUniform("Jitter") != null) {
                // This frame's sub-pixel offset in source-UV units, so the
                // resolve can sample the current frame back onto the fixed
                // output grid ("unjitter"). Zero whenever jitter is off.
                shader.getUniform("Jitter").set(DSJitter.uvX(), DSJitter.uvY());
            }

            RenderSystem.setShader(() -> shader);

            // drawWithShader is the only draw call that actually BINDS the shader -
            // setShader alone just stores it, and the previous plain draw() here ran
            // with whatever stale program was left bound, producing nothing in 0.01 ms.
            // drawWithShader also stamps the current matrices into the shader, so both
            // are pushed to identity for the duration: the quad is already in NDC.
            org.joml.Matrix4f savedProjection = new org.joml.Matrix4f(
                    RenderSystem.getProjectionMatrix());
            com.mojang.blaze3d.vertex.VertexSorting savedSorting =
                    RenderSystem.getVertexSorting();
            RenderSystem.setProjectionMatrix(new org.joml.Matrix4f(),
                    com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
            com.mojang.blaze3d.vertex.PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            modelView.setIdentity();
            RenderSystem.applyModelViewMatrix();
            try {
                BufferBuilder buffer = Tesselator.getInstance().getBuilder();
                buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                buffer.vertex(-1.0, -1.0, 0.0).uv(0.0F, 0.0F).endVertex();
                buffer.vertex(1.0, -1.0, 0.0).uv(1.0F, 0.0F).endVertex();
                buffer.vertex(1.0, 1.0, 0.0).uv(1.0F, 1.0F).endVertex();
                buffer.vertex(-1.0, 1.0, 0.0).uv(0.0F, 1.0F).endVertex();
                BufferUploader.drawWithShader(buffer.end());
            } finally {
                modelView.popPose();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
                // Put back whatever the frame had in the slots the helper
                // reads, so the GUI pass after us is not sampling OUR world.
                RenderSystem.setShaderTexture(0, savedTex0);
                RenderSystem.setShaderTexture(1, savedTex1);
                RenderSystem.setShaderTexture(2, savedTex2);
                RenderSystem.setShaderTexture(3, savedTex3);
            }

            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            return true;
        } catch (Throwable t) {
            // One failure disables the shader path for the session rather than throwing
            // an exception every single frame.
            failed = true;
            DLSSStyle.LOGGER.error("Upscale shader failed, falling back to blit", t);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            return false;
        }
    }

    public static ResourceLocation shaderId() {
        return new ResourceLocation(DLSSStyle.MODID, "dlss_upscale");
    }
}
