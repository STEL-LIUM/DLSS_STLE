package com.dlssstyle;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The pack doctor - root-cause repair for the 2026-08-12 flicker saga.
 *
 * <p>Field-measured with a direct-launch harness: Complementary Reimagined
 * r5.8.1's shaders.properties computes custom uniforms from variables this
 * Oculus build does not provide (endFlashIntensity is an Iris 1.21 uniform;
 * BIOME_PALE_GARDEN is a 1.21.4 biome). The expression parser throws
 * "Unknown variable" and the dependent uniform (endFlashIntensityM, fed to
 * mainLighting.glsl) carries garbage - measured as 2Hz strobes of ~22 luma
 * on every sunlit surface, present even with this mod's scaling disabled.
 * Neutralising exactly the affected expression lines dropped the measured
 * frame pulses from 22.3 to 1.7-2.3 and the parser errors to zero.
 *
 * <p>So: on client setup, scan every shader-pack zip for expression lines
 * (variable.* / uniform.*) that reference a known-missing variable, and
 * write a "_fixed" sibling zip with ONLY those lines rewritten to
 * "&lt;key&gt;=0". The original zip is never modified, and the fix is only
 * ever a sibling the player selects deliberately. The known-missing set is
 * config (packDoctorMissingVars) so future Iris variables never need a
 * rebuild.
 */
public final class DSPackDoctor {
    private static final String PROPS_ENTRY = "shaders/shaders.properties";
    private static final String FIXED_SUFFIX = "_fixed.zip";

    /** Chat hint queued by run(), delivered once by maybeAnnounce(). */
    private static volatile String hint;
    private static boolean hintShown;

    private DSPackDoctor() {
    }

    /** Scans the runtime shaderpacks folder and writes _fixed siblings. */
    public static void run() {
        try {
            if (!DSConfig.packDoctorEnabled()) {
                return;
            }
            List<String> missing = DSConfig.packDoctorMissingVars();
            if (missing.isEmpty()) {
                return;
            }
            Path dir = FMLPaths.GAMEDIR.get().resolve("shaderpacks");
            if (!Files.isDirectory(dir)) {
                return;
            }
            List<String> written = new ArrayList<>();
            try (DirectoryStream<Path> zips = Files.newDirectoryStream(dir, "*.zip")) {
                for (Path zip : zips) {
                    String name = zip.getFileName().toString();
                    if (name.endsWith(FIXED_SUFFIX)) {
                        continue;   // never doctor our own output
                    }
                    doctorOne(zip, name, missing, written);
                }
            }
            if (!written.isEmpty()) {
                hint = "pack " + String.join(", ", written)
                        + " references variables your Oculus build lacks - a fixed copy"
                        + " (_fixed.zip) was created, select it in Shader Packs";
            }
        } catch (Throwable t) {
            // The doctor is a convenience: a failure must never cost the game.
            DLSSStyle.LOGGER.warn("Pack doctor could not run", t);
        }
    }

    private static void doctorOne(Path zip, String name, List<String> missing,
                                  List<String> written) {
        try {
            List<String> afflictedKeys = scan(zip, missing);
            if (afflictedKeys.isEmpty()) {
                return;
            }
            Path fixed = zip.resolveSibling(
                    name.substring(0, name.length() - 4) + FIXED_SUFFIX);
            // Idempotent: a _fixed at least as new as its source stands.
            if (Files.exists(fixed) && Files.getLastModifiedTime(fixed)
                    .compareTo(Files.getLastModifiedTime(zip)) >= 0) {
                // Debug, not info: per-boot "already fixed" notices are noise
                // once the heal has happened. Actual heals stay INFO.
                DLSSStyle.LOGGER.debug(
                        "Pack doctor: {} references missing variables {} - fixed copy"
                        + " already present ({})", name, afflictedKeys,
                        fixed.getFileName());
                return;
            }
            int patched = patch(zip, fixed, missing);
            DLSSStyle.LOGGER.info(
                    "Pack doctor: {} references variables this shader mod build does"
                    + " not provide - neutralised {} expression line(s) {} into {}",
                    name, patched, afflictedKeys, fixed.getFileName());
            written.add(name);
        } catch (Throwable t) {
            DLSSStyle.LOGGER.warn("Pack doctor could not process {}", name, t);
        }
    }

    /** Keys of expression lines in the zip's shaders.properties that
     *  reference a missing variable; empty when the pack is healthy. */
    static List<String> scan(Path zip, List<String> missing) throws IOException {
        List<String> keys = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry entry = file.getEntry(PROPS_ENTRY);
            if (entry == null) {
                return keys;    // not a shader pack (or an odd layout): skip
            }
            String text = new String(file.getInputStream(entry).readAllBytes(),
                    StandardCharsets.ISO_8859_1);
            for (String line : text.split("\n", -1)) {
                String key = afflictedKey(line, missing);
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /**
     * The detection rule, shared by scan and patch: an EXPRESSION line
     * (variable.* / uniform.*) whose text mentions a missing variable.
     * Whole-line substring matching is deliberate - it catches both a value
     * that reads the variable (endFlashFactor0=min(endFlashIntensity,...))
     * and the uniform NAMED for it (endFlashIntensityM=...), which is fed
     * from the poisoned chain and must be pinned too. Returns the key, or
     * null for a healthy line.
     */
    private static String afflictedKey(String rawLine, List<String> missing) {
        String line = rawLine.endsWith("\r")
                ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
        String trimmed = line.trim();
        if (!trimmed.startsWith("variable.") && !trimmed.startsWith("uniform.")) {
            return null;
        }
        int eq = line.indexOf('=');
        if (eq < 0) {
            return null;
        }
        for (String var : missing) {
            if (!var.isEmpty() && line.contains(var)) {
                return line.substring(0, eq).trim();
            }
        }
        return null;
    }

    /**
     * Writes the _fixed sibling: every entry byte-copied, except that
     * afflicted expression lines in shaders.properties become "key=0"
     * (indentation and line endings preserved, everything else untouched).
     * Built in a temp file and moved into place so a crash mid-write can
     * never leave a half zip where a pack is expected.
     */
    private static int patch(Path source, Path fixed, List<String> missing)
            throws IOException {
        Path tmp = Files.createTempFile(fixed.getParent(), "dlssstyle-doctor", ".tmp");
        int patched = 0;
        try {
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source));
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                byte[] buf = new byte[65536];
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    ZipEntry copy = new ZipEntry(entry.getName());
                    if (entry.getTime() != -1) {
                        copy.setTime(entry.getTime());
                    }
                    out.putNextEntry(copy);
                    if (PROPS_ENTRY.equals(entry.getName())) {
                        byte[] data = in.readAllBytes();
                        int[] count = new int[1];
                        out.write(patchProperties(data, missing, count));
                        patched = count[0];
                    } else if (!entry.isDirectory()) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                    out.closeEntry();
                }
            }
            Files.move(tmp, fixed, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return patched;
    }

    /** Rewrites afflicted lines as "key=0"; all other bytes unchanged. */
    private static byte[] patchProperties(byte[] data, List<String> missing,
                                          int[] count) {
        String text = new String(data, StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int nl = text.indexOf('\n', i);
            String line;
            String terminator;
            if (nl < 0) {
                line = text.substring(i);
                terminator = "";
                i = text.length();
            } else if (nl > i && text.charAt(nl - 1) == '\r') {
                line = text.substring(i, nl - 1);
                terminator = "\r\n";
                i = nl + 1;
            } else {
                line = text.substring(i, nl);
                terminator = "\n";
                i = nl + 1;
            }
            if (afflictedKey(line, missing) != null) {
                // Keep the original indentation and key text exactly; only
                // the expression after '=' is pinned to a harmless constant.
                out.append(line, 0, line.indexOf('=')).append("=0");
                count[0]++;
            } else {
                out.append(line);
            }
            out.append(terminator);
        }
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Says it in chat once, the way the shader-mode standdown does: a fix
     * that only ever writes a sibling file is invisible until the player
     * knows to select it.
     */
    public static void maybeAnnounce() {
        if (hint == null || hintShown) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.gui == null) {
            return;
        }
        hintShown = true;
        minecraft.gui.getChat().addMessage(Component.literal("DLSS Style: ")
                .append(Component.literal(hint).withStyle(ChatFormatting.AQUA)));
    }
}
