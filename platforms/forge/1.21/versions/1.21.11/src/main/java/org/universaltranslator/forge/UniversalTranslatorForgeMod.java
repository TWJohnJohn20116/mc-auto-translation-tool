package org.universaltranslator.forge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(UniversalTranslatorForgeMod.MOD_ID)
public final class UniversalTranslatorForgeMod {
    public static final String MOD_ID = "universal_translator";
    public static final Logger LOGGER = LogUtils.getLogger();

    public UniversalTranslatorForgeMod() {
        UniversalTranslatorForgeClient.registerDirectEvents();
        LOGGER.info("MC Auto Translation Tool Forge bootstrap loaded");
    }
}
