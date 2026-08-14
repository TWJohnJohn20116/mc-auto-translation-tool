package org.universaltranslator.forge.legacy;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = UniversalTranslatorForgeMod.MOD_ID,
        name = "MC Auto Translation Tool",
        version = UniversalTranslatorForgeMod.VERSION,
        clientSideOnly = true,
        acceptableRemoteVersions = "*")
public final class UniversalTranslatorForgeMod {
    public static final String MOD_ID = "universal_translator";
    public static final String VERSION = "1.3.2";

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
