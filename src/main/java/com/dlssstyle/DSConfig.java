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
        final ForgeConfigSpec.BooleanValue enableDynamic;
        final ForgeConfigSpec.BooleanValue jitter;
        final ForgeConfigSpec.BooleanValue menuBoost;
        final ForgeConfigSpec.BooleanValue beatSync;
        final ForgeConfigSpec.BooleanValue debugDump;
        final ForgeConfigSpec.BooleanValue packDoctorEnabled;
        final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> packDoctorMissingVars;
        final ForgeConfigSpec.BooleanValue packDoctorAuto;
        final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> packDoctorKnownVars;

        Client(ForgeConfigSpec.Builder builder) {
            builder.comment("DLSS Style - DLSS-style temporal upscaling.",
                    "The world renders at a fraction of your window's pixels and",
                    "is stretched back smart: temporal accumulation with",
                    "depth-based camera reprojection.").push("upscale");
            this.preset = builder
                    .comment("OFF: native, nothing runs.",
                            "QUALITY / BALANCED / PERFORMANCE / ULTRA_PERFORMANCE:",
                            "DLSS's own scale ratios (67% / 58% / 50% / 33% per",
                            "axis) - fps for pixels.",
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
            this.enableDynamic = builder
                    .comment("Allow the DYNAMIC preset. Off: DYNAMIC is hidden from",
                            "preset cycling and treated as DLAA if already selected.")
                    .define("enableDynamic", true);
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
                    .comment("OPT-IN. Beat Sync: frames release on a",
                            "metronome-steady grid at an even divisor of your",
                            "refresh rate, stepping down only when the GPU cannot",
                            "hold the pace. Aimed at stutter/flicker on",
                            "G-Sync/FreeSync panels at any fps, including",
                            "Unlimited. Stands down automatically under vsync, in",
                            "menus, unfocused, or when another clock is detected.",
                            "Off by default: taking over frame delivery is the one",
                            "thing here a player cannot see going wrong, and a",
                            "performance mod should not touch it uninvited.")
                    .define("beatSync", false);
            this.debugDump = builder
                    .comment("Allow the DUMP_BUFFERS marker file to write the",
                            "pipeline's buffers as PNGs (logs/buffers/) - the",
                            "debugging lever that found every bug in this mod.")
                    .define("debugDump", true);
            this.packDoctorEnabled = builder
                    .comment("Pack doctor: scan shader-pack zips for custom-uniform",
                            "expressions that reference variables this shader mod",
                            "build does not provide (the parser then feeds the pack",
                            "garbage - measured as 2Hz full-scene strobing on",
                            "Complementary Reimagined r5.8.1). Writes a '_fixed'",
                            "sibling zip with only those lines pinned to 0; the",
                            "original pack is never touched.")
                    .define("packDoctorEnabled", true);
            this.packDoctorMissingVars = builder
                    .comment("Variables known to be missing from this shader mod",
                            "build. Expression lines referencing any of these are",
                            "neutralised in the '_fixed' copy. Extend this list when",
                            "a pack update leans on a newer Iris variable.")
                    .defineList("packDoctorMissingVars",
                            java.util.List.of("endFlashIntensity", "BIOME_PALE_GARDEN"),
                            entry -> entry instanceof String s && !s.isBlank());
            this.packDoctorAuto = builder
                    .comment("Pack doctor auto-discovery: instead of only the fixed",
                            "list above, parse every expression and flag ANY",
                            "identifier the installed shader mod does not provide",
                            "(checked against the documented Iris 1.6 variable set",
                            "plus this Minecraft version's real biome registry).",
                            "This is how a future pack update leaning on a newer",
                            "Iris variable gets healed with zero config changes.")
                    .define("packDoctorAuto", true);
            this.packDoctorKnownVars = builder
                    .comment("Escape hatch for auto-discovery false positives:",
                            "identifiers listed here are always treated as provided",
                            "and never cause a line to be neutralised.")
                    .defineList("packDoctorKnownVars", java.util.List.of(),
                            entry -> entry instanceof String s && !s.isBlank());
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
        // The single guard for the DYNAMIC toggle: every consumer reads the
        // preset through here, so a disabled DYNAMIC behaves as DLAA
        // everywhere without touching the configured value.
        if (cached == DSPreset.DYNAMIC && !dynamicEnabled()) {
            return DSPreset.DLAA;
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
        // The engagement latch is per pack-session: crossing between a
        // scaling and a non-scaling preset while a pack is active used to
        // require a manual pack reload (the #1 footgun - it cost a whole
        // night of testing a disengaged mod). Reload programmatically via
        // the stable Iris v0 API; if this build's shader mod lacks the
        // method, say the exact remaining manual step instead.
        if (DSRenderScale.packReloadNeeded(preset)) {
            DSRenderScale.resetShaderSession();
            boolean reloaded =
                    com.dlssstyle.compat.DSIrisIntegration.reloadShaderPack();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null && minecraft.gui != null) {
                minecraft.gui.getChat().addMessage(
                        net.minecraft.network.chat.Component.literal("DLSS Style: ")
                                .append(net.minecraft.network.chat.Component.literal(
                                        reloaded
                                        ? "shader pack reloaded for " + preset.label()
                                        : "to finish switching to " + preset.label()
                                          + ", open Video Settings > Shader Packs"
                                          + " and click Apply")
                                .withStyle(net.minecraft.ChatFormatting.AQUA)));
            }
        }
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

    /** Config not loaded reads as TRUE: DYNAMIC stays available by default. */
    public static boolean dynamicEnabled() {
        try {
            return CLIENT.enableDynamic.get();
        } catch (IllegalStateException e) {
            return true;
        }
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

    public static boolean packDoctorEnabled() {
        try {
            return CLIENT.packDoctorEnabled.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Config not loaded reads as the shipped default set. */
    public static java.util.List<String> packDoctorMissingVars() {
        try {
            return java.util.List.copyOf(CLIENT.packDoctorMissingVars.get());
        } catch (IllegalStateException e) {
            return java.util.List.of("endFlashIntensity", "BIOME_PALE_GARDEN");
        }
    }

    public static boolean packDoctorAuto() {
        try {
            return CLIENT.packDoctorAuto.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public static java.util.List<String> packDoctorKnownVars() {
        try {
            return java.util.List.copyOf(CLIENT.packDoctorKnownVars.get());
        } catch (IllegalStateException e) {
            return java.util.List.of();
        }
    }
}
