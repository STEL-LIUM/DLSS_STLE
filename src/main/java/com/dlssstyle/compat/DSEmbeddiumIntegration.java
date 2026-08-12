package com.dlssstyle.compat;

import com.dlssstyle.DSConfig;
import com.dlssstyle.DLSSStyle;
import com.dlssstyle.render.DSPreset;
import com.google.common.collect.ImmutableList;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.network.chat.Component;
import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;

/**
 * Embeddium (and Rubidium-family) replaces the whole Video Settings screen
 * with its Sodium-style GUI, which erases our vanilla-screen button. This
 * registers "DLSS Style" as a native section in that GUI's left nav - the
 * same mechanism Oculus/Mekalus uses for its Shader Packs page.
 *
 * <p>Every Embeddium type stays inside this class; it is only class-loaded
 * behind a ModList.isLoaded("embeddium") check, so installs without
 * Embeddium never touch it.
 */
public final class DSEmbeddiumIntegration {
    private DSEmbeddiumIntegration() {
    }

    /**
     * Storage is a formality: our bindings write straight into DSConfig,
     * which persists itself on every change (and reloads chunks - so the
     * change is applied live, no Apply click needed).
     */
    private static final OptionStorage<Void> STORAGE = new OptionStorage<>() {
        @Override
        public Void getData() {
            return null;
        }

        @Override
        public void save() {
        }
    };

    public static void register() {
        OptionGUIConstructionEvent.BUS.addListener(event -> event.addPage(buildPage()));
        DLSSStyle.LOGGER.info("Registered DLSS Style page in Embeddium's video settings");
    }

    private static OptionPage buildPage() {
        DSPreset[] presets = DSPreset.values();
        Component[] names = new Component[presets.length];
        for (int i = 0; i < presets.length; i++) {
            names[i] = Component.literal(presets[i].label());
        }

        OptionImpl<Void, DSPreset> preset = OptionImpl.createBuilder(DSPreset.class, STORAGE)
                .setName(Component.literal("Preset"))
                .setTooltip(Component.literal(
                        "DLSS-style temporal upscaling. DLAA (default): native res, "
                        + "pure anti-aliasing - no quality lost. Quality 67% / "
                        + "Balanced 58% / Performance 50% per axis (DLSS's own ratios) "
                        + "trade pixels for fps. Dynamic: holds your fps target by "
                        + "itself. Applies instantly."))
                .setControl(option -> new CyclingControl<>(option, DSPreset.class, names))
                .setBinding((data, value) -> DSConfig.setPreset(value),
                        data -> DSConfig.preset())
                .setImpact(OptionImpact.HIGH)
                .build();

        OptionImpl<Void, Integer> sharpness = OptionImpl.createBuilder(Integer.class, STORAGE)
                .setName(Component.literal("Sharpness"))
                .setTooltip(Component.literal(
                        "Edge crispness added back after upscaling. Too high looks gritty."))
                .setControl(option -> new SliderControl(option, 0, 100, 5,
                        ControlValueFormatter.percentage()))
                .setBinding((data, value) -> DSConfig.setSharpness(value / 100.0),
                        data -> (int) Math.round(DSConfig.upscaleSharpness() * 100.0))
                .setImpact(OptionImpact.LOW)
                .build();

        OptionImpl<Void, Integer> targetFps = OptionImpl.createBuilder(Integer.class, STORAGE)
                .setName(Component.literal("Dynamic Preset: Target"))
                .setTooltip(Component.literal(
                        "ONLY used by the Dynamic preset, which moves the render scale "
                        + "to hold this framerate. It is NOT an fps cap - it does "
                        + "nothing on Off, DLAA, Quality, Balanced or Performance, and "
                        + "nothing in this mod limits your framerate. Auto = your fps "
                        + "cap, or the monitor's refresh rate when uncapped."))
                .setControl(option -> new SliderControl(option, 0, 240, 10,
                        value -> value == 0 ? Component.literal("Auto")
                                : Component.literal(value + " fps")))
                .setBinding((data, value) -> DSConfig.setDynamicTargetFps(value),
                        data -> DSConfig.dynamicTargetFps())
                .setImpact(OptionImpact.VARIES)
                .build();

        return new OptionPage(Component.literal("DLSS Style"),
                ImmutableList.of(OptionGroup.createBuilder()
                        .add(preset)
                        .add(sharpness)
                        .add(targetFps)
                        .build()));
    }
}
