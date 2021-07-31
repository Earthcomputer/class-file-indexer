package net.earthcomputer.classfileindexer

import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.AnnotationVisitor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.FieldVisitor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Opcodes
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.TypePath

class IndexerFieldVisitor(private val cv: IndexerClassVisitor) : FieldVisitor(Opcodes.ASM9) {
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

    override fun visitEnd() {
        cv.locationStack.pop()
    }
}
