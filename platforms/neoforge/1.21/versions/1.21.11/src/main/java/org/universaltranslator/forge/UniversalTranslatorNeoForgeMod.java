package org.universaltranslator.forge;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(value = UniversalTranslatorNeoForgeMod.MOD_ID, dist = Dist.CLIENT)
public final class UniversalTranslatorNeoForgeMod {
    public static final String MOD_ID = "universal_translator";
    public static final Logger LOGGER = LogUtils.getLogger();

    public UniversalTranslatorNeoForgeMod(IEventBus modEventBus) {
        LOGGER.info("MC Auto Translation Tool NeoForge bootstrap loaded");
    }
}
