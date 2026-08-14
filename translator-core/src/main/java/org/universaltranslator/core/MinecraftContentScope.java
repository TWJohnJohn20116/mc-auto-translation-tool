package org.universaltranslator.core;

/** Classifies the active screen without linking the shared core to a Minecraft API version. */
public final class MinecraftContentScope {
    private MinecraftContentScope() {
    }

    public static boolean isVanillaScreen(Object screen) {
        return screen != null && isVanillaClassName(screen.getClass().getName());
    }

    public static boolean isVanillaClassName(String className) {
        return className != null && className.startsWith("net.minecraft.");
    }
}
