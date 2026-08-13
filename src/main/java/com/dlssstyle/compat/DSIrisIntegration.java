package com.dlssstyle.compat;

import com.dlssstyle.DLSSStyle;

import java.lang.reflect.Method;

/**
 * Shader-pack awareness. Iris-family mods (Oculus, Mekalus, Iris itself)
 * replace the world pipeline with their own framebuffers when a shader
 * pack is enabled - our scaled target would fight theirs and lose. So
 * while a pack is in use the upscaler stands down automatically, and
 * re-engages the moment the pack is turned off.
 *
 * <p>Reflection instead of a compile dependency on purpose: one cached
 * Method on the stable v0 API (nanoseconds per call) works identically
 * across every Iris fork regardless of modid, and installs without any
 * shader mod skip the probe entirely.
 */
public final class DSIrisIntegration {
    private static Object api;
    private static Method inUse;
    private static boolean probed;
    private static boolean lastState;

    private DSIrisIntegration() {
    }

    /** True while an Iris-family shader pack is actively rendering. */
    public static boolean shaderPackActive() {
        if (!probed) {
            probe();
        }
        if (inUse == null) {
            return false;
        }
        try {
            boolean active = (Boolean) inUse.invoke(api);
            if (active != lastState) {
                lastState = active;
                DLSSStyle.LOGGER.info(active
                        ? "Shader pack active - scaling the PACK's own passes"
                          + " (fixed preset, spatial resolve, no jitter)"
                        : "Shader pack off - full temporal path restored");
                // Say it in chat too: a silent standdown looks like a broken
                // mod, and "why did DLSS Style stop doing anything" is the
                // first thing a shader user would report.
                net.minecraft.client.Minecraft minecraft =
                        net.minecraft.client.Minecraft.getInstance();
                if (minecraft.level != null && minecraft.gui != null) {
                    minecraft.gui.getChat().addMessage(net.minecraft.network.chat.Component
                            .literal("DLSS Style: ")
                            .append(net.minecraft.network.chat.Component
                                    .literal(active
                                            ? "shader mode - pick Quality/Balanced/Performance"
                                            : "shader pack off - full temporal path")
                                    .withStyle(net.minecraft.ChatFormatting.AQUA)));
                }
            }
            return active;
        } catch (Throwable t) {
            inUse = null;
            return false;
        }
    }

    /**
     * Programmatic shader-pack reload through the STABLE v0 API - no
     * internals touched. IrisApi.getConfig() hands back IrisApiConfig,
     * whose setShadersEnabledAndApply(...) is implemented as a config save
     * plus a full pipeline reload; re-applying the CURRENT enabled state is
     * therefore exactly the "toggle the pack" dance, in one call. Verified
     * present in this environment's shader mod (Mekalus 1.8.0.1, method
     * table read from the jar). Returns false when unavailable so the
     * caller can fall back to telling the player the manual step.
     */
    public static boolean reloadShaderPack() {
        if (!probed) {
            probe();
        }
        if (api == null) {
            return false;
        }
        try {
            Object config = api.getClass().getMethod("getConfig").invoke(api);
            boolean enabled = (Boolean) config.getClass()
                    .getMethod("areShadersEnabled").invoke(config);
            if (!enabled) {
                return false;   // nothing to reload; engagement is moot
            }
            config.getClass().getMethod("setShadersEnabledAndApply", boolean.class)
                    .invoke(config, true);
            DLSSStyle.LOGGER.info("Shader pack reloaded programmatically"
                    + " (preset change re-latches the scaling session)");
            return true;
        } catch (Throwable t) {
            DLSSStyle.LOGGER.warn("Programmatic shader reload unavailable: {}",
                    t.toString());
            return false;
        }
    }

    /**
     * The active pack's name for the join banner, or null when there is no
     * shader mod, no pack, or the internal method moved. Internals (not the
     * v0 API, which never exposed the name) - so this is best-effort by
     * design and a null simply drops the name from the banner.
     */
    public static String shaderPackName() {
        for (String className : new String[]{
                "net.irisshaders.iris.Iris", "net.coderbot.iris.Iris"}) {
            try {
                Object name = Class.forName(className)
                        .getMethod("getCurrentPackName").invoke(null);
                return name == null ? null : name.toString();
            } catch (Throwable ignored) {
                // Try the next home.
            }
        }
        return null;
    }

    private static void probe() {
        probed = true;
        // Both API homes: current Iris/Oculus/Mekalus use the irisshaders
        // package, older Oculus builds shipped the coderbot one. Whichever
        // answers first wins; neither present means no shader mod at all.
        for (String className : new String[]{
                "net.irisshaders.iris.api.v0.IrisApi",
                "net.coderbot.iris.api.v0.IrisApi"}) {
            try {
                Class<?> irisApi = Class.forName(className);
                api = irisApi.getMethod("getInstance").invoke(null);
                inUse = irisApi.getMethod("isShaderPackInUse");
                DLSSStyle.LOGGER.info("Shader mod detected ({}) - DLSS Style will stand down"
                        + " automatically while a shader pack is loaded", className);
                return;
            } catch (Throwable ignored) {
                // Try the next home.
            }
        }
    }
}
