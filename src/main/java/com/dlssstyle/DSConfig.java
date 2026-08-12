package com.dlssstyle;

import com.dlssstyle.render.DSPreset;
import com.dlssstyle.render.DSRenderScale;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * DLSS Style's config - the whole public surface, deliberately small.
 * The preset is the product; everything else is a tuned default.
 */
public final class DSConfig {
    public static final ForgeConfigSpec SPEC;
    static final Client CLIENT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CLIENT = new Client(builder);
        SPEC = builder.build();
    }

    static final class Client {
        final ForgeConfigSpec.EnumValue<DSPreset> preset;
        final ForgeConfigSpec.DoubleValue sharpness;
        final ForgeConfigSpec.IntValue dynamicTargetFps;
        final ForgeConfigSpec.BooleanValue jitter;
        final ForgeConfigSpec.BooleanValue menuBoost;
        final ForgeConfigSpec.BooleanValue beatSync;
        final ForgeConfigSpec.BooleanValue debugDump;

        Client(ForgeConfigSpec.Builder builder) {
            builder.comment("DLSS Style - DLSS-style temporal upscaling.",
                    "The world renders at a fraction of your window's pixels and",
                    "is stretched back smart: temporal accumulation with",
                    "depth-based camera reprojection.").push("upscale");
            this.preset = builder
                    .comment("OFF: native, nothing runs.",
                            "QUALITY / BALANCED / PERFORMANCE: DLSS's own scale",
                            "ratios (67% / 58% / 50% per axis) - fps for pixels.",
                            "DLAA: native res, the temporal pass as pure",
                            "anti-aliasing - no quality sacrificed. The default.",
                            "DYNAMIC: scale adjusts itself to hold your fps cap or",
                            "refresh rate.")
                    .defineEnum("preset", DSPreset.DLAA);
            this.sharpness = builder
                    .comment("Edge crispness added back after upscaling.",
                            "Too high looks gritty.")
                    .defineInRange("sharpness", 0.35, 0.0, 1.0);
            this.dynamicTargetFps = builder
                    .comment("The fps the DYNAMIC preset holds. 0 = automatic",
                            "(your fps cap, or the monitor's refresh rate when",
                            "uncapped).")
                    .defineInRange("dynamicTargetFps", 0, 0, 240);
            this.jitter = builder
                    .comment("EXPERIMENTAL. Sub-pixel camera jitter - the DLSS",
                            "detail trick. Each frame samples slightly different",
                            "points inside every pixel and the temporal blend",
                            "integrates them into real detail. Off until it has",
                            "passed a visual A/B - may shimmer or blur.")
                    .define("jitter", false);
            this.menuBoost = builder
                    .comment("EXPERIMENTAL. Halve the render scale while a menu or",
                            "inventory is open. Off by default: each open/close",
                            "currently costs a framebuffer resize.")
                    .define("menuBoost", false);
            this.beatSync = builder
                    .comment("Beat Sync: frames release on a metronome-steady grid",
                            "at an even divisor of your refresh rate, stepping down",
                            "only when the GPU cannot hold the pace. THE fix for",
                            "stutter/flicker on G-Sync/FreeSync panels at any fps,",
                            "including Unlimited. Stands down automatically under",
                            "vsync, in menus, unfocused, or when another clock is",
                            "detected. Off = vanilla frame limiting, untouched.")
                    .define("beatSync", true);
            this.debugDump = builder
                    .comment("Allow the DUMP_BUFFERS marker file to write the",
                            "pipeline's buffers as PNGs (logs/buffers/) - the",
                            "debugging lever that found every bug in this mod.")
                    .define("debugDump", true);
            builder.pop();
        }
    }

    private DSConfig() {
    }

    private static volatile DSPreset presetCache;

    public static DSPreset preset() {
        DSPreset cached = presetCache;
        if (cached == null) {
            try {
                cached = CLIENT.preset.get();
            } catch (IllegalStateException e) {
                return DSPreset.OFF;
            }
            presetCache = cached;
        }
        return cached;
    }

    /**
     * Applies a preset. Takes effect on the very next frame - and ONLY on
     * the framebuffer. An earlier version also reloaded all chunks here
     * (vanilla graphics-switch style) as an "it applied!" signal; that was
     * a multi-second rebuild storm after every toggle - including the
     * toggle to Off - reported live as "made stutters even when off".
     * The resolution change is its own confirmation.
     */
    public static void setPreset(DSPreset preset) {
        try {
            CLIENT.preset.set(preset);
            SPEC.save();
        } catch (Exception e) {
            DLSSStyle.LOGGER.warn("Could not persist preset", e);
        }
        presetCache = preset;
        DSRenderScale.onPresetChanged(preset);
        DLSSStyle.LOGGER.info("Preset: {}", preset.label());
    }

    /** Read per frame by the scale pass. */
    public static double effectiveRenderScale() {
        double scale = DSRenderScale.presetScale(preset());
        if (menuBoosting()) {
            return Math.min(scale, 0.5);
        }
        return scale;
    }

    /**
     * Menu boost: while a screen covers the world the scale drops to half -
     * free frames where nobody is looking. Exempt: chat (the world stays
     * fully visible while typing) and the video-settings screens (judging
     * preset quality through a boosted image would be misleading).
     */
    public static boolean menuBoosting() {
        // Never under a shader pack: halving the scale on every menu open
        // would rebuild the pack's entire buffer set, twice per visit.
        if (!menuBoost() || !preset().enabled() || DSRenderScale.shaderPackActive()) {
            return false;
        }
        net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
        if (screen == null
                || screen instanceof net.minecraft.client.gui.screens.ChatScreen
                || screen instanceof net.minecraft.client.gui.screens.VideoSettingsScreen) {
            return false;
        }
        return !screen.getClass().getSimpleName().contains("SodiumOptionsGUI");
    }

    public static boolean jitterEnabled() {
        try {
            return CLIENT.jitter.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static boolean menuBoost() {
        try {
            return CLIENT.menuBoost.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Config not loaded reads as FALSE: never pace on a guess. */
    public static boolean beatSync() {
        try {
            return CLIENT.beatSync.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }


    public static double upscaleSharpness() {
        try {
            return CLIENT.sharpness.get();
        } catch (IllegalStateException e) {
            return 0.35;
        }
    }

    public static void setSharpness(double sharpness) {
        try {
            CLIENT.sharpness.set(sharpness);
            SPEC.save();
        } catch (Exception e) {
            DLSSStyle.LOGGER.warn("Could not persist sharpness", e);
        }
    }

    /** 0 means automatic (fps cap, else monitor refresh). */
    public static int dynamicTargetFps() {
        try {
            return CLIENT.dynamicTargetFps.get();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public static void setDynamicTargetFps(int fps) {
        try {
            CLIENT.dynamicTargetFps.set(fps);
            SPEC.save();
        } catch (Exception e) {
            DLSSStyle.LOGGER.warn("Could not persist dynamic target fps", e);
        }
    }

    public static boolean debugDump() {
        try {
            return CLIENT.debugDump.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
