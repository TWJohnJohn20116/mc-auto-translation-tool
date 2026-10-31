package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.text.LiteralText;

/**
 * The 1.7.2-1.7.10 half of the chat sink: {@code ChatGui#addMessage(Text)}.
 *
 * <p>1.7.x replaced the plain-string overload with a component one, and this is the first range in
 * this bundle where {@code net.minecraft.text.LiteralText} exists at all (1.6.1/1.6.4 have no such
 * class, which is why the boundary sits at 1.7 rather than on the family layer). The call is direct
 * because a lookup by name cannot survive {@code remapJar}, which rewrites references but not string
 * literals.
 */
final class OrnitheChatSink {
    private OrnitheChatSink() {
    }

    static void addMessage(Minecraft client, String message) {
        client.gui.getChat().addMessage(new LiteralText(message));
    }
}
