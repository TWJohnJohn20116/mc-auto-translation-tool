package com.terraformersmc.modmenu.api;

/** Compile-only stand-in for Mod Menu's entrypoint API. Not packaged into release JARs. */
public interface ModMenuApi {
    ConfigScreenFactory<?> getModConfigScreenFactory();
}
