package com.dlssstyle;

import com.dlssstyle.render.DSUpscaleShader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DLSS Style - DLSS-style temporal upscaling for Minecraft, standalone.
 * (Internal modid stays "dlssstyle": modids are frozen once published,
 * and NVIDIA's mark is safer out of the id.)
 *
 * <p>Extracted 2026-08-11 from the Enchanted Genesis performance mod so the
 * public can use it on its own; the core mod keeps its integrated copy.
 * The pipeline: the world renders into a reduced-resolution target
 * (fragment cost scales with pixels - THAT is where the fps comes from),
 * then resolves to display size DLSS-style: temporal accumulation with
 * depth-based camera reprojection - the DLSS mechanism, minus the neural
 * network; NGX proper cannot attach to Minecraft's OpenGL.
 *
 * <p>Battle scars carried over from the parent mod, all dump-proven:
 * the mip LOD bias (log2(scale)) without which scaled rendering looks
 * soft and dark; the sampler-clobber fix (vanilla's drawWithShader helper
 * overwrites Sampler0..11 from RenderSystem's shader-texture slots); and
 * the bind-steal fix (vanilla's entity-outline branch rebinds the main
 * target mid-frame EVERY frame - without the redirect, entities, water
 * and the first-person hand render at full res and are erased by the
 * resolve).
 */
@Mod(DLSSStyle.MODID)
public final class DLSSStyle {
    public static final String MODID = "dlssstyle";
    public static final Logger LOGGER = LogManager.getLogger("DLSS Style");

    public DLSSStyle() {
        ensureCleanConfig();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, DSConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(DSClientEvents.class);

        // Embeddium replaces the Video Settings screen wholesale, taking our
        // vanilla-screen button with it - so we join ITS screen instead. The
        // integration class is only touched when Embeddium is present, and a
        // future Embeddium that breaks this API costs a warning, not a crash.
        if (net.minecraftforge.fml.ModList.get().isLoaded("embeddium")) {
            try {
                com.dlssstyle.compat.DSEmbeddiumIntegration.register();
            } catch (Throwable t) {
                LOGGER.warn("Embeddium video-settings integration unavailable", t);
            }
        }
        LOGGER.info("DLSS Style loaded");
    }

    /**
     * Writes a complete config file before Forge reads it. Forge's own
     * first-boot path creates an EMPTY file and then "corrects" every key
     * at WARN level - five lines of scary log noise on a fresh install,
     * and again for anyone upgrading across a key rename. A file that
     * already matches the spec loads silently.
     */
    private static void ensureCleanConfig() {
        try {
            java.nio.file.Path path = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve(MODID + "-client.toml");
            // FRESH INSTALLS ONLY. An earlier version also rewrote existing
            // files that predated a layout change - which silently reset the
            // player's preset to DLAA on upgrade (and its marker key was
            // never bumped for the 1.2.0 doctor keys anyway, so upgrades got
            // the WARN noise regardless). An existing file now always
            // stands; Forge appends any keys it is missing, noisily but
            // once, and the player's choices survive.
            if (java.nio.file.Files.exists(path)) {
                return;
            }
            java.nio.file.Files.writeString(path, """
                    [upscale]
                    \tpreset = "DLAA"
                    \tsharpness = 0.35
                    \tdynamicTargetFps = 0
                    \tenableDynamic = true
                    \tdepthReprojection = true
                    \tmenuBoost = false
                    \tbeatSync = false
                    \tdebugDump = true
                    \tpackDoctorEnabled = true
                    \tpackDoctorMissingVars = ["endFlashIntensity", "BIOME_PALE_GARDEN"]
                    \tpackDoctorAuto = true
                    \tpackDoctorKnownVars = []
                    """);
        } catch (Exception e) {
            // Worst case Forge corrects the file itself, just noisily.
            LOGGER.debug("Could not pre-write config", e);
        }
    }

    /** Mod-bus events: shader + reload listener registration. */
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        private ModBus() {
        }

        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) {
            try {
                event.registerShader(
                        new ShaderInstance(event.getResourceProvider(),
                                DSUpscaleShader.shaderId(),
                                DefaultVertexFormat.POSITION_TEX),
                        DSUpscaleShader::setShader);
            } catch (Exception e) {
                LOGGER.error("Could not register the upscale shader - "
                        + "DLSS Style will fall back to a plain stretch", e);
            }
        }

        @SubscribeEvent
        public static void onClientSetup(
                net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
            // The pack doctor scans shaderpacks/ once per launch and writes
            // "_fixed" siblings for packs whose custom-uniform expressions
            // reference variables this shader mod build does not provide -
            // the measured root cause of the 2026-08-12 full-scene strobing.
            DSPackDoctor.run();
        }

        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            // A resource reload rebuilds the block atlas; the mip LOD bias
            // lives on the atlas texture object and must be re-applied.
            event.registerReloadListener((ResourceManagerReloadListener) manager ->
                    com.dlssstyle.render.DSRenderScale.atlasReset());
        }
    }
}
