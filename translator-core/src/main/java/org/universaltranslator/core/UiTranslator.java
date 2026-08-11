package org.universaltranslator.core;

/** Platform adapter for resolving Minecraft language keys without coupling the core to a loader. */
public interface UiTranslator {
    String translate(String key, Object... arguments);
}
