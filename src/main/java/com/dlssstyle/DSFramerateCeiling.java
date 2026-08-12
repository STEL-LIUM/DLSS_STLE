package com.dlssstyle;

import net.minecraft.client.Minecraft;

/**
 * Keeps the framerate inside the G-Sync/FreeSync range - in FULLSCREEN ONLY.
 *
 * <p>Above the top of a VRR panel's range the display hands control from
 * variable refresh back to vsync, and that handoff is felt as a sharp drop.
 * It bites hardest when standing still, because that is when framerate is
 * highest. Frames past the refresh rate are rendered and then discarded, so
 * capping just below it costs nothing real.
 *
 * <p>Fullscreen only, and that distinction is the whole reason this exists
 * again: windowed rendering goes through the desktop compositor, G-Sync is
 * not truly driving the panel, and there is no handoff to avoid - Aryan
 * confirmed it live, stable windowed and dropping in fullscreen. An earlier
 * version clamped everywhere and was rightly removed for making "Unlimited"
 * a lie where it did no good.
 */
public final class DSFramerateCeiling {
    private static final int UNDER_REFRESH = 5;
    private static int lastLogged;

    private DSFramerateCeiling() {
    }

    public static int clamp(int requested) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.getWindow().isFullscreen()) {
            return requested;
        }
        int refresh = minecraft.getWindow().getRefreshRate();
        if (refresh < 30) {
            return requested;
        }
        int ceiling = Math.max(30, refresh - UNDER_REFRESH);
        if (requested <= ceiling) {
            return requested;
        }
        if (lastLogged != ceiling) {
            lastLogged = ceiling;
            DLSSStyle.LOGGER.info(
                    "Fullscreen: capping {} -> {} fps ({}Hz panel) so G-Sync never hands off to vsync",
                    requested >= 260 ? "Unlimited" : String.valueOf(requested), ceiling, refresh);
        }
        return ceiling;
    }
}
