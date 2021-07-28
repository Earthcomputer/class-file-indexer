package net.earthcomputer.classfileindexer

import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.*

class IndexerClassVisitor : ClassVisitor(Opcodes.ASM9) {
    val index = mutableMapOf<BinaryIndexKey, Int>()
    fun addClassRef(name: String) {
        index.merge(ClassIndexKey(name), 1, Integer::sum)
    }
    fun addFieldRef(owner: String, name: String) {
        index.merge(FieldIndexKey(owner, name), 1, Integer::sum)
    }
    fun addMethodRef(owner: String, name: String, desc: String) {
        index.merge(MethodIndexKey(owner, name, desc), 1, Integer::sum)
    }

    fun addTypeDescriptor(desc: String) {
        var type = Type.getType(desc)
        while (type.sort == Type.ARRAY) {
            type = type.elementType
        }
        if (type.sort == Type.OBJECT) {
            addClassRef(type.internalName)
        }
    }

    fun addStringConstant(cst: String) {
        index.merge(StringConstantKey(cst), 1, Integer::sum)
    }
    fun addConstant(cst: Any?) {
        if (cst == null) return
        when (cst) {
            is String -> addStringConstant(cst)
            is Type -> addTypeDescriptor(cst.descriptor)
            is Handle -> {
                if (cst.tag == Opcodes.H_GETFIELD || cst.tag == Opcodes.H_GETSTATIC || cst.tag == Opcodes.H_PUTFIELD || cst.tag == Opcodes.H_PUTSTATIC) {
                    addFieldRef(cst.owner, cst.name)
                } else {
                    addMethodRef(cst.owner, cst.name, cst.desc)
                }
            }
            is ConstantDynamic -> {
                val bootstrapMethodArguments = (0 until cst.bootstrapMethodArgumentCount).map { cst.getBootstrapMethodArgument(it) }.toTypedArray()
                IndexerMethodVisitor(this).visitInvokeDynamicInsn(cst.name, cst.descriptor, cst.bootstrapMethod, *bootstrapMethodArguments)
            }
            else -> {
                if (cst.javaClass.isArray) {
                    for (i in 0 until java.lang.reflect.Array.getLength(cst)) {
                        addConstant(java.lang.reflect.Array.get(cst, i))
                    }
                }
            }
        }
    }

    private fun addClassSignature(sig: String) {
        addFormalTypeParameters(sig)
    }
    private fun addFormalTypeParameters(sig: String): Int {
        if (sig.isEmpty() || sig[0] != '<') return 0
        var i = 1
        while (i < sig.length && sig[i] != '>') {
            val prevI = i
            i = addFormalTypeParameter(sig, i)
            if (i == prevI) return i
        }
        if (i < sig.length) i++
        return i
    }
    private fun addFormalTypeParameter(sig: String, ind: Int): Int {
        var i = ind
        i = readIdentifier(sig, i).second
        if (i >= sig.length || sig[i] != ':') return i
        i++
        i = addFieldTypeSignature(sig, i, false)
        while (i < sig.length && sig[i] == ':') {
            i++
            i = addFieldTypeSignature(sig, i, false)
        }
        return i
    }
    fun addFieldTypeSignature(sig: String, ind: Int, skipOuterName: Boolean): Int {
        if (ind == sig.length) return ind
        return when (sig[ind]) {
            'L' -> addClassTypeSignature(sig, ind, skipOuterName)
            '[' -> addArrayTypeSignature(sig, ind, skipOuterName)
            'T' -> addTypeVariableSignature(sig, ind)
            else -> ind
        }
    }
    private fun addClassTypeSignature(sig: String, ind: Int, skipOuterName: Boolean): Int {
        if (ind >= sig.length || sig[ind] != 'L') return ind
        var i = ind + 1
        val (id, nextI) = readIdentifier(sig, i)
        var qualifiedName = id
        i = nextI
        while (i < sig.length && sig[i] == '/') {
            i++
            qualifiedName += "/"
            val (id2, nextI2) = readIdentifier(sig, i)
            qualifiedName += id2
            i = nextI2
        }
        if (i < sig.length && sig[i] == '<') {
            i = addTypeArguments(sig, i)
        }
        while (i < sig.length && sig[i] == '.') {
            i++
            qualifiedName += "$"
            val (id2, nextI2) = readIdentifier(sig, i)
            qualifiedName += id2
            i = nextI2
            if (i < sig.length && sig[i] == '<') {
                i = addTypeArguments(sig, i)
            }
        }
        if (i < sig.length && sig[i] == ';') {
            i++
        }
        if (!skipOuterName) {
            addClassRef(qualifiedName)
        }
        return i
    }
    private fun addTypeArguments(sig: String, ind: Int): Int {
        if (ind >= sig.length || sig[ind] != '<') return ind
        var i = ind + 1
        while (i < sig.length && sig[i] != '>') {
            val prevI = i
            i = addTypeArgument(sig, i)
            if (i == prevI) return i
        }
        if (i < sig.length) i++
        return i
    }
    private fun addTypeArgument(sig: String, ind: Int): Int {
        if (ind >= sig.length) return ind
        if (sig[ind] == '*') return ind + 1
        var i = ind
        if (sig[i] == '+' || sig[i] == '-') i++
        i = addFieldTypeSignature(sig, i, false)
        return i
    }
    private fun addArrayTypeSignature(sig: String, ind: Int, skipOuterName: Boolean): Int {
        var i = ind
        while (i < sig.length && sig[i] == '[') i++
        return addTypeSignature(sig, i, skipOuterName)
    }
    private fun addTypeVariableSignature(sig: String, ind: Int): Int {
        if (ind >= sig.length || sig[ind] != 'T') return ind
        var i = ind + 1
        i = readIdentifier(sig, i).second
        if (i < sig.length && sig[i] == ';') i++
        return i
    }
    private fun addTypeSignature(sig: String, ind: Int, skipOuterName: Boolean): Int {
        if (ind >= sig.length) return ind
        if (BASE_TYPE_CHARS.contains(sig[ind])) return ind + 1
        return addFieldTypeSignature(sig, ind, skipOuterName)
    }
    private fun addMethodTypeSignature(sig: String) {
        var i = addFormalTypeParameters(sig)
        if (i >= sig.length || sig[i] != '(') return
        i++
        while (i < sig.length && sig[i] != ')') {
            val prevI = i
            i = addTypeSignature(sig, i, false)
            if (i == prevI) return
        }
        if (i < sig.length) i++
        if (i >= sig.length) return
        if (sig[i] == 'V') i++
        else i = addTypeSignature(sig, i, false)
        while (i < sig.length && sig[i] == '^') {
            i++
            i = addFieldTypeSignature(sig, i, false)
        }
    }
    private fun readIdentifier(sig: String, i: Int): Pair<String, Int> {
        var endI = i
        while (endI < sig.length && !ILLEGAL_SIG_CHARS.contains(sig[i])) {
            endI++
        }
        return sig.substring(i, endI) to endI
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        signature?.let { addClassSignature(it) }
        superName?.let { addClassRef(it) }
        interfaces?.forEach { addClassRef(it) }
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
        addTypeDescriptor(descriptor)
        return IndexerAnnotationVisitor(this)
    }

    override fun visitTypeAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String,
        visible: Boolean
    ): AnnotationVisitor {
        addTypeDescriptor(descriptor)
        return IndexerAnnotationVisitor(this)
    }

    override fun visitRecordComponent(name: String?, descriptor: String, signature: String?): RecordComponentVisitor {
        addTypeDescriptor(descriptor)
        signature?.let { addFieldTypeSignature(it, 0, true) }
        return IndexerRecordComponentVisitor(this)
    }

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        addTypeDescriptor(descriptor)
        signature?.let { addFieldTypeSignature(it, 0, true) }
        addConstant(value)
        return IndexerFieldVisitor(this)
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val desc = Type.getMethodType(descriptor)
        for (argumentType in desc.argumentTypes) {
            addTypeDescriptor(argumentType.descriptor)
        }
        addTypeDescriptor(desc.returnType.descriptor)
        signature?.let { addMethodTypeSignature(it) }
        exceptions?.forEach { addClassRef(it) }
        return IndexerMethodVisitor(this)
    }

    companion object {
        val ILLEGAL_SIG_CHARS = setOf('.', ';', '[', '/', '<', '>', ':')
        val BASE_TYPE_CHARS = setOf('B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z')
    }
}
