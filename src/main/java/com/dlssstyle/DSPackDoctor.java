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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * (variable.* / uniform.*) that reference a missing variable, and write a
 * "_fixed" sibling zip with ONLY those lines rewritten to "&lt;key&gt;=0".
 * The original zip is never modified, and the fix is only ever a sibling
 * the player selects deliberately.
 *
 * <p>Since 1.2.2 detection is auto-discovering, not just the configured
 * list: every expression is tokenized and any identifier that is neither
 * pack-defined, in the Iris 1.6 variable set, nor a real biome of THIS
 * Minecraft version (read from the biome registry class, so BIOME_PLAINS
 * passes and BIOME_PALE_GARDEN fails on 1.20.1) marks the line afflicted.
 * A future pack leaning on a newer Iris variable heals with zero config.
 * The old list (packDoctorMissingVars) survives as a forced-missing
 * override, and packDoctorKnownVars is the false-positive escape hatch.
 * Everything fails OPEN: any error in the auto path only means fewer
 * detections, never a broken pack or a broken launch.
 */
public final class DSPackDoctor {
    private static final String PROPS_ENTRY = "shaders/shaders.properties";
    private static final String FIXED_SUFFIX = "_fixed.zip";

    /** Chat hint queued by run(), delivered once by maybeAnnounce(). */
    private static volatile String hint;
    private static boolean hintShown;

    private DSPackDoctor() {
    }

    /** Everything one scan needs, built once per run. */
    private record Ctx(List<String> forcedMissing, boolean auto,
                       Set<String> known, Set<String> biomes) {
    }

    /** What a scan found: afflicted keys, and why (for the log). */
    private record ScanResult(List<String> keys, Set<String> unknowns) {
        boolean clean() {
            return keys.isEmpty();
        }
    }

    /** Scans the runtime shaderpacks folder and writes _fixed siblings. */
    public static void run() {
        try {
            if (!DSConfig.packDoctorEnabled()) {
                return;
            }
            Ctx ctx = new Ctx(DSConfig.packDoctorMissingVars(),
                    DSConfig.packDoctorAuto(), knownVars(), vanillaBiomeVars());
            if (ctx.forcedMissing().isEmpty() && !ctx.auto()) {
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
                    doctorOne(zip, name, ctx, written);
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

    private static void doctorOne(Path zip, String name, Ctx ctx,
                                  List<String> written) {
        try {
            ScanResult found = scan(zip, ctx);
            if (found.clean()) {
                // A _fixed from an earlier (over-eager) detection may still
                // sit beside a pack that is no longer flagged - and a stale
                // heal pins lines a healthy pack needs. Refresh it in place.
                refreshStale(zip, name, ctx);
                return;
            }
            Path fixed = zip.resolveSibling(
                    name.substring(0, name.length() - 4) + FIXED_SUFFIX);
            // Idempotent AND self-validating: a _fixed at least as new as its
            // source only stands if scanning IT comes back clean - so when
            // detection improves (auto-discovery finding lines the old list
            // missed), stale heals regenerate instead of shadowing the fix.
            if (Files.exists(fixed) && Files.getLastModifiedTime(fixed)
                    .compareTo(Files.getLastModifiedTime(zip)) >= 0
                    && scan(fixed, ctx).clean()) {
                // Debug, not info: per-boot "already fixed" notices are noise
                // once the heal has happened. Actual heals stay INFO.
                DLSSStyle.LOGGER.debug(
                        "Pack doctor: {} references missing variables {} - fixed copy"
                        + " already present ({})", name, found.keys(),
                        fixed.getFileName());
                return;
            }
            int patched = patch(zip, fixed, ctx);
            DLSSStyle.LOGGER.info(
                    "Pack doctor: {} references variables this shader mod build does"
                    + " not provide{} - neutralised {} expression line(s) {} into {}",
                    name,
                    found.unknowns().isEmpty() ? "" : " " + found.unknowns(),
                    patched, found.keys(), fixed.getFileName());
            written.add(name);
        } catch (Throwable t) {
            DLSSStyle.LOGGER.warn("Pack doctor could not process {}", name, t);
        }
    }

    /** Scans one zip's shaders.properties; clean() when the pack is healthy. */
    static ScanResult scan(Path zip, Ctx ctx) throws IOException {
        byte[] props = readProps(zip);
        if (props == null) {
            // Not a shader pack (or an odd layout): skip.
            return new ScanResult(List.of(), Set.of());
        }
        return decide(new String(props, StandardCharsets.ISO_8859_1), ctx).result;
    }

    private static byte[] readProps(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry entry = file.getEntry(PROPS_ENTRY);
            return entry == null ? null
                    : file.getInputStream(entry).readAllBytes();
        }
    }

    /**
     * When a pack scans clean but an older doctor left a _fixed beside it
     * (detection improves; the first auto build flagged healthy matrix
     * lines), the stale heal is actively harmful - it pins lines the pack
     * needs. Rewrite it as a faithful copy rather than deleting it: the
     * player's shader selection may point at the _fixed name.
     */
    private static void refreshStale(Path zip, String name, Ctx ctx)
            throws IOException {
        Path fixed = zip.resolveSibling(
                name.substring(0, name.length() - 4) + FIXED_SUFFIX);
        if (!Files.exists(fixed)) {
            return;
        }
        byte[] source = readProps(zip);
        byte[] healed = readProps(fixed);
        if (source == null || java.util.Arrays.equals(source, healed)) {
            return;     // already faithful - nothing stale about it
        }
        patch(zip, fixed, ctx);     // zero afflicted lines = plain copy
        DLSSStyle.LOGGER.info(
                "Pack doctor: {} is no longer flagged - stale heal {} refreshed"
                + " to match the original", name, fixed.getFileName());
    }

    /** Every per-line decision for one properties text, made once. */
    private static final class Decision {
        final List<String> logicals;
        final boolean[] pin;
        final ScanResult result;

        Decision(List<String> logicals, boolean[] pin, ScanResult result) {
            this.logicals = logicals;
            this.pin = pin;
            this.result = result;
        }
    }

    /**
     * Properties are LAST-KEY-WINS: photon v1.3b declares the pale-garden
     * uniform with a 1.21 expression and immediately re-declares it "= 0.0"
     * for older targets - the parser only ever sees the final value, so the
     * first line is dead code and must not count. Only the last definition
     * of each key is eligible for affliction, exactly mirroring what the
     * shader mod will actually evaluate.
     */
    private static Decision decide(String text, Ctx ctx) {
        List<String> logicals = logicalLines(text);
        Set<String> defined = new HashSet<>();
        java.util.Map<String, Integer> lastDef = new java.util.HashMap<>();
        for (int i = 0; i < logicals.size(); i++) {
            String key = expressionKey(logicals.get(i));
            if (key == null) {
                continue;
            }
            lastDef.put(key, i);
            int dot = key.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < key.length()) {
                defined.add(key.substring(dot + 1));
            }
        }
        boolean[] pin = new boolean[logicals.size()];
        List<String> keys = new ArrayList<>();
        Set<String> unknowns = new LinkedHashSet<>();
        for (int i = 0; i < logicals.size(); i++) {
            String key = expressionKey(logicals.get(i));
            if (key == null || lastDef.get(key) != i) {
                continue;
            }
            if (afflictedKey(logicals.get(i), defined, ctx, unknowns) != null) {
                pin[i] = true;
                keys.add(key);
            }
        }
        return new Decision(logicals, pin, new ScanResult(keys, unknowns));
    }

    /** The key of a variable./uniform. expression line, or null. */
    private static String expressionKey(String logical) {
        String trimmed = logical.trim();
        if (!trimmed.startsWith("variable.") && !trimmed.startsWith("uniform.")) {
            return null;
        }
        int eq = trimmed.indexOf('=');
        return eq < 0 ? null : trimmed.substring(0, eq).trim();
    }

    // ── the detection rule ──

    /**
     * An EXPRESSION line (variable.* / uniform.*) is afflicted when its text
     * mentions a forced-missing variable (whole-line substring on purpose -
     * it catches both a value reading the variable and the uniform NAMED for
     * it, which is fed from the poisoned chain), or - auto mode - when its
     * expression contains any identifier the shader mod does not provide.
     * Returns the key, or null for a healthy line.
     */
    private static String afflictedKey(String rawLine, Set<String> defined,
                                       Ctx ctx, Set<String> unknowns) {
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
        // An already-pinned line is healthy BY DEFINITION - without this,
        // our own "endFlashIntensityM=0" keeps substring-matching the
        // forced list through its KEY and the heal regenerates every boot
        // (caught live: CR re-healed on three consecutive launches).
        if (line.substring(eq + 1).trim().matches("0(\\.0+)?")) {
            return null;
        }
        for (String var : ctx.forcedMissing()) {
            if (!var.isEmpty() && line.contains(var)) {
                return line.substring(0, eq).trim();
            }
        }
        if (ctx.auto()) {
            List<String> unknown =
                    unknownIdents(line.substring(eq + 1), defined, ctx);
            if (!unknown.isEmpty()) {
                unknowns.addAll(unknown);
                return line.substring(0, eq).trim();
            }
        }
        return null;
    }

    /**
     * Identifiers in an expression that nothing provides. Member accesses
     * (sunPosition.y - the 'y'), function calls (smooth(...), in(...)) and
     * number literals are not variable references and are skipped.
     */
    private static List<String> unknownIdents(String value, Set<String> defined,
                                              Ctx ctx) {
        List<String> unknown = new ArrayList<>();
        int i = 0;
        int n = value.length();
        while (i < n) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                // Number literal: consume 0.5 / 1e3 / 0xFF whole, so the
                // letters inside never masquerade as identifiers.
                i++;
                while (i < n && (Character.isLetterOrDigit(value.charAt(i))
                        || value.charAt(i) == '.')) {
                    i++;
                }
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(value.charAt(i))
                        || value.charAt(i) == '_')) {
                    i++;
                }
                String id = value.substring(start, i);
                boolean member = start > 0 && value.charAt(start - 1) == '.';
                int j = i;
                while (j < n && (value.charAt(j) == ' ' || value.charAt(j) == '\t')) {
                    j++;
                }
                boolean call = j < n && value.charAt(j) == '(';
                if (!member && !call && !LITERALS.contains(id)
                        && !provided(id, defined, ctx) && !unknown.contains(id)) {
                    unknown.add(id);
                }
                continue;
            }
            i++;
        }
        return unknown;
    }

    private static boolean provided(String id, Set<String> defined, Ctx ctx) {
        if (defined.contains(id) || ctx.known().contains(id)) {
            return true;
        }
        if (id.startsWith("BIOME_")) {
            // A biome constant is real exactly when the biome is: checked
            // against this Minecraft version's own registry class. Fail
            // OPEN when that reflection produced nothing.
            return ctx.biomes().isEmpty() || ctx.biomes().contains(id);
        }
        return false;
    }

    // ── what the installed shader mod provides ──

    /** Word-shaped things in expressions that are never variable lookups. */
    private static final Set<String> LITERALS = Set.of(
            "true", "false", "if", "else", "in", "smooth",
            "PI", "pi", "E", "e");

    /**
     * The Iris 1.6.x / OptiFine-heritage variable set usable in custom
     * uniform expressions - the Oculus 1.20.1 line is frozen, so this list
     * is stable. Deliberately GENEROUS: including a name Oculus lacks only
     * means auto-detection misses it (status quo, the forced list still
     * catches it); omitting a real one would pin healthy lines. Notably
     * ABSENT on purpose: endFlashIntensity (Iris 1.21) - the measured CR
     * strobe cause, which auto mode must keep catching.
     */
    private static final Set<String> IRIS_16_VARS = Set.of(
            // held item / fog / colour
            "heldItemId", "heldItemId2", "heldBlockLightValue",
            "heldBlockLightValue2", "fogMode", "fogShape", "fogDensity",
            "fogStart", "fogEnd", "fogColor", "skyColor", "entityColor",
            // time / weather / sky
            "worldTime", "worldDay", "moonPhase", "frameCounter", "frameTime",
            "frameTimeCounter", "sunAngle", "shadowAngle", "rainStrength",
            "wetness", "thunderStrength", "lightningBoltPosition", "cloudTime",
            // camera / viewport
            "aspectRatio", "viewWidth", "viewHeight", "near", "far",
            "sunPosition", "moonPosition", "shadowLightPosition", "upPosition",
            "cameraPosition", "previousCameraPosition", "eyeAltitude",
            "eyeBrightness", "eyeBrightnessSmooth", "centerDepthSmooth",
            "terrainTextureSize", "terrainIconSize", "atlasSize",
            // player state
            "isEyeInWater", "nightVision", "blindness", "darknessFactor",
            "darknessLightFactor", "screenBrightness", "hideGUI", "playerMood",
            "constantMood", "currentPlayerAir", "maxPlayerAir",
            "currentPlayerArmor", "maxPlayerArmor", "currentPlayerHealth",
            "maxPlayerHealth", "currentPlayerHunger", "maxPlayerHunger",
            "is_alive", "is_burning", "is_hurt", "is_invisible", "is_on_ground",
            "is_sneaking", "is_sprinting", "is_riding",
            // ids / misc
            "entityId", "blockEntityId", "currentRenderedItemId", "blendFunc",
            "instanceId", "renderStage",
            // matrices - element access (gbufferProjection.0.0) is legal
            // expression syntax, field-proven by photon v1.3b which derives
            // sun_dir from gbufferModelViewInverse and loads with zero
            // parser errors (2026-08-13 empirical launch; the first draft of
            // this list omitted these and false-positived NINE packs)
            "gbufferModelView", "gbufferModelViewInverse",
            "gbufferPreviousModelView", "gbufferProjection",
            "gbufferProjectionInverse", "gbufferPreviousProjection",
            "shadowModelView", "shadowModelViewInverse", "shadowProjection",
            "shadowProjectionInverse",
            // Distant Horizons compat uniforms - provided by Oculus itself
            // (photon's combined_far = dhRenderDistance resolves without DH
            // installed, same empirical launch)
            "dhNearPlane", "dhFarPlane", "dhRenderDistance",
            // Iris-exclusive world/dimension info
            "biome", "biome_category", "biome_precipitation", "temperature",
            "rainfall", "ambientLight", "bedrockLevel", "cloudHeight",
            "hasCeiling", "hasSkylight", "heightLimit", "logicalHeightLimit",
            // biome categories (Iris defines these statically)
            "CAT_NONE", "CAT_TAIGA", "CAT_EXTREME_HILLS", "CAT_JUNGLE",
            "CAT_MESA", "CAT_PLAINS", "CAT_SAVANNA", "CAT_ICY", "CAT_THE_END",
            "CAT_BEACH", "CAT_FOREST", "CAT_OCEAN", "CAT_DESERT", "CAT_RIVER",
            "CAT_SWAMP", "CAT_MUSHROOM", "CAT_NETHER", "CAT_UNDERGROUND",
            "CAT_MOUNTAIN",
            // precipitation constants
            "PPT_NONE", "PPT_RAIN", "PPT_SNOW");

    private static Set<String> knownVars() {
        Set<String> known = new HashSet<>(IRIS_16_VARS);
        known.addAll(DSConfig.packDoctorKnownVars());
        // Forced-missing beats known: the config's word is final both ways.
        DSConfig.packDoctorMissingVars().forEach(known::remove);
        return known;
    }

    /**
     * BIOME_* constants that are real on THIS Minecraft version, from the
     * vanilla registry class - iterated by field type, never by name, so
     * reobfuscation cannot break it. On 1.20.1 this yields BIOME_PLAINS et
     * al and NOT BIOME_PALE_GARDEN, which is the whole point.
     */
    private static Set<String> vanillaBiomeVars() {
        Set<String> out = new HashSet<>();
        try {
            for (java.lang.reflect.Field field
                    : net.minecraft.world.level.biome.Biomes.class.getFields()) {
                Object value = field.get(null);
                if (value instanceof net.minecraft.resources.ResourceKey<?> key) {
                    out.add("BIOME_" + key.location().getPath()
                            .toUpperCase(Locale.ROOT));
                }
            }
        } catch (Throwable t) {
            return Set.of();    // fail open: BIOME_* all pass
        }
        return out;
    }

    // ── logical lines (properties '\' continuations) ──

    /**
     * shaders.properties is a Java properties file: a trailing backslash
     * continues the line, with the next line's leading whitespace stripped.
     * Detection must see the JOINED expression (a missing variable can sit
     * on any physical line), and a pin must replace the whole logical line -
     * pinning only the first physical line would orphan its continuations
     * into the file as garbage keys.
     */
    private static List<String> logicalLines(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean continuing = false;
        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r")
                    ? raw.substring(0, raw.length() - 1) : raw;
            String piece = continuing ? line.stripLeading() : line;
            if (piece.endsWith("\\")) {
                current.append(piece, 0, piece.length() - 1);
                continuing = true;
                continue;
            }
            current.append(piece);
            out.add(current.toString());
            current.setLength(0);
            continuing = false;
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    // ── writing the heal ──

    /**
     * Writes the _fixed sibling: every entry byte-copied, except that
     * afflicted expression lines in shaders.properties become "key=0"
     * (indentation and line endings preserved, everything else untouched).
     * Built in a temp file and moved into place so a crash mid-write can
     * never leave a half zip where a pack is expected.
     */
    private static int patch(Path source, Path fixed, Ctx ctx)
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
                        out.write(patchProperties(data, ctx, count));
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

    /** Rewrites afflicted logical lines as "key=0"; all other bytes unchanged. */
    private static byte[] patchProperties(byte[] data, Ctx ctx, int[] count) {
        String text = new String(data, StandardCharsets.ISO_8859_1);
        Decision decision = decide(text, ctx);
        StringBuilder out = new StringBuilder(text.length());
        // Walk PHYSICAL lines but decide per LOGICAL line: buffer a line's
        // raw text (terminators included) until its continuations end, then
        // emit either the untouched buffer or one "key=0" with the last
        // physical terminator. The nth completed logical line here matches
        // logicalLines(text)'s nth entry - decide()'s pin array indexes it.
        StringBuilder raw = new StringBuilder();
        StringBuilder logical = new StringBuilder();
        boolean continuing = false;
        int logicalIndex = 0;
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
            raw.append(line).append(terminator);
            String piece = continuing ? line.stripLeading() : line;
            if (piece.endsWith("\\")) {
                logical.append(piece, 0, piece.length() - 1);
                continuing = true;
                continue;
            }
            logical.append(piece);
            String joined = logical.toString();
            if (logicalIndex < decision.pin.length && decision.pin[logicalIndex]) {
                // Keep the original indentation and key text exactly; only
                // the expression after '=' is pinned to a harmless constant.
                out.append(joined, 0, joined.indexOf('=')).append("=0")
                        .append(terminator);
                count[0]++;
            } else {
                out.append(raw);
            }
            raw.setLength(0);
            logical.setLength(0);
            continuing = false;
            logicalIndex++;
        }
        if (raw.length() > 0) {
            out.append(raw);
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
