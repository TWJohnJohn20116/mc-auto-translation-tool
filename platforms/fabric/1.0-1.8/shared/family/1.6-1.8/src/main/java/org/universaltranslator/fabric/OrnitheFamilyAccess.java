package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Session;
import net.minecraft.client.gui.widget.TextFieldWidget;

/**
 * The 1.6.1-1.7.10 half of the members {@link OrnitheClientAccess} cannot call on every version it
 * compiles.
 *
 * <p>Both halves are compiled into the same class name and only one of them is on a project's source
 * path, so the call sites in {@code shared/common} stay a plain call instead of a lookup by name —
 * {@code remapJar} rewrites references but not string literals, which is why the reflective versions
 * of these answered "not found" in the shipped JAR.
 *
 * <p>Everything here is present on 1.6.1 through 1.7.10: {@code GameGui#setOverlayMessage(String,
 * boolean)}, {@code TextFieldWidget#setEditable(boolean)} and {@code Minecraft#getSession()}.
 */
final class OrnitheFamilyAccess {
    private OrnitheFamilyAccess() {
    }

    static void setEditable(TextFieldWidget field, boolean editable) {
        field.setEditable(editable);
    }

    /** Answers true: the line reached this generation's HUD. */
    static boolean overlayMessage(Minecraft client, String message) {
        client.gui.setOverlayMessage(message, false);
        return true;
    }

    static String username(Minecraft client) {
        Session session = client.getSession();
        return session == null ? null : session.getUsername();
    }
}
