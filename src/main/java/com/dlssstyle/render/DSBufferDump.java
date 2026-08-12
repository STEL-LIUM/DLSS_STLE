package com.dlssstyle.render;

import com.dlssstyle.DLSSStyle;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dumps the scaled-rendering buffers to PNG on demand - the LOOK AT THE
 * OUTPUT lever for a pipeline whose bugs (water missing under bilinear,
 * FSR rendering black with a scrambled ghost) cannot be reasoned out of
 * GL state from the code alone.
 *
 * <p>Trigger: a {@code DUMP_BUFFERS} marker file in the game directory.
 * The next resolve writes {@code logs/buffers/<stamp>-source.png} (the
 * reduced-size world render), {@code -resolved.png} (the main target after
 * the upscale pass) and {@code -history.png} (the temporal buffer, when one
 * exists), then deletes the marker - one dump per touch, no keybind
 * plumbing, and the file can be created remotely while someone else plays.
 * Same shape as the VANILLA_TEST kill switch: a file, not a config, so it
 * works mid-session.
 */
public final class DSBufferDump {
    private DSBufferDump() {
    }

    /** True when the marker exists; consumes it. Called once per resolve. */
    public static boolean armed() {
        try {
            Path marker = Path.of("DUMP_BUFFERS");
            if (!Files.exists(marker)) {
                marker = Path.of("run", "DUMP_BUFFERS");
            }
            if (Files.exists(marker)) {
                Files.delete(marker);
                return true;
            }
        } catch (Exception ignored) {
            // A locked marker just means no dump this frame.
        }
        return false;
    }

    /** Writes each non-null target as a PNG. Never throws into the frame. */
    public static void dump(RenderTarget source, RenderTarget resolved,
                            RenderTarget history) {
        String stamp = String.valueOf(System.currentTimeMillis() / 1000L % 100000L);
        write(source, stamp + "-source");
        write(resolved, stamp + "-resolved");
        write(history, stamp + "-history");
        // The depth pair is what makes the reprojection falsifiable:
        // -depthWorld must show recognisable terrain (it read as blank white
        // until 2026-08-11, which is exactly how the temporal path stayed
        // dead for weeks), and -depthLive must be white EXCEPT a hand
        // silhouette, which is the view-locked mask.
        writeDepth(DSRenderScale.worldDepthTextureId(), source, stamp + "-depthWorld");
        writeDepth(source == null ? 0 : source.getDepthTextureId(), source,
                stamp + "-depthLive");
        DLSSStyle.LOGGER.info("Buffer dump written: logs/buffers/{}-*.png", stamp);
    }

    /**
     * Depth as a contrast-stretched grayscale PNG. Raw depth is so close to
     * 1.0 across a normal scene that a linear map looks uniformly white -
     * the reason a blank depth buffer and a working one look identical
     * unless the range is stretched. Min/max are measured per dump and
     * logged, so "all 1.0" is unmistakable.
     */
    private static void writeDepth(int textureId, RenderTarget size, String name) {
        if (textureId == 0 || size == null) {
            return;
        }
        try {
            int w = size.width;
            int h = size.height;
            java.nio.FloatBuffer depth = ByteBuffer
                    .allocateDirect(w * h * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asFloatBuffer();
            GlStateManager._bindTexture(textureId);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT,
                    GL11.GL_FLOAT, depth);
            GlStateManager._bindTexture(0);

            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (int i = 0; i < w * h; i++) {
                float d = depth.get(i);
                if (d < min) {
                    min = d;
                }
                if (d > max) {
                    max = d;
                }
            }
            float span = Math.max(1.0e-6F, max - min);

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                int srcRow = (h - 1 - y) * w;
                for (int x = 0; x < w; x++) {
                    float d = depth.get(srcRow + x);
                    int v = Math.round(255.0F * (1.0F - (d - min) / span));
                    v = Math.max(0, Math.min(255, v));
                    img.setRGB(x, y, (v << 16) | (v << 8) | v);
                }
            }
            File dir = new File("logs/buffers");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            ImageIO.write(img, "png", new File(dir, name + ".png"));
            DLSSStyle.LOGGER.info("  {}: depth range {} .. {}{}", name, min, max,
                    span <= 1.0e-6F ? "  <-- FLAT, no depth information" : "");
        } catch (Throwable t) {
            DLSSStyle.LOGGER.warn("Depth dump of {} failed: {}", name, t.toString());
        }
    }

    /** One labelled capture, for before/after bisection around a pass. */
    public static void one(RenderTarget target, String label) {
        String stamp = String.valueOf(System.currentTimeMillis() / 1000L % 100000L);
        write(target, stamp + "-" + label);
        DLSSStyle.LOGGER.info("Buffer dump written: logs/buffers/{}-{}.png",
                stamp, label);
    }

    private static void write(RenderTarget target, String name) {
        if (target == null) {
            return;
        }
        try {
            int w = target.width;
            int h = target.height;
            ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                int srcRow = (h - 1 - y) * w * 4;     // GL reads bottom-up
                for (int x = 0; x < w; x++) {
                    int i = srcRow + x * 4;
                    int r = pixels.get(i) & 0xFF;
                    int g = pixels.get(i + 1) & 0xFF;
                    int b = pixels.get(i + 2) & 0xFF;
                    img.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }
            File dir = new File("logs/buffers");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            ImageIO.write(img, "png", new File(dir, name + ".png"));
        } catch (Throwable t) {
            DLSSStyle.LOGGER.warn("Buffer dump of {} failed: {}", name, t.toString());
        }
    }
}
