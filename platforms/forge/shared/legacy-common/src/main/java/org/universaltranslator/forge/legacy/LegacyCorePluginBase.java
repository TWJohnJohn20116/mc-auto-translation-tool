package org.universaltranslator.forge.legacy;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

public abstract class LegacyCorePluginBase implements IFMLLoadingPlugin {
    @Override
    public String[] getASMTransformerClass() {
        return new String[]{FontRendererTransformer.class.getName()};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
