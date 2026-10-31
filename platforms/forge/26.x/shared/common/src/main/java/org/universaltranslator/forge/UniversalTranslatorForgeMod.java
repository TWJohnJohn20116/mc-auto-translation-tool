package org.universaltranslator.forge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(UniversalTranslatorForgeMod.MOD_ID)
public final class UniversalTranslatorForgeMod {
    public static final String MOD_ID = "universal_translator";
    public static final Logger LOGGER = LogUtils.getLogger();

    public UniversalTranslatorForgeMod() {
        // The constructor runs on both sides. Without the gate a dedicated server loads
        // UniversalTranslatorForgeClient, initialises its KeyMapping statics (GLFW key codes,
        // KeyMapping.Category.MISC) and subscribes a client-only listener bus; mods.toml has no key
        // that keeps a Forge mod off the server.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            UniversalTranslatorForgeClient.registerDirectEvents();
        }
        LOGGER.info("MC Auto Translation Tool Forge bootstrap loaded");
    }
}
