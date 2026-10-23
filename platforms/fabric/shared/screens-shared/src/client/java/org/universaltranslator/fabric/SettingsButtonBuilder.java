package org.universaltranslator.fabric;

import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Version-neutral handle on the per-generation button builder chain.
 *
 * <p>1.20 added {@code ButtonWidget.Builder.dimensions(...)} to the vanilla class. Earlier versions
 * only expose constructors, so this tree carries a package-local {@code ButtonWidget} shim that
 * restores the newer shape. The settings screen cannot name that builder type directly, because the
 * shim exists in some generations and the vanilla class in others. This interface is the single name
 * both generations expose, so the screen keeps one {@code of(...).dimensions(...).build()} chain.
 */
interface SettingsButtonBuilder {
    SettingsButtonBuilder dimensions(int x, int y, int width, int height);

    ButtonWidget build();
}