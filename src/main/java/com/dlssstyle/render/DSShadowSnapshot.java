package com.dlssstyle.render;

import com.dlssstyle.DLSSStyle;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * GPU-side copies of the pack's shadow textures - the execution half of the
 * shadow cache, v2.
 *
 * <p>The v1 cache made skipping work by SUPPRESSING Iris's buffer clears so
 * the old map survived into the next frame. Field-measured 2026-08-13 on
 * Complementary Reimagined: the whole volumetric sun wash strobed on/off
 * (~18-22 mean-luma swings) and the alternating heavy/light frames read as
 * stutter - a redirect on RenderSystem.clear inside beginLevelRendering
 * catches every clear at that site, not just the shadow one, so skip
 * frames inherited uncleared state they were never meant to see. v2
 * inverts the approach: Iris clears everything exactly as stock, and skip
 * frames REPAINT the cached shadow textures over the fresh clear with
 * glCopyImageSubData. A 2048x2048 depth copy is ~16MB of pure bandwidth -
 * a fraction of a millisecond - against the several milliseconds of
 * re-rasterizing the world into the shadow map.
 *
 * <p>Source texture ids come from Iris's ShadowRenderTargets via
 * reflection (unobfuscated, remap-free names); width, height and internal
 * format come from GL itself, so the clones always match whatever the pack
 * configured. Any surprise - a missing method, an incompatible format, a
 * driver without OpenGL 4.3 - marks the snapshot broken and the cache
 * simply never skips, which is stock behaviour.
 */
public final class DSShadowSnapshot {
    /** One cached texture: the source id it mirrors and our clone. */
    private static final class Clone {
        int source;
        int copy;
        int width;
        int height;
        int internalFormat;
    }

    private static final List<Clone> clones = new ArrayList<>();
    private static Object sourcesFrom;
    private static boolean broken;
    private static boolean loggedArmed;

    private static Method getDepthTexture;
    private static Method getDepthTextureNoTranslucents;
    private static Method getColorTextureId;
    private static Method getTextureId;
    private static boolean methodsProbed;

    private DSShadowSnapshot() {
    }

    /** True when a captured map exists and matches the current targets. */
    public static boolean hasCapture(Object targets) {
        return !broken && sourcesFrom == targets && !clones.isEmpty()
                && sourcesValid();
    }

    /**
     * Copies the pack's current shadow textures into the clones. Called at
     * the tail of a REAL shadow render. Returns false (and goes to stock
     * rendering) on any surprise.
     */
    public static boolean capture(Object targets) {
        if (broken || targets == null) {
            return false;
        }
        try {
            if (sourcesFrom != targets || !sourcesValid()) {
                rebuild(targets);
            }
            for (Clone clone : clones) {
                GL43.glCopyImageSubData(clone.source, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                        clone.copy, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                        clone.width, clone.height, 1);
            }
            if (!loggedArmed && !clones.isEmpty()) {
                loggedArmed = true;
                DLSSStyle.LOGGER.info("Shadow cache v2 armed: {} shadow texture(s)"
                        + " snapshotted for reuse ({}x{})", clones.size(),
                        clones.get(0).width, clones.get(0).height);
            }
            return !clones.isEmpty();
        } catch (Throwable t) {
            markBroken("capture", t);
            return false;
        }
    }

    /**
     * Repaints the cached textures into the pack's freshly-cleared shadow
     * targets. Called on a skip frame BEFORE the shadow pass is cancelled;
     * false means the caller must let the real render run.
     */
    public static boolean restore(Object targets) {
        if (broken || targets == null || sourcesFrom != targets
                || clones.isEmpty() || !sourcesValid()) {
            return false;
        }
        try {
            for (Clone clone : clones) {
                GL43.glCopyImageSubData(clone.copy, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                        clone.source, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                        clone.width, clone.height, 1);
            }
            return true;
        } catch (Throwable t) {
            markBroken("restore", t);
            return false;
        }
    }

    /** A resized or reloaded pack recreates its textures; detect via GL. */
    private static boolean sourcesValid() {
        for (Clone clone : clones) {
            if (!GL11.glIsTexture(clone.source)) {
                return false;
            }
        }
        return true;
    }

    private static void rebuild(Object targets) throws Exception {
        release();
        if (!GL.getCapabilities().OpenGL43) {
            throw new IllegalStateException("glCopyImageSubData needs OpenGL 4.3");
        }
        probeMethods(targets);
        List<Integer> sources = new ArrayList<>();
        addDepth(sources, targets, getDepthTexture);
        addDepth(sources, targets, getDepthTextureNoTranslucents);
        if (getColorTextureId != null) {
            for (int i = 0; i < 8; i++) {
                try {
                    Object id = getColorTextureId.invoke(targets, i);
                    if (id instanceof Integer texture && texture > 0
                            && GL11.glIsTexture(texture)) {
                        sources.add(texture);
                    }
                } catch (Throwable outOfRange) {
                    break;
                }
            }
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("no shadow textures found to cache");
        }
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            for (int source : sources) {
                clones.add(cloneOf(source));
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
        sourcesFrom = targets;
    }

    private static void addDepth(List<Integer> sources, Object targets,
                                 Method method) {
        if (method == null) {
            return;
        }
        try {
            Object depthTexture = method.invoke(targets);
            if (depthTexture == null) {
                return;
            }
            if (getTextureId == null) {
                getTextureId = depthTexture.getClass().getMethod("getTextureId");
            }
            Object id = getTextureId.invoke(depthTexture);
            if (id instanceof Integer texture && texture > 0
                    && GL11.glIsTexture(texture)) {
                sources.add(texture);
            }
        } catch (Throwable ignored) {
            // This depth flavour is absent on this Iris build; fine.
        }
    }

    private static void probeMethods(Object targets) {
        if (methodsProbed) {
            return;
        }
        methodsProbed = true;
        Class<?> type = targets.getClass();
        getDepthTexture = methodOf(type, "getDepthTexture");
        getDepthTextureNoTranslucents = methodOf(type, "getDepthTextureNoTranslucents");
        getColorTextureId = methodOf(type, "getColorTextureId", int.class);
    }

    private static Method methodOf(Class<?> type, String name, Class<?>... args) {
        try {
            return type.getMethod(name, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A clone with the exact size and internal format of its source. */
    private static Clone cloneOf(int source) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
        Clone clone = new Clone();
        clone.source = source;
        clone.width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                GL11.GL_TEXTURE_WIDTH);
        clone.height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                GL11.GL_TEXTURE_HEIGHT);
        clone.internalFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                GL11.GL_TEXTURE_INTERNAL_FORMAT);
        boolean depth = clone.internalFormat == GL11.GL_DEPTH_COMPONENT
                || clone.internalFormat == org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT16
                || clone.internalFormat == org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24
                || clone.internalFormat == org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT32
                || clone.internalFormat == org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT32F;
        clone.copy = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, clone.copy);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, clone.internalFormat,
                clone.width, clone.height, 0,
                depth ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RGBA,
                depth ? GL11.GL_FLOAT : GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        return clone;
    }

    private static void markBroken(String where, Throwable t) {
        broken = true;
        DLSSStyle.LOGGER.warn("Shadow snapshot {} failed - shadow cache disabled,"
                + " shadows render stock: {}", where, t.toString());
        release();
    }

    /** Frees the clone textures; safe to call at any time. */
    public static void release() {
        for (Clone clone : clones) {
            try {
                GL11.glDeleteTextures(clone.copy);
            } catch (Throwable ignored) {
                // Context going away.
            }
        }
        clones.clear();
        sourcesFrom = null;
    }
}
