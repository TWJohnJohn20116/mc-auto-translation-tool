package org.universaltranslator.forge.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.GuiScreenEvent;

import java.util.List;

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

    static void sendChatMessage(Minecraft minecraft, String message) {
        if (minecraft.player != null) {
            minecraft.player.sendChatMessage(message);
        }
    }

    static void rememberSentMessage(Minecraft minecraft, String message) {
        minecraft.ingameGUI.getChatGUI().addToSentMessages(message);
    }

    static int maximumChatLength() {
        return 256;
    }

    static GuiScreen eventScreen(GuiScreenEvent event) {
        return event.getGui();
    }

    static List<GuiButton> buttonList(GuiScreenEvent.InitGuiEvent event) {
        return event.getButtonList();
    }

    static List<GuiButton> buttonList(GuiScreenEvent.ActionPerformedEvent event) {
        return event.getButtonList();
    }

    static GuiButton actionButton(GuiScreenEvent.ActionPerformedEvent event) {
        return event.getButton();
    }
}
