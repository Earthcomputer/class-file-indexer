package net.earthcomputer.classfileindexer;

import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.ClassReader;
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.ClassVisitor;
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.ClassWriter;
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.FieldVisitor;
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Label;
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.MethodVisitor;
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Opcodes;
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Type;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

public class MyAgent implements ClassFileTransformer {
    private static final boolean DEBUG = false;

    private static final String USAGE_INFO = "com/intellij/usageView/UsageInfo";
    private static final String USAGE_INFO_2_USAGE_ADAPTER = "com/intellij/usages/UsageInfo2UsageAdapter";
    private static final String PSI_UTIL = "com/intellij/psi/util/PsiUtil";
    private static final String JAVA_READ_WRITE_ACCESS_DETECTOR = "com/intellij/codeInsight/highlighting/JavaReadWriteAccessDetector";

    public static void agentmain(String s, Instrumentation instrumentation) throws UnmodifiableClassException {
        instrumentation.addTransformer(new MyAgent(), true);

        List<Class<?>> classesToRetransform = new ArrayList<>();
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            String className = clazz.getName();
            if (USAGE_INFO.equals(className)
                    || USAGE_INFO_2_USAGE_ADAPTER.equals(className)
                    || PSI_UTIL.equals(className)
                    || JAVA_READ_WRITE_ACCESS_DETECTOR.equals(className)) {
                classesToRetransform.add(clazz);
            }
        }
        instrumentation.retransformClasses(classesToRetransform.toArray(new Class[0]));
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            switch (className) {
                case USAGE_INFO:
                    return transformClass(
                            classfileBuffer,
                            loader,
                            "net.earthcomputer.classfileindexer.IHasNavigationOffset",
                            "getNavigationOffset",
                            "()I",
                            new HookClassVisitor.Target(
                                    "getNavigationOffset",
                                    "()I",
                                    UsageInfoGetNavigationOffsetVisitor::new
                            )
                    );
                case USAGE_INFO_2_USAGE_ADAPTER:
                    return transformClass(
                            classfileBuffer,
                            loader,
                            "net.earthcomputer.classfileindexer.IHasCustomDescription",
                            "getCustomDescription",
                            "()[Lcom/intellij/usages/TextChunk;",
                            new HookClassVisitor.Target(
                                    "getPlainText",
                                    "()Ljava/lang/String;",
                                    HasCustomDescriptionVisitor::new
                            ),
                            new HookClassVisitor.Target(
                                    "initChunks",
                                    "()[Lcom/intellij/usages/TextChunk;",
                                    InitChunksMethodVisitor::new
                            )
                    );
                case PSI_UTIL:
                    return transformClass(
                            classfileBuffer,
                            loader,
                            "net.earthcomputer.classfileindexer.IIsWriteOverride",
                            "isWrite",
                            "()Z",
                            new HookClassVisitor.Target(
                                    "isAccessedForWriting",
                                    "(Lcom/intellij/psi/PsiExpression;)Z",
                                    IsAccessedForWriteMethodVisitor::new
                            )
                    );
                case JAVA_READ_WRITE_ACCESS_DETECTOR:
                    return transformClass(
                            classfileBuffer,
                            loader,
                            "net.earthcomputer.classfileindexer.IIsWriteOverride",
                            "isWrite",
                            "()Z",
                            new HookClassVisitor.Target(
                                    "getExpressionAccess",
                                    "(Lcom/intellij/psi/PsiElement;)Lcom/intellij/codeInsight/highlighting/ReadWriteAccessDetector$Access;",
                                    JavaReadWriteAccessDetectorMethodVisitor::new
                            )
                    );
            }
        } catch (Throwable t) {
            // since the jdk does not log it for us
            t.printStackTrace();
            throw t;
        }
        return classfileBuffer;
    }

    private static byte[] transformClass(byte[] classfileBuffer, ClassLoader classLoader, String interfaceName, String interfaceMethod, String interfaceMethodDesc, HookClassVisitor.Target... targets) {
        ClassReader cr = new ClassReader(classfileBuffer);
        String className = cr.getClassName();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                if (type1.equals(type2)) {
                    return type1;
                }
                if (type2.equals(className)) {
                    return getCommonSuperClass(type2, type1);
                }
                if (type1.equals(className)) {
                    String superName = cr.getSuperName();
                    return getCommonSuperClass(superName, type2);
                }
                return super.getCommonSuperClass(type1, type2);
            }

            @Override
            protected ClassLoader getClassLoader() {
                return classLoader;
            }
        };
        cr.accept(new HookClassVisitor(cw, interfaceName, interfaceMethod, interfaceMethodDesc, targets), ClassReader.SKIP_FRAMES);
        byte[] bytes = cw.toByteArray();
        if (DEBUG) {
            Path output = Paths.get("debugTransformerOutput", className.replace("/", File.separator) + ".class").toAbsolutePath();
            try {
                if (!Files.exists(output.getParent())) {
                    Files.createDirectories(output.getParent());
                }
                Files.write(output, bytes);
                System.out.println("Written " + output);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return bytes;
    }

    // expects an object to already be on the stack
    // injects code resembling the following:
    // if (obj instanceof interfaceName i) { // prologue
    //    <load i and arguments onto the stack>
    //    result = i.interfaceMethod(); // interface call
    //    <do stuff with result>
    // } // epilogue
    private static class HookClassVisitor extends ClassVisitor {
        static class Target {
            final String hookMethodName;
            final String hookMethodDesc;
            final BiFunction<MethodVisitor, HookInfo, MethodVisitor> hookMethodTransformer;

            Target(String hookMethodName, String hookMethodDesc, BiFunction<MethodVisitor, HookInfo, MethodVisitor> hookMethodTransformer) {
                this.hookMethodName = hookMethodName;
                this.hookMethodDesc = hookMethodDesc;
                this.hookMethodTransformer = hookMethodTransformer;
            }
        }
        static class HookInfo {
            final String interfaceName;
            final String interfaceMethod;
            final String interfaceMethodDesc;
            final Target[] targets;
            final String hookClassField;
            final String hookMethodField;
            String targetClass;

            HookInfo(String interfaceName, String interfaceMethod, String interfaceMethodDesc, Target... targets) {
                this.interfaceName = interfaceName;
                this.interfaceMethod = interfaceMethod;
                this.interfaceMethodDesc = interfaceMethodDesc;
                this.targets = targets;
                this.hookClassField = "C" + interfaceName.toUpperCase(Locale.ROOT).replace('.', '_');
                this.hookMethodField = hookClassField + "_" + interfaceMethod.toUpperCase(Locale.ROOT);
            }

        }
        private final HookInfo hookInfo;
        private boolean hadClinit = false;

        public HookClassVisitor(ClassVisitor classVisitor, String interfaceName, String interfaceMethod, String interfaceMethodDesc, Target... targets) {
            super(Opcodes.ASM9, classVisitor);
            this.hookInfo = new HookInfo(interfaceName, interfaceMethod, interfaceMethodDesc, targets);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
            hookInfo.targetClass = name;
        }

        @Override
        public void visitEnd() {
            if (!hadClinit) {
                MethodVisitor mv = visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                if (mv != null) {
                    mv.visitCode();
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                }
            }
            FieldVisitor fv = visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, hookInfo.hookClassField, "Ljava/lang/Class;", null, null);
            if (fv != null)
                fv.visitEnd();
            fv = visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, hookInfo.hookMethodField, "Ljava/lang/reflect/Method;", null, null);
            if (fv != null)
                fv.visitEnd();
            super.visitEnd();
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("<clinit>".equals(name)) {
                hadClinit = true;
                return new HookClinitVisitor(mv, hookInfo);
            }
            for (Target target : hookInfo.targets) {
                if (target.hookMethodName.equals(name) && target.hookMethodDesc.equals(descriptor)) {
                    return target.hookMethodTransformer.apply(mv, hookInfo);
                }
            }
            return mv;
        }
    }

    private static class HookClinitVisitor extends MethodVisitor {
        private final HookClassVisitor.HookInfo hookInfo;

        public HookClinitVisitor(MethodVisitor methodVisitor, HookClassVisitor.HookInfo hookInfo) {
            super(Opcodes.ASM9, methodVisitor);
            this.hookInfo = hookInfo;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            visitMethodInsn(Opcodes.INVOKESTATIC, "com/intellij/ide/plugins/PluginManager", "getInstance", "()Lcom/intellij/ide/plugins/PluginManager;", false);
            visitLdcInsn("net.earthcomputer.classfileindexer");
            visitMethodInsn(Opcodes.INVOKESTATIC, "com/intellij/openapi/extensions/PluginId", "getId", "(Ljava/lang/String;)Lcom/intellij/openapi/extensions/PluginId;", false);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/intellij/ide/plugins/PluginManager", "findEnabledPlugin", "(Lcom/intellij/openapi/extensions/PluginId;)Lcom/intellij/ide/plugins/IdeaPluginDescriptor;", false);
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/intellij/openapi/extensions/PluginDescriptor", "getPluginClassLoader", "()Ljava/lang/ClassLoader;", true);
            visitLdcInsn(hookInfo.interfaceName);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", false);
            visitInsn(Opcodes.DUP);
            visitFieldInsn(Opcodes.PUTSTATIC, hookInfo.targetClass, hookInfo.hookClassField, "Ljava/lang/Class;");
            visitLdcInsn(hookInfo.interfaceMethod);
            Type[] argTypes = Type.getMethodType(hookInfo.interfaceMethodDesc).getArgumentTypes();
            loadInt(this, argTypes.length);
            visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
            for (int i = 0; i < argTypes.length; i++) {
                Type argType = argTypes[i];
                visitInsn(Opcodes.DUP);
                loadInt(this, i);
                if (argType.getSort() == Type.OBJECT || argType.getSort() == Type.ARRAY) {
                    visitLdcInsn(argType);
                } else {
                    String boxedClass = getBoxedClass(argType);
                    visitFieldInsn(Opcodes.GETSTATIC, boxedClass, "TYPE", "Ljava/lang/Class;");
                }
                visitInsn(Opcodes.AASTORE);
            }
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
            visitFieldInsn(Opcodes.PUTSTATIC, hookInfo.targetClass, hookInfo.hookMethodField,
                    "Ljava/lang/reflect/Method;");
        }

    }

    private static abstract class HookMethodVisitor extends MethodVisitor {
        private final HookClassVisitor.HookInfo hookInfo;
        private Label jumpLabel;

        public HookMethodVisitor(MethodVisitor methodVisitor, HookClassVisitor.HookInfo hookInfo) {
            super(Opcodes.ASM9, methodVisitor);
            this.hookInfo = hookInfo;
        }

        protected void addPrologue() {
            visitFieldInsn(Opcodes.GETSTATIC, hookInfo.targetClass, hookInfo.hookClassField, "Ljava/lang/Class;");
            visitInsn(Opcodes.SWAP);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "isInstance", "(Ljava/lang/Object;)Z", false);
            jumpLabel = new Label();
            visitJumpInsn(Opcodes.IFEQ, jumpLabel);
        }

        protected void addInterfaceCall() {
            Type methodType = Type.getMethodType(hookInfo.interfaceMethodDesc);
            Type[] argTypes = methodType.getArgumentTypes();
            loadInt(this, argTypes.length);
            visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            for (int i = argTypes.length - 1; i >= 0; i--) {
                Type argType = argTypes[i];
                boolean isWide = argType.getSort() == Type.DOUBLE || argType.getSort() == Type.LONG;
                visitInsn(isWide ? Opcodes.DUP_X2 : Opcodes.DUP_X1);
                visitInsn(isWide ? Opcodes.DUP_X2 : Opcodes.DUP_X1);
                visitInsn(Opcodes.POP);
                if (argType.getSort() != Type.OBJECT && argType.getSort() != Type.ARRAY) {
                    String boxedClass = getBoxedClass(argType);
                    visitMethodInsn(Opcodes.INVOKESTATIC, boxedClass, "valueOf", "(" + argType.getDescriptor() + ")L" + boxedClass + ";", false);
                }
                loadInt(this, i);
                visitInsn(Opcodes.SWAP);
                visitInsn(Opcodes.AASTORE);
            }
            visitFieldInsn(Opcodes.GETSTATIC, hookInfo.targetClass, hookInfo.hookMethodField, "Ljava/lang/reflect/Method;");
            visitInsn(Opcodes.DUP_X2);
            visitInsn(Opcodes.POP);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            Type returnType = methodType.getReturnType();
            if (!"Ljava/lang/Object;".equals(returnType.getDescriptor())) {
                if (returnType.getSort() == Type.VOID) {
                    visitInsn(Opcodes.POP);
                } else if (returnType.getSort() == Type.OBJECT) {
                    visitTypeInsn(Opcodes.CHECKCAST, returnType.getInternalName());
                } else if (returnType.getSort() == Type.ARRAY) {
                    visitTypeInsn(Opcodes.CHECKCAST, returnType.getDescriptor());
                } else {
                    String boxedClass = getBoxedClass(returnType);
                    visitTypeInsn(Opcodes.CHECKCAST, boxedClass);
                    String unboxMethod = getUnboxMethod(returnType);
                    visitMethodInsn(Opcodes.INVOKEVIRTUAL, boxedClass, unboxMethod, "()" + returnType.getDescriptor(), false);
                }
            }
        }

        protected void addEpilogue() {
            visitLabel(jumpLabel);
        }
    }

    private static class UsageInfoGetNavigationOffsetVisitor extends HookMethodVisitor {
        private boolean waitingForAstore = false;
        private boolean waitingForElementNullCheck = false;
        private Label elementNullCheckJumpTarget = null;
        private int elementLocalVarIndex = -1;

        public UsageInfoGetNavigationOffsetVisitor(MethodVisitor methodVisitor, HookClassVisitor.HookInfo hookInfo) {
            super(methodVisitor, hookInfo);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (USAGE_INFO.equals(owner) && "getElement".equals(name) && "()Lcom/intellij/psi/PsiElement;".equals(descriptor)) {
                waitingForAstore = true;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, var);
            if (waitingForAstore) {
                waitingForAstore = false;
                elementLocalVarIndex = var;
                waitingForElementNullCheck = true;
            }
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            super.visitJumpInsn(opcode, label);
            if (waitingForElementNullCheck) {
                waitingForElementNullCheck = false;
                elementNullCheckJumpTarget = label;
            }
        }

        @Override
        public void visitLabel(Label label) {
            super.visitLabel(label);
            if (elementNullCheckJumpTarget == label) {
                elementNullCheckJumpTarget = null;
                visitVarInsn(Opcodes.ALOAD, elementLocalVarIndex);
                addPrologue();
                visitVarInsn(Opcodes.ALOAD, elementLocalVarIndex);
                addInterfaceCall();
                visitInsn(Opcodes.IRETURN);
                addEpilogue();
            }
        }
    }

    private static class HasCustomDescriptionVisitor extends HookMethodVisitor {
        private boolean waitingForAstore = false;

        public HasCustomDescriptionVisitor(MethodVisitor methodVisitor, HookClassVisitor.HookInfo hookInfo) {
            super(methodVisitor, hookInfo);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (USAGE_INFO_2_USAGE_ADAPTER.equals(owner) && "getElement".equals(name) && "()Lcom/intellij/psi/PsiElement;".equals(descriptor)) {
                waitingForAstore = true;
            }
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, var);
            if (waitingForAstore) {
                waitingForAstore = false;
                visitVarInsn(Opcodes.ALOAD, var);
                addPrologue();
                visitVarInsn(Opcodes.ALOAD, var);
                addInterfaceCall();
                visitVarInsn(Opcodes.ASTORE, var);
                visitVarInsn(Opcodes.ALOAD, var);
                visitInsn(Opcodes.ARRAYLENGTH);
                visitVarInsn(Opcodes.ISTORE, var + 1);
                visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                visitInsn(Opcodes.DUP);
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
                visitVarInsn(Opcodes.ASTORE, var + 2);
                visitInsn(Opcodes.ICONST_0);
                visitVarInsn(Opcodes.ISTORE, var + 3);
                Label loopCondition = new Label();
                visitJumpInsn(Opcodes.GOTO, loopCondition);
                Label loopBody = new Label();
                visitLabel(loopBody);
                visitVarInsn(Opcodes.ALOAD, var + 2);
                visitVarInsn(Opcodes.ALOAD, var);
                visitVarInsn(Opcodes.ILOAD, var + 3);
                visitInsn(Opcodes.AALOAD);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/intellij/usages/TextChunk", "getText", "()Ljava/lang/String;", false);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                visitInsn(Opcodes.POP);
                visitIincInsn(var + 3, 1);
                visitLabel(loopCondition);
                visitVarInsn(Opcodes.ILOAD, var + 3);
                visitVarInsn(Opcodes.ILOAD, var + 1);
                visitJumpInsn(Opcodes.IF_ICMPLT, loopBody);
                visitVarInsn(Opcodes.ALOAD, var + 2);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                visitInsn(Opcodes.ARETURN);
                addEpilogue();
            }
        }
    }

    private static class InitChunksMethodVisitor extends HookMethodVisitor {
        private boolean waitingForAstore = false;

        public InitChunksMethodVisitor(MethodVisitor methodVisitor, HookClassVisitor.HookInfo hookInfo) {
            super(methodVisitor, hookInfo);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (USAGE_INFO_2_USAGE_ADAPTER.equals(owner) && "getElement".equals(name) && "()Lcom/intellij/psi/PsiElement;".equals(descriptor)) {
                waitingForAstore = true;
            }
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, var);
            if (waitingForAstore) {
                waitingForAstore = false;
                visitVarInsn(Opcodes.ALOAD, var);
                addPrologue();
                visitVarInsn(Opcodes.ALOAD, var);
                addInterfaceCall();
                visitVarInsn(Opcodes.ASTORE, var);
                visitVarInsn(Opcodes.ALOAD, 0);
                visitTypeInsn(Opcodes.NEW, "com/intellij/reference/SoftReference");
                visitInsn(Opcodes.DUP);
                visitVarInsn(Opcodes.ALOAD, var);
                visitMethodInsn(Opcodes.INVOKESPECIAL, "com/intellij/reference/SoftReference", "<init>", "(Ljava/lang/Object;)V", false);
                visitFieldInsn(Opcodes.PUTFIELD, USAGE_INFO_2_USAGE_ADAPTER, "myTextChunks", "Ljava/lang/ref/Reference;");
                visitVarInsn(Opcodes.ALOAD, var);
                visitInsn(Opcodes.ARETURN);
                addEpilogue();
            }
        }
    }

    private static class IsAccessedForWriteMethodVisitor extends HookMethodVisitor {
        public IsAccessedForWriteMethodVisitor(MethodVisitor methodVisitor, HookClassVisitor.HookInfo hookInfo) {
            super(methodVisitor, hookInfo);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            visitVarInsn(Opcodes.ALOAD, 0);
            addPrologue();
            visitVarInsn(Opcodes.ALOAD, 0);
            addInterfaceCall();
            visitInsn(Opcodes.IRETURN);
            addEpilogue();
        }
    }

    private static class JavaReadWriteAccessDetectorMethodVisitor extends HookMethodVisitor {
        public JavaReadWriteAccessDetectorMethodVisitor(MethodVisitor methodVisitor, HookClassVisitor.HookInfo hookInfo) {
            super(methodVisitor, hookInfo);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            visitVarInsn(Opcodes.ALOAD, 1);
            addPrologue();
            visitVarInsn(Opcodes.ALOAD, 1);
            addInterfaceCall();
            Label falseLabel = new Label();
            Label returnLabel = new Label();
            visitJumpInsn(Opcodes.IFEQ, falseLabel);
            visitFieldInsn(Opcodes.GETSTATIC, "com/intellij/codeInsight/highlighting/ReadWriteAccessDetector$Access", "Write", "Lcom/intellij/codeInsight/highlighting/ReadWriteAccessDetector$Access;");
            visitJumpInsn(Opcodes.GOTO, returnLabel);
            visitLabel(falseLabel);
            visitFieldInsn(Opcodes.GETSTATIC, "com/intellij/codeInsight/highlighting/ReadWriteAccessDetector$Access", "Read", "Lcom/intellij/codeInsight/highlighting/ReadWriteAccessDetector$Access;");
            visitLabel(returnLabel);
            visitInsn(Opcodes.ARETURN);
            addEpilogue();
        }
    }

    private static void loadInt(MethodVisitor mv, int val) {
        assert val >= 0;
        if (val <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + val);
        } else if (val <= 255) {
            mv.visitIntInsn(val <= 127 ? Opcodes.BIPUSH : Opcodes.SIPUSH, val);
        } else {
            mv.visitLdcInsn(val);
        }
    }

    private static String getBoxedClass(Type type) {
        switch (type.getSort()) {
            case Type.BYTE:
                return "java/lang/Byte";
            case Type.CHAR:
                return "java/lang/Character";
            case Type.DOUBLE:
                return "java/lang/Double";
            case Type.FLOAT:
                return "java/lang/Float";
            case Type.INT:
                return "java/lang/Integer";
            case Type.LONG:
                return "java/lang/Long";
            case Type.SHORT:
                return "java/lang/Short";
            case Type.BOOLEAN:
                return "java/lang/Boolean";
            default:
                throw new AssertionError();
        }
    }

    private static String getUnboxMethod(Type type) {
        switch (type.getSort()) {
            case Type.BYTE:
                return "byteValue";
            case Type.CHAR:
                return "charValue";
            case Type.DOUBLE:
                return "doubleValue";
            case Type.FLOAT:
                return "floatValue";
            case Type.INT:
                return "intValue";
            case Type.LONG:
                return "longValue";
            case Type.SHORT:
                return "shortValue";
            case Type.BOOLEAN:
                return "booleanValue";
            default:
                throw new AssertionError();
        }
    }
}
