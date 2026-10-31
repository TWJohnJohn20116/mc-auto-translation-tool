package org.universaltranslator.forge.legacy;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

import java.io.File;

@Mod(
        modid = UniversalTranslatorForgeMod.MOD_ID,
        name = "MC Auto Translation Tool",
        // Left empty so FML falls back to whatever version metadata the jar carries instead of a
        // stale constant. Note that this jar ships no mcmod.info, so the versions on the 1.8.9 and
        // 1.12.2 mod lists are empty until that metadata file exists.
        version = "",
        clientSideOnly = true,
        acceptableRemoteVersions = "*")
public final class UniversalTranslatorForgeMod {
    public static final String MOD_ID = "universal_translator";

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        Logger logger = event.getModLog();
        File configDirectory = event.getModConfigurationDirectory();
        // The client integration is registered before the configuration is read, and separately:
        // the two used to share one try block, so any failure in the load (a read-only directory, a
        // provider that cannot be built, an unusable cache path) also skipped the key bindings and
        // the event bus registration. The settings screen is the only way to repair a broken
        // setting, and both of its entry points live on the registered instance — so "the settings
        // are broken" also meant "the repair UI cannot be opened". initialize() is idempotent.
        try {
            LegacyClientEvents.initialize(configDirectory);
        } catch (Exception registrationFailure) {
            logger.error("MC Auto Translation Tool client integration failed", registrationFailure);
        }
        try {
            LegacyConfig config = LegacyConfig.load(configDirectory);
            LegacyTranslationRuntime.initialize(config);
            logger.info("MC Auto Translation Tool initialized; enabled={}", config.enabled);
        } catch (Exception exception) {
            LegacyTranslationRuntime.shutdown();
            logger.error("MC Auto Translation Tool configuration failed; translation remains disabled", exception);
        }
    }
}
