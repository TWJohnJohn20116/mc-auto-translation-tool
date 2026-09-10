package org.universaltranslator.forge.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.GuiScreenEvent;

import java.util.List;

/** Compile-time adapter for Minecraft 1.8.9 MCP names. */
final class LegacyVersionAccess {
    private LegacyVersionAccess() {
    }

    static NetHandlerPlayClient connection(Minecraft minecraft) {
        return minecraft.getNetHandler();
    }

    static FontRenderer fontRenderer() {
        return Minecraft.getMinecraft().fontRendererObj;
    }

    static String localPlayerName(Minecraft minecraft) {
        return minecraft.getSession() == null ? null : minecraft.getSession().getUsername();
    }

    static String serverAddress(Minecraft minecraft) {
        return minecraft.getCurrentServerData() == null
                ? null : minecraft.getCurrentServerData().serverIP;
    }

    static void showLocalChatMessage(Minecraft minecraft, String message) {
        minecraft.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(message));
    }

    static void sendChatMessage(Minecraft minecraft, String message) {
        if (minecraft.thePlayer != null) {
            minecraft.thePlayer.sendChatMessage(message);
        }
    }

    static void rememberSentMessage(Minecraft minecraft, String message) {
        minecraft.ingameGUI.getChatGUI().addToSentMessages(message);
    }

    static int maximumChatLength() {
        return 100;
    }

    static GuiScreen eventScreen(GuiScreenEvent event) {
        return event.gui;
    }

    static List<GuiButton> buttonList(GuiScreenEvent.InitGuiEvent event) {
        return event.buttonList;
    }

    static List<GuiButton> buttonList(GuiScreenEvent.ActionPerformedEvent event) {
        return event.buttonList;
    }

    static GuiButton actionButton(GuiScreenEvent.ActionPerformedEvent event) {
        return event.button;
    }
}
