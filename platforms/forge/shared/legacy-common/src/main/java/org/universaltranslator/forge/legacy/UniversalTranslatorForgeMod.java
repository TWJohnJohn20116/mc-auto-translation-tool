package org.universaltranslator.forge.legacy;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = UniversalTranslatorForgeMod.MOD_ID,
        name = "MC Auto Translation Tool",
        // Left empty so FML resolves the version from the mcmod.info metadata that the
        // build stamps with gradle.properties mod_version, instead of a stale constant.
        version = "",
        clientSideOnly = true,
        acceptableRemoteVersions = "*")
public final class UniversalTranslatorForgeMod {
    public static final String MOD_ID = "universal_translator";

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        Logger logger = event.getModLog();
        try {
            LegacyConfig config = LegacyConfig.load(event.getModConfigurationDirectory());
            LegacyTranslationRuntime.initialize(config);
            LegacyClientEvents.initialize(event.getModConfigurationDirectory());
            logger.info("MC Auto Translation Tool initialized; enabled={}", config.enabled);
        } catch (Exception exception) {
            LegacyTranslationRuntime.shutdown();
            logger.error("MC Auto Translation Tool configuration failed; translation remains disabled", exception);
        }
    }
}
