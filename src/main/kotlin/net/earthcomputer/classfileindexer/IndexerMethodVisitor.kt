package net.earthcomputer.classfileindexer

import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.*

class IndexerMethodVisitor(private val cv: IndexerClassVisitor) : MethodVisitor(Opcodes.ASM9) {
    override fun visitAnnotationDefault(): AnnotationVisitor {
        return IndexerAnnotationVisitor(cv)
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
        cv.addTypeDescriptor(descriptor)
        return IndexerAnnotationVisitor(cv)
    }

    override fun visitTypeAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String,
        visible: Boolean
    ): AnnotationVisitor {
        cv.addTypeDescriptor(descriptor)
        return IndexerAnnotationVisitor(cv)
    }

    override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean): AnnotationVisitor {
        cv.addTypeDescriptor(descriptor)
        return IndexerAnnotationVisitor(cv)
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        var t = Type.getObjectType(type)
        while (t.sort == Type.ARRAY) {
            t = t.elementType
        }
        if (t.sort == Type.OBJECT) {
            cv.addClassRef(t.internalName)
        }
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        cv.addFieldRef(owner, name, opcode == Opcodes.PUTSTATIC || opcode == Opcodes.PUTFIELD)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
        cv.addMethodRef(owner, name, descriptor)
    }

    override fun visitInvokeDynamicInsn(
        name: String?,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        vararg bootstrapMethodArguments: Any?
    ) {
        when (bootstrapMethodHandle.owner) {
            "java/lang/invoke/LambdaMetafactory" -> {
                val invokedMethod = when (bootstrapMethodHandle.name) {
                    "metafactory" -> {
                        if (bootstrapMethodArguments.size < 3) return
                        bootstrapMethodArguments[1] as? Handle ?: return
                    }
                    "altMetafactory" -> {
                        if (bootstrapMethodArguments.size < 2) return
                        val extraArgs = bootstrapMethodArguments[0] as? Array<*> ?: return
                        if (extraArgs.size < 2) return
                        extraArgs[1] as? Handle ?: return
                    }
                    else -> return
                }
                if (invokedMethod.owner == cv.className) {
                    cv.addLambdaLocationMapping("${invokedMethod.name}:${invokedMethod.desc}")
                }
                cv.addMethodRef(invokedMethod.owner, invokedMethod.name, invokedMethod.desc)
            }
            "java/lang/invoke/StringConcatFactory" -> {
                when (bootstrapMethodHandle.name) {
                    "makeConcat", "makeConcatWithConstants" -> {
                        val methodType = Type.getMethodType(descriptor)
                        for (argumentType in methodType.argumentTypes) {
                            if (argumentType.sort == Type.OBJECT) {
                                cv.addRef(argumentType.internalName, ImplicitToStringKey.INSTANCE)
                            }
                        }
                        if (bootstrapMethodHandle.name == "makeConcatWithConstants") {
                            val recipe = bootstrapMethodArguments.getOrNull(0) as? String ?: return
                            recipe.split('\u0001', '\u0002').filter { it.isNotEmpty() }
                                .forEach { cv.addStringConstant(it) }
                            cv.addConstant(bootstrapMethodArguments.getOrNull(1))
                        }
                    }
                }
            }
        }
    }

    override fun visitLdcInsn(value: Any?) {
        cv.addConstant(value)
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        cv.addTypeDescriptor(descriptor)
    }

    override fun visitInsnAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String,
        visible: Boolean
    ): AnnotationVisitor {
        cv.addTypeDescriptor(descriptor)
        return IndexerAnnotationVisitor(cv)
    }

    override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) {
        type?.let { cv.addClassRef(it) }
    }

    override fun visitTryCatchAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String,
        visible: Boolean
    ): AnnotationVisitor {
        cv.addTypeDescriptor(descriptor)
        return IndexerAnnotationVisitor(cv)
    }

    override fun visitLocalVariable(
        name: String?,
        descriptor: String?,
        signature: String?,
        start: Label?,
        end: Label?,
        index: Int
    ) {
        signature?.let { cv.addFieldTypeSignature(it, 0, true) }
    }

    override fun visitLocalVariableAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        start: Array<out Label>?,
        end: Array<out Label>?,
        index: IntArray?,
        descriptor: String,
        visible: Boolean
    ): AnnotationVisitor {
        cv.addTypeDescriptor(descriptor)
        return IndexerAnnotationVisitor(cv)
    }

    override fun visitEnd() {
        cv.locationStack.pop()
    }
}
