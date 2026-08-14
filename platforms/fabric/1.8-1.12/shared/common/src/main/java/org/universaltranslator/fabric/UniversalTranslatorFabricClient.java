package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;

/** Ornithe Fabric legacy bootstrap for Minecraft 1.8.9-1.12.2. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Legacy Ornithe profiles expose no compatible modern capture API.
    }
}
