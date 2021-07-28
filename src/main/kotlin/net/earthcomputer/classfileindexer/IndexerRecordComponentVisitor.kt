package net.earthcomputer.classfileindexer

import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.AnnotationVisitor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Opcodes
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.RecordComponentVisitor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.TypePath

class IndexerRecordComponentVisitor(private val cv: IndexerClassVisitor) : RecordComponentVisitor(Opcodes.ASM9) {
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
}
