package org.universaltranslator.forge;

import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(UniversalTranslatorForgeMod.MOD_ID)
public final class UniversalTranslatorForgeMod {
    public static final String MOD_ID = "universal_translator";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public UniversalTranslatorForgeMod() {
        LOGGER.info("MC Auto Translation Tool Forge 1.16.5 bootstrap loaded");
    }
}
