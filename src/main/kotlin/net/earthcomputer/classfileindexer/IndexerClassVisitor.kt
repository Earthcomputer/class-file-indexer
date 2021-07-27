package net.earthcomputer.classfileindexer

import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.ClassVisitor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Opcodes

class IndexerClassVisitor : ClassVisitor(Opcodes.ASM9) {
    val index = mutableMapOf<BinaryIndexKey, Int>()
}
