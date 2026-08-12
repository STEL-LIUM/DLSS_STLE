package com.dlssstyle.compat;

import com.dlssstyle.DLSSStyle;
import net.minecraftforge.fml.ModList;

/**
 * Sodium-family chunk renderers (Embeddium, Rubidium, Sodium itself) replace
 * Minecraft's terrain renderer wholesale - their mixin configs even declare
 * method overwrites. Terrain therefore never passes through the vanilla
 * bind-steal seam we reroute, so it lands in the REAL screen buffer at full
 * resolution while entities land in our scaled one. The resolve then paints
 * entities over the top and the world turns to x-ray: mobs and cave
 * interiors visible, floor and trees gone (reported live 2026-08-11).
 *
 * <p>Until the main-render-target proxy lands - which fixes this and the
 * Iris case together, because a proxied accessor catches EVERY renderer,
 * not just vanilla's - the honest behaviour is to stand down and say so.
 */
public final class DSChunkRendererCompat {
    private static final String[] SODIUM_FAMILY = {
            "embeddium", "rubidium", "sodium", "xenon", "nvidium"};

    private static Boolean present;

    private DSChunkRendererCompat() {
    }

    /** True when a replacement chunk renderer owns terrain rendering. */
    public static boolean replacementChunkRenderer() {
        Boolean cached = present;
        if (cached == null) {
            cached = false;
            try {
                for (String id : SODIUM_FAMILY) {
                    if (ModList.get().isLoaded(id)) {
                        cached = true;
                        DLSSStyle.LOGGER.info(
                                "{} detected - terrain is drawn by it, so scaling relies"
                                + " on the render-target proxy rather than vanilla's seams", id);
                        break;
                    }
                }
            } catch (Throwable t) {
                // Mod list not ready yet; ask again next frame.
                return false;
            }
            present = cached;
        }
        return cached;
    }
}
