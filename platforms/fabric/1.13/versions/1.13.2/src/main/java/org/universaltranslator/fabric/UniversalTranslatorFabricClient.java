package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;

/** Legacy Fabric 1.13.2 bootstrap. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // The 1.13.2 legacy loader has no compatible modern capture API.
    }
}