package com.dlssstyle;

import com.dlssstyle.render.DSPreset;
import com.dlssstyle.render.DSRenderScale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Forge-bus wiring: the per-frame camera matrices for temporal
 * reprojection, a preset button in Video Settings, and a hotkey.
 */
public final class DSClientEvents {
    private DSClientEvents() {
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            // The temporal upscaler's reprojection needs this frame's
            // camera matrices; captured here where pose + projection are
            // both in hand. The pose carries ROTATION ONLY, so the camera
            // position rides along separately - without it the reprojection
            // cannot follow the player walking.
            DSRenderScale.noteViewProj(
                    new org.joml.Matrix4f(event.getProjectionMatrix())
                            .mul(event.getPoseStack().last().pose()),
                    event.getCamera().getPosition());
        }
    }

    /**
     * The control lives where players look for graphics switches: a
     * preset cycle button added to vanilla's Video Settings screen.
     * Vanilla's option rows are a closed list, so the button sits above
     * them in the top-right corner.
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof VideoSettingsScreen screen)) {
            return;
        }
        DSPreset[] cycleValues = DSConfig.dynamicEnabled()
                ? DSPreset.values()
                : java.util.Arrays.stream(DSPreset.values())
                        .filter(preset -> preset != DSPreset.DYNAMIC)
                        .toArray(DSPreset[]::new);
        event.addListener(CycleButton.<DSPreset>builder(
                        preset -> Component.literal(preset.label()))
                .withValues(cycleValues)
                .withInitialValue(DSConfig.preset())
                .create(screen.width - 165, 6, 160, 20,
                        Component.literal("DLSS Style"),
                        (button, preset) -> DSConfig.setPreset(preset)));
    }

    /**
     * Ctrl+U steps through the presets in game, with a chat
     * confirmation. A public mod whose only control needs a menu visit
     * gets toggled a lot less; the key is discoverable from the mod page.
     */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS
                || event.getKey() != GLFW.GLFW_KEY_U
                || (event.getModifiers() & GLFW.GLFW_MOD_CONTROL) == 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.screen != null) {
            return;
        }
        DSPreset[] order = DSPreset.values();
        DSPreset next = order[(DSConfig.preset().ordinal() + 1) % order.length];
        if (next == DSPreset.DYNAMIC && !DSConfig.dynamicEnabled()) {
            next = order[(next.ordinal() + 1) % order.length];
        }
        DSConfig.setPreset(next);
        minecraft.gui.getChat().addMessage(Component.literal("DLSS Style: ")
                .append(Component.literal(next.label())
                        .withStyle(ChatFormatting.AQUA)));
    }
}
