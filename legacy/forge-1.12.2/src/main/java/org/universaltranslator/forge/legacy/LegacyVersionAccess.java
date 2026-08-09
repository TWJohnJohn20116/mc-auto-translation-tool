package org.universaltranslator.forge.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.util.text.TextComponentString;

/** Compile-time adapter for names changed after Minecraft 1.8.9. */
final class LegacyVersionAccess {
    private LegacyVersionAccess() {
    }

    static NetHandlerPlayClient connection(Minecraft minecraft) {
        return minecraft.getConnection();
    }

    static FontRenderer fontRenderer() {
        return Minecraft.getMinecraft().fontRenderer;
    }

    static String localPlayerName(Minecraft minecraft) {
        return minecraft.getSession() == null ? null : minecraft.getSession().getUsername();
    }

    static String serverAddress(Minecraft minecraft) {
        return minecraft.getCurrentServerData() == null
                ? null : minecraft.getCurrentServerData().serverIP;
    }

    static void showLocalChatMessage(Minecraft minecraft, String message) {
        minecraft.ingameGUI.getChatGUI().printChatMessage(new TextComponentString(message));
    }
}
