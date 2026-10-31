package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;

/**
 * The 1.3.1-1.5.2 half of the members {@link OrnitheClientAccess} cannot call on every version it
 * compiles.
 *
 * <p>Both halves are compiled into the same class name and only one of them is on a project's source
 * path, so the call sites in {@code shared/common} stay a plain call instead of a lookup by name —
 * {@code remapJar} rewrites references but not string literals, which is why the reflective versions
 * of these answered "not found" in the shipped JAR.
 *
 * <p>What this range cannot do, measured on each version's mapped JAR:
 * <ul>
 *   <li>{@code GameGui} keeps the status line in a private {@code overlayMessage} field and has no
 *       {@code setOverlayMessage} before 1.6.1;</li>
 *   <li>{@code TextFieldWidget#setEditable} only arrives in 1.4.2, and the {@code editable} field is
 *       private, so this layer cannot lock the form fields the way 1.4.2+ could — the settings screen
 *       already swallows the keystrokes itself while the catalog covers it.</li>
 * </ul>
 */
final class OrnitheFamilyAccess {
    private OrnitheFamilyAccess() {
    }

    /** No setter in this range; the caller reports once that the line is dropped. */
    static void setEditable(TextFieldWidget field, boolean editable) {
    }

    /** Answers false: this generation has no overlay-message entry point. */
    static boolean overlayMessage(Minecraft client, String message) {
        return false;
    }

    /** Public field rather than an accessor up to 1.5.2; {@code getSession()} arrives in 1.6.1. */
    static String username(Minecraft client) {
        return client.session == null ? null : client.session.username;
    }
}
