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

    private static volatile int settings = DEFAULT_SETTINGS;
    private static volatile int toggle = DEFAULT_TOGGLE;

    private HotkeyKeys() {
    }

    /** Installs the configured codes; {@code null} or an unknown name keeps the default. */
    public static void install(String settingsName, String toggleName) {
        settings = codeOf(settingsName, DEFAULT_SETTINGS);
        toggle = codeOf(toggleName, DEFAULT_TOGGLE);
    }

    /** Code the settings screen opens on. */
    public static int settingsCode() {
        return settings;
    }

    /** Code that toggles translation. */
    public static int toggleCode() {
        return toggle;
    }

    /** Configured name of the settings key, for the diagnostics screen. */
    public static String settingsName() {
        return nameOf(settings);
    }

    /** Configured name of the toggle key, for the diagnostics screen. */
    public static String toggleName() {
        return nameOf(toggle);
    }

    /**
     * Resolves a configured key name to its GLFW code.
     *
     * <p>Accepts a single letter or digit, {@code F1}-{@code F24}, and the word names below. Comparison
     * ignores case and surrounding whitespace, and a leading {@code KEY_} / {@code GLFW_KEY_} prefix is
     * tolerated so a value copied from a log still works.
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

    /** Inverse of {@link #codeOf(String, int)} for the codes it can produce. */
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
            case 340: return "LEFT_SHIFT";
            case 341: return "LEFT_CONTROL";
            case 342: return "LEFT_ALT";
            default: return String.valueOf(code);
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
        return lwjgl2CodeOf(settings);
    }

    /** Toggle key in LWJGL2 numbering; negative when the configured key has no LWJGL2 equivalent. */
    public static int toggleLwjgl2Code() {
        return lwjgl2CodeOf(toggle);
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
        return -1;
    }
}