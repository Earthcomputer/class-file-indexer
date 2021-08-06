package net.earthcomputer.classfileindexer

import com.intellij.openapi.diagnostic.Logger
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.AnnotationVisitor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.ClassVisitor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.ConstantDynamic
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.FieldVisitor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Handle
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.MethodVisitor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Opcodes
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.RecordComponentVisitor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Type
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.TypePath

class IndexerClassVisitor : ClassVisitor(Opcodes.ASM9) {
    lateinit var className: String
    val index = SmartMap<String, MutableMap<BinaryIndexKey, MutableMap<String, Int>>>()
    val locationStack = java.util.ArrayDeque<String>()

    private val lambdaLocationMappings = mutableMapOf<String, MutableMap<String, Int>>()
    private val syntheticMethods = mutableSetOf<String>()

    fun addRef(name: String, key: BinaryIndexKey) {
        index.computeIfAbsent(name.intern()) { SmartMap() }.computeIfAbsent(key) { SmartMap() }.merge(locationStack.peek(), 1, Integer::sum)
    }
    fun addClassRef(name: String) {
        addRef(name, ClassIndexKey.INSTANCE)
    }
    fun addFieldRef(owner: String, name: String, isWrite: Boolean) {
        addRef(name, FieldIndexKey(owner.intern(), isWrite))
    }
    fun addMethodRef(owner: String, name: String, desc: String) {
        addRef(name, MethodIndexKey(owner.intern(), desc.intern()))
    }
    fun addDelegateRef(name: String, key: BinaryIndexKey) {
        index[name]?.get(key)?.remove(locationStack.peek())
        addRef(name, DelegateIndexKey(key))
    }

    fun addLambdaLocationMapping(lambdaLocation: String) {
        lambdaLocationMappings.computeIfAbsent(lambdaLocation) { mutableMapOf() }.merge(locationStack.peek(), 1, Integer::sum)
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

//    fun addStringConstant(cst: String) {
//        // addRef(cst, StringConstantKey.INSTANCE)
//    }
    fun addConstant(cst: Any?) {
        if (cst == null) return
        when (cst) {
            // is String -> addStringConstant(cst) TODO
            is Type -> addTypeDescriptor(cst.descriptor)
            is Handle -> {
                when (cst.tag) {
                    Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC -> {
                        addFieldRef(cst.owner, cst.name, false)
                    }
                    Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC -> {
                        addFieldRef(cst.owner, cst.name, true)
                    }
                    else -> {
                        addMethodRef(cst.owner, cst.name, cst.desc)
                    }
                }
            }
            is ConstantDynamic -> {
                val bootstrapMethodArguments = (0 until cst.bootstrapMethodArgumentCount).map { cst.getBootstrapMethodArgument(it) }.toTypedArray()
                IndexerMethodVisitor(this, 0, "()V").visitInvokeDynamicInsn(cst.name, cst.descriptor, cst.bootstrapMethod, *bootstrapMethodArguments)
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
        while (endI < sig.length && !ILLEGAL_SIG_CHARS.contains(sig[endI])) {
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
        locationStack.push("")
        className = name
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

    override fun visitRecordComponent(name: String, descriptor: String, signature: String?): RecordComponentVisitor {
        locationStack.push("$name:$descriptor")
        addTypeDescriptor(descriptor)
        signature?.let { addFieldTypeSignature(it, 0, true) }
        return IndexerRecordComponentVisitor(this)
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        locationStack.push("$name:$descriptor")
        addTypeDescriptor(descriptor)
        signature?.let { addFieldTypeSignature(it, 0, true) }
        addConstant(value)
        return IndexerFieldVisitor(this)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        locationStack.push("$name:$descriptor")
        if ((access and Opcodes.ACC_SYNTHETIC) != 0) {
            syntheticMethods.add(locationStack.peek())
        }
        val desc = Type.getMethodType(descriptor)
        for (argumentType in desc.argumentTypes) {
            addTypeDescriptor(argumentType.descriptor)
        }
        addTypeDescriptor(desc.returnType.descriptor)
        signature?.let { addMethodTypeSignature(it) }
        exceptions?.forEach { addClassRef(it) }
        return IndexerMethodVisitor(this, access, descriptor)
    }

    override fun visitEnd() {
        locationStack.pop()
        propagateLambdaLocations()
    }

    private fun propagateLambdaLocations() {
        val referencedByLambda = lambdaLocationMappings.entries.asSequence()
            .flatMap { (k, vs) -> vs.asSequence().map { v -> java.util.AbstractMap.SimpleEntry(k, v.key) } }
            .groupByTo(mutableMapOf(), { it.value }) { it.key }
        var changed = true
        while (lambdaLocationMappings.isNotEmpty() && changed) {
            changed = false
            val lambdaLocItr = lambdaLocationMappings.iterator()
            while (lambdaLocItr.hasNext()) {
                val (lambdaLoc, targets) = lambdaLocItr.next()
                if (referencedByLambda[lambdaLoc]?.isNotEmpty() == true) {
                    continue // something still needs to be inlined into this lambda, can't inline this one yet
                }
                changed = true
                lambdaLocItr.remove()
                for ((targetLoc, countOfLambda) in targets) {
                    referencedByLambda[targetLoc]?.remove(lambdaLoc)
                    for (keys in index.values) {
                        for (locations in keys.values) {
                            val countInLambda = locations.remove(lambdaLoc) ?: continue
                            locations.merge(targetLoc, countOfLambda * countInLambda, Integer::sum)
                        }
                    }
                }
            }
        }

        if (lambdaLocationMappings.isNotEmpty()) {
            LOGGER.warn("$className: unable to propagate lambda locations")
        }
    }

    companion object {
        private val LOGGER = Logger.getInstance(IndexerClassVisitor::class.java)
        private val ILLEGAL_SIG_CHARS = setOf('.', ';', '[', '/', '<', '>', ':')
        private val BASE_TYPE_CHARS = setOf('B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z')
    }
}
