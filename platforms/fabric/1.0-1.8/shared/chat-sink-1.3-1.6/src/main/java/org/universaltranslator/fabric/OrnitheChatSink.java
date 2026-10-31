package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;

/**
 * The 1.3.1-1.6.4 half of the chat sink: {@code ChatGui#addMessage(String)}.
 *
 * <p>The chat line's generation boundary lands inside the {@code 1.6-1.8} family — 1.6.1 and 1.6.4
 * still append plain strings while 1.7.x already wants a {@code Text} component — so it gets a
 * source directory of its own instead of a method on {@code OrnitheFamilyAccess}. The call is direct
 * because a lookup by name cannot survive {@code remapJar}, which rewrites references but not string
 * literals.
 */
final class OrnitheChatSink {
    private OrnitheChatSink() {
    }

    static void addMessage(Minecraft client, String message) {
        client.gui.getChat().addMessage(message);
    }
}
