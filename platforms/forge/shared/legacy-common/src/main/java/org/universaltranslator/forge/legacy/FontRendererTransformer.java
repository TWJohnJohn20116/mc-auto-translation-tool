package org.universaltranslator.forge.legacy;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Injects one string substitution call into FontRenderer render and width methods. */
public final class FontRendererTransformer implements IClassTransformer {
    // Forge's log4j, not System.out: a transformer write to stdout has no level, no timestamp and
    // cannot be filtered, and launch wrappers highlight it as an error line.
    private static final Logger LOGGER = LogManager.getLogger("MCAutoTranslationTool");
    private static final String FONT_RENDERER = "net.minecraft.client.gui.FontRenderer";
    private static final String CHAT_HUD = "net.minecraft.client.gui.GuiNewChat";
    private static final String GUI_SCREEN = "net.minecraft.client.gui.GuiScreen";
    private static final String GUI_TEXT_FIELD = "net.minecraft.client.gui.GuiTextField";
    private static final String GUI_EDIT_SIGN = "net.minecraft.client.gui.inventory.GuiEditSign";
    private static final String BRIDGE =
            "org/universaltranslator/forge/legacy/LegacyRenderedTextBridge";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return basicClass;
        }
        boolean fontRenderer = FONT_RENDERER.equals(name) || FONT_RENDERER.equals(transformedName);
        boolean chatHud = CHAT_HUD.equals(name) || CHAT_HUD.equals(transformedName);
        boolean guiScreen = GUI_SCREEN.equals(name) || GUI_SCREEN.equals(transformedName);
        boolean guiTextField = GUI_TEXT_FIELD.equals(name) || GUI_TEXT_FIELD.equals(transformedName);
        boolean guiEditSign = GUI_EDIT_SIGN.equals(name) || GUI_EDIT_SIGN.equals(transformedName);
        if (!fontRenderer && !chatHud && !guiScreen && !guiTextField && !guiEditSign) {
            return basicClass;
        }
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            CountingVisitor visitor = fontRenderer
                    ? new FontRendererVisitor(writer)
                    : chatHud ? new ChatHudVisitor(writer)
                    : guiScreen ? new TooltipVisitor(writer)
                    : guiTextField ? new TextInputVisitor(writer) : new SignInputVisitor(writer);
            reader.accept(visitor, 0);
            String hookName = fontRenderer ? "font rendering"
                    : chatHud ? "chat context"
                    : guiScreen ? "tooltip context"
                    : guiTextField ? "text input context" : "sign input context";
            if (visitor.modifiedMethods() == 0) {
                LOGGER.warn("No compatible {} methods were found; text translation hook is inactive",
                        hookName);
                return basicClass;
            }
            LOGGER.info("Installed {} {} hook(s)", visitor.modifiedMethods(), hookName);
            return writer.toByteArray();
        } catch (Throwable error) {
            LOGGER.error("FontRenderer transformation failed", error);
            return basicClass;
        }
    }

    /**
     * True when {@code name} is any of the given readable or obfuscated method names.
     *
     * <p>Every visitor below matches one method by a list of names because the obfuscated name
     * differs per Minecraft version. Spelling the chain out at each site made the nesting depth
     * of {@code visitMethod} the worst in the repository.
     */
    private static boolean isAny(String name, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Keeps the live sign preview local while GuiEditSign renders it. */
    private static final class SignInputVisitor extends CountingVisitor {
        private static final String CONTEXT =
                "org/universaltranslator/forge/legacy/LegacyRenderContext";

        private SignInputVisitor(ClassVisitor delegate) {
            super(delegate);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean drawScreen = "(IIF)V".equals(descriptor)
                    && isAny(name, "drawScreen", "func_73863_a", "a");
            if (!drawScreen) {
                return delegate;
            }
            markModified();
            return inputContextVisitor(delegate);
        }

        private MethodVisitor inputContextVisitor(MethodVisitor delegate) {
            return new MethodVisitor(Opcodes.ASM5, delegate) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC, CONTEXT, "pushTextInput", "()V", false);
                }

                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC, CONTEXT, "popTextInput", "()V", false);
                    }
                    super.visitInsn(opcode);
                }
            };
        }
    }

    /** Keeps every GuiTextField value local and untranslated while it is drawn/measured. */
    private static final class TextInputVisitor extends CountingVisitor {
        private static final String CONTEXT =
                "org/universaltranslator/forge/legacy/LegacyRenderContext";

        private TextInputVisitor(ClassVisitor delegate) {
            super(delegate);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean drawTextBox = "()V".equals(descriptor)
                    && isAny(name, "drawTextBox", "func_146194_f", "g");
            if (!drawTextBox) {
                return delegate;
            }
            markModified();
            return new MethodVisitor(Opcodes.ASM5, delegate) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC, CONTEXT, "pushTextInput", "()V", false);
                }

                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC, CONTEXT, "popTextInput", "()V", false);
                    }
                    super.visitInsn(opcode);
                }
            };
        }
    }

    /** Marks GuiNewChat.drawChat so privacy policy can distinguish chat from other surfaces. */
    private abstract static class CountingVisitor extends ClassVisitor {
        private int modifiedMethods;

        private CountingVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM5, delegate);
        }

        final void markModified() {
            modifiedMethods++;
        }

        final int modifiedMethods() {
            return modifiedMethods;
        }
    }

    private static final class ChatHudVisitor extends CountingVisitor {
        private static final String CONTEXT =
                "org/universaltranslator/forge/legacy/LegacyRenderContext";

        private ChatHudVisitor(ClassVisitor delegate) {
            super(delegate);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean drawChat = "(I)V".equals(descriptor)
                    && isAny(name, "drawChat", "func_146230_a", "a");
            if (!drawChat) {
                return delegate;
            }
            markModified();
            return new MethodVisitor(Opcodes.ASM5, delegate) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC, CONTEXT, "pushChat", "()V", false);
                }

                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC, CONTEXT, "pop", "()V", false);
                    }
                    super.visitInsn(opcode);
                }
            };
        }
    }

    private static final class FontRendererVisitor extends CountingVisitor {
        private FontRendererVisitor(ClassVisitor delegate) {
            super(delegate);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean rendersString = "(Ljava/lang/String;FFIZ)I".equals(descriptor);
            boolean measuresString = "(Ljava/lang/String;)I".equals(descriptor)
                    && isAny(name, "getStringWidth", "func_78256_a", "a");
            if (!rendersString && !measuresString) {
                return delegate;
            }
            markModified();
            return new MethodVisitor(Opcodes.ASM5, delegate) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    super.visitVarInsn(Opcodes.ALOAD, 1);
                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            BRIDGE,
                            "translate",
                            "(Ljava/lang/String;)Ljava/lang/String;",
                            false);
                    super.visitVarInsn(Opcodes.ASTORE, 1);
                }
            };
        }
    }

    /** Translates item name/lore lists before vanilla measures and renders the tooltip. */
    private static final class TooltipVisitor extends CountingVisitor {
        private static final String CONTEXT =
                "org/universaltranslator/forge/legacy/LegacyRenderContext";

        private TooltipVisitor(ClassVisitor delegate) {
            super(delegate);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean tooltip = "(Ljava/util/List;II)V".equals(descriptor)
                    && isAny(name, "drawHoveringText", "func_146283_a", "a");
            if (tooltip) {
                markModified();
                return hoveringTextVisitor(delegate);
            }
            boolean itemTooltipFactory = (descriptor.equals(
                    "(Lnet/minecraft/item/ItemStack;)Ljava/util/List;")
                    || descriptor.equals("(Laip;)Ljava/util/List;"))
                    && isAny(name, "getItemToolTip", "func_191927_a", "a");
            if (itemTooltipFactory) {
                return itemTooltipFactoryVisitor(delegate);
            }
            // Production jars still contain Notch class names in descriptors when coremods run.
            // zx is ItemStack in 1.8.9; aip is ItemStack in 1.12.2.
            boolean itemTooltip = (descriptor.startsWith("(Lnet/minecraft/item/ItemStack;")
                    || descriptor.startsWith("(Lzx;")
                    || descriptor.startsWith("(Laip;"))
                    && descriptor.endsWith(";II)V")
                    && isAny(name, "renderToolTip", "func_146285_a", "a");
            if (!itemTooltip) {
                return delegate;
            }
            return itemTooltipProducerVisitor(delegate);
        }

        private MethodVisitor hoveringTextVisitor(MethodVisitor delegate) {
            return new MethodVisitor(Opcodes.ASM5, delegate) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    super.visitVarInsn(Opcodes.ALOAD, 1);
                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            BRIDGE,
                            "translateTooltipLines",
                            "(Ljava/util/List;)Ljava/util/List;",
                            false);
                    super.visitVarInsn(Opcodes.ASTORE, 1);
                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC, CONTEXT, "pushTooltip", "()V", false);
                }

                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC, CONTEXT, "pop", "()V", false);
                    }
                    super.visitInsn(opcode);
                }
            };
        }

        private MethodVisitor itemTooltipProducerVisitor(MethodVisitor delegate) {
            return new MethodVisitor(Opcodes.ASM5, delegate) {
                private boolean injected;

                @Override
                public void visitMethodInsn(
                        int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    boolean itemStackTooltip = isItemStackOwner(owner)
                            // 1.8.9 uses (EntityPlayer, boolean); 1.12.2 uses
                            // (EntityPlayer, ITooltipFlag). Both return the canonical list.
                            && descriptor.endsWith(")Ljava/util/List;")
                            && isAny(name, "getTooltip", "func_82840_a", "a");
                    if (itemStackTooltip) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                BRIDGE,
                                "translateItemTooltipLines",
                                "(Ljava/util/List;)Ljava/util/List;",
                                false);
                        if (!injected) {
                            markModified();
                            injected = true;
                        }
                    }
                }
            };
        }

        /** 1.12.2 centralizes item tooltip creation in GuiScreen.getItemToolTip. */
        private MethodVisitor itemTooltipFactoryVisitor(MethodVisitor delegate) {
            return new MethodVisitor(Opcodes.ASM5, delegate) {
                private boolean injected;

                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.ARETURN) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                BRIDGE,
                                "translateItemTooltipLines",
                                "(Ljava/util/List;)Ljava/util/List;",
                                false);
                        if (!injected) {
                            markModified();
                            injected = true;
                        }
                    }
                    super.visitInsn(opcode);
                }
            };
        }

        private static boolean isItemStackOwner(String owner) {
            return "net/minecraft/item/ItemStack".equals(owner)
                    || "zx".equals(owner)
                    || "aip".equals(owner);
        }
    }
}
