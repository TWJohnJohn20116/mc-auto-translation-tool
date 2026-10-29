package org.universaltranslator.fabric;
import net.fabricmc.api.ClientModInitializer;
/** Bootstrap for the pre-1.16 client API family. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // This source set is compiled on its own for 1.14, 1.14.1 and 1.14.2
        // (platforms/fabric/1.14-1.15/versions/1.14/build.gradle adds only this directory): the
        // translator, the mixins and the screens are not in that jar, and the mixin config it ships
        // declares no mixins at all. Saying so out loud is the difference between "installed,
        // nothing works, no log line" and "this build is a placeholder"; the log4j API is not on
        // this source set's compile classpath, so a plain line is all that is available here.
        System.out.println("[universal-translator] 1.14/1.14.1/1.14.2 build: bootstrap only - "
                + "translation, the HUD indicator and the settings screens ship from 1.14.3 onward. "
                + "Install the 1.14.3+ jar from the same bundle.");
    }
}
