package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;

/** Bootstrap kept compatible with the pre-OrderedText 1.16 / 1.16.1 client API. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Capture/render hooks require OrderedText, which arrives in 1.16.2.
    }
}
