package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

/**
 * Compile-only stand-in for Mod Menu's config screen factory. Not packaged into release JARs.
 *
 * <p>Because {@code modmenu.gradle} excludes this package from the jar, the class that ships is
 * Mod Menu's own, and the lambda in the integration class is linked against <em>this</em>
 * declaration: the invokedynamic site records the interface and method name written here. A name
 * that does not match the real library therefore fails at link time ({@code AbstractMethodError}
 * when the player opens the Mod Menu config button) rather than at build time, and nothing in the
 * repository compares the two.
 *
 * <p>Contract to check whenever Mod Menu is upgraded: {@code ModMenuApi.getModConfigScreenFactory()}
 * returns {@code ConfigScreenFactory<?>}, and the factory declares
 * {@code S createScreen(Screen parent)}. Keep this stub's method name identical to that one, or
 * replace the stub with a {@code modCompileOnly} dependency on the real artifact — see audit3 #134.
 */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
    S create(Screen parent);
}
