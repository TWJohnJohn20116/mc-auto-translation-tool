package org.universaltranslator.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import org.universaltranslator.core.LogMessages;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Opens the existing settings screen from Mod Menu's config button. */
public final class ModMenuIntegration implements ModMenuApi {
    private static final Logger LOGGER = Logger.getLogger("universal_translator");

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // Written out instead of a lambda on purpose: a lambda links against whatever
        // ConfigScreenFactory declares, so a stub whose abstract method is misnamed still compiles
        // and only fails at run time with AbstractMethodError when the player presses the config
        // button. Naming the method here turns that into a compile error.
        return new ConfigScreenFactory<Screen>() {
            @Override
            public Screen createScreen(Screen parent) {
                try {
                    return new UniversalTranslatorConfigScreen(
                            parent, FabricConfig.load(FabricLoader.getInstance().getConfigDir()));
                } catch (Exception exception) {
                    LOGGER.log(Level.SEVERE, LogMessages.SETTINGS_OPEN_FAILED_FROM_MOD_MENU,
                            exception);
                    return parent;
                }
            }
        };
    }
}
