package net.earthcomputer.classfileindexer

import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.AnnotationVisitor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Opcodes
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Type

class IndexerAnnotationVisitor(private val cv: IndexerClassVisitor) : AnnotationVisitor(Opcodes.ASM9) {
    override fun visit(name: String?, value: Any?) {
        cv.addConstant(value)
    }

    override fun visitEnum(name: String?, descriptor: String, value: String) {
        cv.addFieldRef(Type.getType(descriptor).internalName, value, false)
    }

    override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor {
        cv.addTypeDescriptor(descriptor)
        return this
    }

    override fun visitArray(name: String?): AnnotationVisitor {
        return this
    }
}
