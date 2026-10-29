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
        // 1.21.9 dropped Bus.MOD for key mappings, so this entrypoint registers the listener itself
        // instead of relying on an @EventBusSubscriber annotation. The constructor runs on both
        // sides, though: without the gate a dedicated server loads UniversalTranslatorForgeClient,
        // initialises its KeyMapping statics (GLFW key codes, KeyMapping.Category.MISC) and subscribes
        // a client-only listener bus. The 1.21-1.21.8 and NeoForge entrypoints both stay client-only
        // (annotation value = Dist.CLIENT / Mod#dist), so this is the only one that had to be told.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            UniversalTranslatorForgeClient.registerDirectEvents();
        }
        LOGGER.info("MC Auto Translation Tool Forge bootstrap loaded");
    }
}
