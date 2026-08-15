package org.universaltranslator.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Opens the existing settings screen from Mod Menu's config button. */
public final class ModMenuIntegration implements ModMenuApi {
    private static final Logger LOGGER = Logger.getLogger("universal_translator");

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            try {
                return new UniversalTranslatorConfigScreen(
                        parent, FabricConfig.load(FabricLoader.getInstance().getConfigDir()));
            } catch (Exception exception) {
                LOGGER.log(Level.SEVERE, "Could not open MC Auto Translation Tool settings from Mod Menu",
                        exception);
                return parent;
            }
        };
    }
}
