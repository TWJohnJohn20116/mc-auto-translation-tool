package org.universaltranslator.core;

/**
 * Clears a Minecraft widget's focus without binding to one version's accessor name.
 *
 * <p>1.16–1.20 keep {@code setFocused} protected on the widget base class and publish
 * {@code setTextFieldFocused} instead, while 1.21 and later publish {@code setFocused} itself. A
 * screen compiled against several trees cannot call either name directly, so the accessor is
 * resolved once per class and cached.
 *
 * <p>The method is only used when an overlay owns the keyboard, so the reflective call is not on a
 * per-frame path.
 */
public final class FocusHelper {
    private FocusHelper() {
    }

    private static final java.lang.reflect.Method CLEAR = resolve();

    private static java.lang.reflect.Method resolve() {
        String[] classNames = {
                "net.minecraft.client.gui.widget.TextFieldWidget",
                "net.minecraft.client.gui.components.EditBox"
        };
        String[] methodNames = {"setTextFieldFocused", "setFocused"};
        for (String className : classNames) {
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException missing) {
                continue;
            } catch (RuntimeException denied) {
                return null;
            }
            for (String name : methodNames) {
                try {
                    java.lang.reflect.Method method = type.getMethod(name, boolean.class);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException absent) {
                    // Try the next accessor name; the older one is protected rather than absent.
                } catch (RuntimeException denied) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Takes the focus off one field, or does nothing when this Minecraft version exposes neither
     * accessor.
     *
     * @param field widget to clear, or {@code null}
     */
    public static void clear(Object field) {
        if (field == null || CLEAR == null) {
            return;
        }
        try {
            CLEAR.invoke(field, Boolean.FALSE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The focus stays where it was; clearing it is an improvement, not a requirement.
        }
    }
}
