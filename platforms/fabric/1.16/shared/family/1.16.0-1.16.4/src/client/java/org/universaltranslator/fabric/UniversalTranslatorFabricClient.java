package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;

/** Bootstrap kept compatible with the pre-1.16.5 client API family. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // The complete capture/render hooks are supplied by the 1.16.5+ family.
    }
}