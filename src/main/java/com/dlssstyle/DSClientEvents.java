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

    /**
     * Frames until the join banner fires; armed at login, counted down in
     * the render stage. The delay is load-bearing twice over: the pack
     * engagement latch decides on the first scaled frame, and chat sent at
     * the login instant lands before the HUD exists.
     */
    private static int bannerFrames;

    @SubscribeEvent
    public static void onLoggingIn(
            net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        bannerFrames = 40;
    }

    /**
     * One line of state at world join: preset, real render scale, pack and
     * whether scaling is actually engaged. The disengaged-mod footgun cost
     * a whole night of testing on 2026-08-12 precisely because nothing on
     * screen ever said what the mod was doing; this says it, once.
     */
    private static void joinBanner() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui == null) {
            return;
        }
        DSPreset preset = DSConfig.preset();
        StringBuilder text = new StringBuilder(preset.label());
        if (!preset.enabled()) {
            text.append(" - native, nothing runs");
        } else {
            boolean pack = DSRenderScale.shaderPackActive();
            boolean engaged = DSRenderScale.isActive();
            if (engaged) {
                // Config scale, not currentScale(): this runs mid-world-pass
                // with the proxy armed, where getMainRenderTarget() IS the
                // scaled target and currentScale() would read 100%.
                text.append(" - world at ").append(
                        (int) Math.round(DSConfig.effectiveRenderScale() * 100.0))
                        .append('%');
            } else {
                text.append(" - native");
            }
            if (pack) {
                String name = com.dlssstyle.compat.DSIrisIntegration.shaderPackName();
                text.append(", pack ").append(name == null ? "on" : name);
                if (!engaged) {
                    text.append(preset.scalesUnderPack()
                            ? " - NOT engaged, switch preset once to re-latch"
                            : " - pass-through by design");
                }
            } else if (engaged) {
                text.append(", temporal path");
            }
        }
        DLSSStyle.LOGGER.info("Join banner: {}", text);
        minecraft.gui.getChat().addMessage(Component.literal("DLSS Style: ")
                .append(Component.literal(text.toString())
                        .withStyle(ChatFormatting.AQUA)));
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        // Tail of LevelRenderer.renderLevel: the whole world has written
        // depth and vanilla's pre-hand depth clear has NOT happened yet -
        // the only window in which world depth exists to snapshot. This
        // replaces the withdrawn RenderSystem.clear redirect: Forge's own
        // seam, nothing vanilla is displaced.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            DSRenderScale.snapshotWorldDepth();
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            // One line, a no-op after it fires once: the pack doctor's chat
            // hint needs an in-game moment, and this event proves one.
            DSPackDoctor.maybeAnnounce();
            if (bannerFrames > 0 && --bannerFrames == 0) {
                joinBanner();
            }
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

    // ── camera sweep: the dev lever that makes reprojection falsifiable ──
    // Input injection into GLFW is dead (raw input discards SendInput), so
    // motion-quality testing needs the mod to move the camera itself. A
    // CAMERA_SWEEP marker file in the game dir runs a fixed script: 3s
    // strafe right, 3s strafe left, 2s yaw turn - repeatable enough to
    // A/B temporal stability across builds. Same family as DUMP_BUFFERS:
    // a file, not a keybind, so it works from outside while unattended.
    // Gated behind debugDump, inert for anyone who never touches markers.
    private static int sweepTicks = -1;

    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            sweepTicks = -1;
            return;
        }
        if (sweepTicks < 0) {
            if (!DSConfig.debugDump()) {
                return;
            }
            try {
                java.nio.file.Path marker = java.nio.file.Path.of("CAMERA_SWEEP");
                if (java.nio.file.Files.exists(marker)) {
                    java.nio.file.Files.delete(marker);
                    sweepTicks = 0;
                    DLSSStyle.LOGGER.info("Camera sweep: start");
                }
            } catch (Exception ignored) {
                // A locked marker just means no sweep this tick.
            }
            return;
        }
        boolean right = sweepTicks < 60;
        boolean left = !right && sweepTicks < 120;
        minecraft.options.keyRight.setDown(right);
        minecraft.options.keyLeft.setDown(left);
        if (!right && !left) {
            if (sweepTicks < 160) {
                minecraft.player.turn(6.0, 0.0);    // ~18 deg/s yaw
            } else {
                sweepTicks = -1;
                DLSSStyle.LOGGER.info("Camera sweep: done");
                return;
            }
        }
        sweepTicks++;
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
