package org.universaltranslator.core;

import java.util.Locale;

/**
 * The two hotkeys of the legacy Fabric builds, as raw GLFW key codes.
 *
 * <p>Fabric 1.0-1.13.2 has no key-binding API: the 1.13 tree does not even depend on Fabric API, so
 * {@code KeyBindingHelper} does not exist there and there is no way to put an entry into the vanilla
 * "Controls" screen from a mod of that generation. The keys were therefore hard-coded to U and F8 in
 * every {@code MinecraftMixin}, which meant a player whose keyboard needs another key (or who has
 * another mod on U) could not change or unbind them.
 *
 * <p>They are configurable now, through the shared legacy config file
 * ({@code universal-translator.properties}, keys {@code settings-key} / {@code toggle-key}). The value
 * is a GLFW key name: a letter, a digit, {@code F1}-{@code F12}, or one of the names in
 * {@link #codeOf(String, int)}. This class deliberately keeps the GLFW numbering as plain integers so
 * the core does not take a dependency on LWJGL.
 */
public final class HotkeyKeys {
    /** {@code GLFW_KEY_U}. */
    public static final int DEFAULT_SETTINGS = 85;
    /** {@code GLFW_KEY_F8}. */
    public static final int DEFAULT_TOGGLE = 297;

    /** Highest key code this class can name; anything above it is a typo, not a key. */
    private static final int MAXIMUM_CODE = 400;
    /** Longest decimal key code accepted; every code this class knows has three digits. */
    private static final int MAXIMUM_NUMERIC_CHARS = 3;

    /**
     * The installed pair, published as one reference.
     *
     * <p>The two codes and the two configured names used to be four separate volatile fields, so a
     * reader could see the new code beside the old name while {@link #install} was halfway through;
     * the settings screen then saved that pair and the hotkey reverted on the next start.
     */
    private static volatile Bindings bindings = new Bindings(
            DEFAULT_SETTINGS, DEFAULT_TOGGLE, null, null);

    private HotkeyKeys() {
    }

    /**
     * Installs the configured codes; {@code null} or an unknown name keeps the default.
     *
     * <p>The value is remembered as given when it resolves to the installed code, so a save writes
     * back what the player typed (including a numeric code) instead of a re-derived name.
     */
    public static void install(String settingsName, String toggleName) {
        int settingsCode = codeOf(settingsName, DEFAULT_SETTINGS);
        int toggleCode = codeOf(toggleName, DEFAULT_TOGGLE);
        bindings = new Bindings(settingsCode, toggleCode,
                sourceNameOf(settingsName, settingsCode),
                sourceNameOf(toggleName, toggleCode));
    }

    /** Code the settings screen opens on. */
    public static int settingsCode() {
        return bindings.settings;
    }

    /** Code that toggles translation. */
    public static int toggleCode() {
        return bindings.toggle;
    }

    /** Configured name of the settings key, for the diagnostics screen and for saving. */
    public static String settingsName() {
        Bindings installed = bindings;
        return installed.settingsSource != null
                ? installed.settingsSource : nameOf(installed.settings);
    }

    /** Configured name of the toggle key, for the diagnostics screen and for saving. */
    public static String toggleName() {
        Bindings installed = bindings;
        return installed.toggleSource != null
                ? installed.toggleSource : nameOf(installed.toggle);
    }

    /**
     * The text to write back for a configured value.
     *
     * <p>The value the file held is kept as written, so a save does not rewrite a spelling the player
     * chose. The one exception is a bare number — that is what the build without a complete
     * {@link #nameOf(int)} wrote, and saving the name instead heals the entry the first time the
     * settings are saved.
     *
     * @param configured value read from the settings file, possibly {@code null}
     * @param code code {@link #codeOf(String, int)} produced for that value
     * @return a value that maps back to {@code code}
     */
    private static String sourceNameOf(String configured, int code) {
        if (configured != null) {
            String trimmed = configured.trim();
            if (!trimmed.isEmpty() && codeOf(trimmed, -1) == code) {
                boolean numeric = numericCodeOrZero(trimmed.toUpperCase(Locale.ROOT)) > 0;
                return numeric ? nameOf(code) : trimmed;
            }
        }
        return nameOf(code);
    }

    /**
     * Resolves a configured key name to its GLFW code.
     *
     * <p>Accepts a single letter or digit, {@code F1}-{@code F24}, the 26 word names below, and a
     * plain decimal code (an older build could only write the number). Comparison ignores case and
     * surrounding whitespace, and a leading {@code KEY_} / {@code GLFW_KEY_} prefix is tolerated so a
     * value copied from a log still works.
     */
    public static int codeOf(String name, int fallback) {
        if (name == null) {
            return fallback;
        }
        String value = name.trim().toUpperCase(Locale.ROOT).replace("GLFW_KEY_", "").replace("KEY_", "");
        if (value.isEmpty()) {
            return fallback;
        }
        if (value.length() == 1) {
            char c = value.charAt(0);
            if (c >= 'A' && c <= 'Z') {
                return c; // GLFW_KEY_A == 65
            }
            if (c >= '0' && c <= '9') {
                return c; // GLFW_KEY_0 == 48
            }
            return fallback;
        }
        int numeric = numericCodeOrZero(value);
        if (numeric > 0) {
            // A settings file written by a build that only had nameOf(int) holds the raw code
            // ("settings-key=262"); it means the same key as "RIGHT" and stays readable.
            return numeric;
        }
        if (value.startsWith("F")) {
            try {
                int number = Integer.parseInt(value.substring(1));
                if (number >= 1 && number <= 24) {
                    return 289 + number; // GLFW_KEY_F1 == 290
                }
            } catch (NumberFormatException ignored) {
                return fallback;
            }
            return fallback;
        }
        if ("SPACE".equals(value)) return 32;
        if ("ENTER".equals(value) || "RETURN".equals(value)) return 257;
        if ("TAB".equals(value)) return 258;
        if ("ESC".equals(value) || "ESCAPE".equals(value)) return 256;
        if ("BACKSPACE".equals(value)) return 259;
        if ("INSERT".equals(value)) return 260;
        if ("DELETE".equals(value)) return 261;
        if ("RIGHT".equals(value)) return 262;
        if ("LEFT".equals(value)) return 263;
        if ("DOWN".equals(value)) return 264;
        if ("UP".equals(value)) return 265;
        if ("PAGE_UP".equals(value) || "PAGEUP".equals(value)) return 266;
        if ("PAGE_DOWN".equals(value) || "PAGEDOWN".equals(value)) return 267;
        if ("HOME".equals(value)) return 268;
        if ("END".equals(value)) return 269;
        if ("CAPS_LOCK".equals(value)) return 280;
        if ("SCROLL_LOCK".equals(value)) return 281;
        if ("NUM_LOCK".equals(value)) return 282;
        if ("PRINT_SCREEN".equals(value)) return 283;
        if ("PAUSE".equals(value)) return 284;
        if ("LEFT_SHIFT".equals(value)) return 340;
        if ("LEFT_CONTROL".equals(value)) return 341;
        if ("LEFT_ALT".equals(value)) return 342;
        if ("RIGHT_SHIFT".equals(value)) return 344;
        if ("RIGHT_CONTROL".equals(value)) return 345;
        if ("RIGHT_ALT".equals(value)) return 346;
        return fallback;
    }

    /**
     * Inverse of {@link #codeOf(String, int)} for every code that method can produce.
     *
     * <p>All 26 word names {@code codeOf} accepts are covered, so {@code nameOf(codeOf(name))} is
     * {@code name} for every name in this class. A code with no name is returned as its decimal
     * form, which {@code codeOf} reads back.
     */
    public static String nameOf(int code) {
        if (code >= 65 && code <= 90) {
            return String.valueOf((char) code);
        }
        if (code >= 48 && code <= 57) {
            return String.valueOf((char) code);
        }
        if (code >= 290 && code <= 313) {
            return "F" + (code - 289);
        }
        switch (code) {
            case 32: return "SPACE";
            case 256: return "ESCAPE";
            case 257: return "ENTER";
            case 258: return "TAB";
            case 259: return "BACKSPACE";
            case 260: return "INSERT";
            case 261: return "DELETE";
            case 262: return "RIGHT";
            case 263: return "LEFT";
            case 264: return "DOWN";
            case 265: return "UP";
            case 266: return "PAGE_UP";
            case 267: return "PAGE_DOWN";
            case 268: return "HOME";
            case 269: return "END";
            case 280: return "CAPS_LOCK";
            case 281: return "SCROLL_LOCK";
            case 282: return "NUM_LOCK";
            case 283: return "PRINT_SCREEN";
            case 284: return "PAUSE";
            case 340: return "LEFT_SHIFT";
            case 341: return "LEFT_CONTROL";
            case 342: return "LEFT_ALT";
            case 344: return "RIGHT_SHIFT";
            case 345: return "RIGHT_CONTROL";
            case 346: return "RIGHT_ALT";
            default: return String.valueOf(code);
        }
    }

    /**
     * A key code a settings file stored as a number.
     *
     * <p>Only a code this class can name is a key. Accepting every number up to
     * {@link #MAXIMUM_CODE} let a typo such as {@code settings-key=150} install a code no keyboard
     * sends: the key never fired, {@link #nameOf(int)} reported "150" as if it were a name, and the
     * bad value was written back instead of healing.
     *
     * @param value upper-case value with the {@code KEY_} prefixes already removed
     * @return the code, or {@code 0} when the value is not a plausible key code
     */
    private static int numericCodeOrZero(String value) {
        if (value.isEmpty() || value.length() > MAXIMUM_NUMERIC_CHARS) {
            return 0;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return 0;
            }
        }
        try {
            int code = Integer.parseInt(value);
            if (code <= 0 || code > MAXIMUM_CODE) {
                return 0;
            }
            // nameOf returns the decimal form exactly when it has no name for the code.
            return String.valueOf(code).equals(nameOf(code)) ? 0 : code;
        } catch (NumberFormatException tooLarge) {
            return 0;
        }
    }

    // LWJGL2 (used by the Ornithe trees, 1.0-1.12) numbers keys differently from GLFW, so the same
    // configured name has to be translated once more. Index = GLFW code offset.
    private static final int[] LWJGL2_LETTERS = {
            30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
            49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44
    };
    private static final int[] LWJGL2_DIGITS = {11, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    private static final int[] LWJGL2_FKEYS = {59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88};

    /** Settings key in LWJGL2 numbering; negative when the configured key has no LWJGL2 equivalent. */
    public static int settingsLwjgl2Code() {
        return lwjgl2CodeOf(bindings.settings);
    }

    /** Toggle key in LWJGL2 numbering; negative when the configured key has no LWJGL2 equivalent. */
    public static int toggleLwjgl2Code() {
        return lwjgl2CodeOf(bindings.toggle);
    }

    private static int lwjgl2CodeOf(int glfwCode) {
        if (glfwCode >= 65 && glfwCode <= 90) {
            return LWJGL2_LETTERS[glfwCode - 65];
        }
        if (glfwCode >= 48 && glfwCode <= 57) {
            return LWJGL2_DIGITS[glfwCode - 48];
        }
        if (glfwCode >= 290 && glfwCode <= 301) {
            return LWJGL2_FKEYS[glfwCode - 290];
        }
        if (glfwCode == 32) return 57;   // Keyboard.KEY_SPACE
        if (glfwCode == 257) return 28;  // Keyboard.KEY_RETURN
        if (glfwCode == 258) return 15;  // Keyboard.KEY_TAB
        if (glfwCode == 256) return 1;   // Keyboard.KEY_ESCAPE
        // The navigation keys exist in LWJGL2 as well, so leaving them out made a configured DELETE or
        // PAGE_DOWN a hotkey that silently never fired on the Ornithe trees.
        if (glfwCode == 260) return 210; // Keyboard.KEY_INSERT
        if (glfwCode == 261) return 211; // Keyboard.KEY_DELETE
        if (glfwCode == 268) return 199; // Keyboard.KEY_HOME
        if (glfwCode == 269) return 207; // Keyboard.KEY_END
        if (glfwCode == 266) return 201; // Keyboard.KEY_PRIOR (page up)
        if (glfwCode == 267) return 209; // Keyboard.KEY_NEXT (page down)
        if (glfwCode == 265) return 200; // Keyboard.KEY_UP
        if (glfwCode == 264) return 208; // Keyboard.KEY_DOWN
        if (glfwCode == 263) return 203; // Keyboard.KEY_LEFT
        if (glfwCode == 262) return 205; // Keyboard.KEY_RIGHT
        return -1;
    }

    /** One installed pair of hotkeys: the two codes and the text the settings file held for them. */
    private static final class Bindings {
        private final int settings;
        private final int toggle;
        private final String settingsSource;
        private final String toggleSource;

        private Bindings(int settings, int toggle, String settingsSource, String toggleSource) {
            this.settings = settings;
            this.toggle = toggle;
            this.settingsSource = settingsSource;
            this.toggleSource = toggleSource;
        }
    }
}
