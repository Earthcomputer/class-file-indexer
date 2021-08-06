package net.earthcomputer.classfileindexer

import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.*

class IndexerMethodVisitor(
    private val cv: IndexerClassVisitor,
    private val access: Int,
    private val desc: String
) : MethodVisitor(Opcodes.ASM9) {
    private val insns = mutableListOf<Insn>()

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
        insns += TypeInsn(opcode, type)
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        cv.addFieldRef(owner, name, opcode == Opcodes.PUTSTATIC || opcode == Opcodes.PUTFIELD)
        insns += MemberInsn(opcode, owner, name, descriptor)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
        cv.addMethodRef(owner, name, descriptor)
        insns += MemberInsn(opcode, owner, name, descriptor)
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
        insns += UnknownInsn(Opcodes.INVOKEDYNAMIC)
    }

    override fun visitLdcInsn(value: Any?) {
        cv.addConstant(value)
        insns += UnknownInsn(Opcodes.LDC)
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        cv.addTypeDescriptor(descriptor)
        insns += UnknownInsn(Opcodes.MULTIANEWARRAY)
    }

    override fun visitInsn(opcode: Int) {
        insns += NoOperandInsn(opcode)
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        insns += UnknownInsn(opcode)
    }

    override fun visitVarInsn(opcode: Int, variable: Int) {
        insns += VarInsn(opcode, variable)
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) {
        insns += UnknownInsn(opcode)
    }

    override fun visitIincInsn(variable: Int, increment: Int) {
        insns += UnknownInsn(Opcodes.IINC)
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) {
        insns += UnknownInsn(Opcodes.TABLESWITCH)
    }

    override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) {
        insns += UnknownInsn(Opcodes.LOOKUPSWITCH)
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
        if ((access and Opcodes.ACC_SYNTHETIC) != 0) {
            checkSyntheticPattern()
        }
        cv.locationStack.pop()
    }

    private fun checkSyntheticPattern() {
        checkAccessMethod()
    }

    private fun checkAccessMethod() {
        val methodType = Type.getMethodType(desc)

        // load every parameter
        var varIndex = 0
        var insnIndex = 0
        if ((access and Opcodes.ACC_STATIC) == 0) {
            val insn = insns.firstOrNull() ?: return
            if (insn !is VarInsn || insn.variable != 0) return
            varIndex++
            insnIndex++
        }
        for (param in methodType.argumentTypes) {
            var insn = insns.getOrNull(insnIndex) ?: return
            // ignore checkcasts
            if (insn.opcode == Opcodes.CHECKCAST) insn = insns.getOrNull(++insnIndex) ?: return
            if (insn !is VarInsn || insn.variable != varIndex) return
            insnIndex++
            if (param.sort == Type.DOUBLE || param.sort == Type.LONG) {
                varIndex += 2
            } else {
                varIndex++
            }
        }

        // member access
        var insn = insns.getOrNull(insnIndex) ?: return
        // ignore checkcasts
        if (insn.opcode == Opcodes.CHECKCAST) insn = insns.getOrNull(++insnIndex) ?: return
        val memberInsn = insn as? MemberInsn ?: return
        insnIndex++

        // return instruction
        insn = insns.getOrNull(insnIndex) ?: return
        // ignore checkcasts
        if (insn.opcode == Opcodes.CHECKCAST) insn = insns.getOrNull(++insnIndex) ?: return
        if (insn.opcode < Opcodes.IRETURN || insn.opcode > Opcodes.RETURN) return
        insnIndex++

        // end of method
        if (insnIndex != insns.size) return

        when (memberInsn.opcode) {
            Opcodes.GETFIELD, Opcodes.GETSTATIC -> cv.addDelegateRef(memberInsn.name, FieldIndexKey(memberInsn.owner.intern(), false))
            Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> cv.addDelegateRef(memberInsn.name, FieldIndexKey(memberInsn.owner.intern(), true))
            else -> cv.addDelegateRef(memberInsn.name, MethodIndexKey(memberInsn.owner.intern(), memberInsn.desc.intern()))
        }
    }

    private sealed class Insn(val opcode: Int)
    private class UnknownInsn(opcode: Int) : Insn(opcode)
    private class VarInsn(opcode: Int, val variable: Int) : Insn(opcode)
    private class MemberInsn(opcode: Int, val owner: String, val name: String, val desc: String) : Insn(opcode)
    private class TypeInsn(opcode: Int, type: String) : Insn(opcode)
    private class NoOperandInsn(opcode: Int) : Insn(opcode)
}
