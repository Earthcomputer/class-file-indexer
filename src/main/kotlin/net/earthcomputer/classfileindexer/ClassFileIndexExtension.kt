package net.earthcomputer.classfileindexer

import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.util.indexing.*
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.KeyDescriptor
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.ClassReader
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

class ClassFileIndexExtension : FileBasedIndexExtension<BinaryIndexKey, Int>() {
    override fun getName() = INDEX_ID

    override fun getIndexer() = DataIndexer<BinaryIndexKey, Int, FileContent> { content ->
        val bytes = content.content
        val cv = IndexerClassVisitor()
        ClassReader(bytes).accept(cv, ClassReader.SKIP_FRAMES)
        cv.index
    }

    override fun getKeyDescriptor() = object : KeyDescriptor<BinaryIndexKey> {
        override fun getHashCode(value: BinaryIndexKey): Int = value.hashCode()

        override fun isEqual(val1: BinaryIndexKey, val2: BinaryIndexKey) = val1 == val2

        override fun save(out: DataOutput, value: BinaryIndexKey) {
            out.writeUTF(value.name)
            when (value) {
                is ClassIndexKey -> out.writeByte(ClassIndexKey.ID)
                is FieldIndexKey -> {
                    out.writeByte(FieldIndexKey.ID)
                    out.writeUTF(value.owner)
                }
                is MethodIndexKey -> {
                    out.writeByte(MethodIndexKey.ID)
                    out.writeUTF(value.owner)
                    out.writeUTF(value.desc)
                }
                is StringConstantKey -> out.writeByte(StringConstantKey.ID)
            }
        }

        override fun read(input: DataInput): BinaryIndexKey {
            val name = input.readUTF()
            return when (input.readUnsignedByte()) {
                ClassIndexKey.ID -> ClassIndexKey(name)
                FieldIndexKey.ID -> FieldIndexKey(input.readUTF(), name)
                MethodIndexKey.ID -> MethodIndexKey(input.readUTF(), name, input.readUTF())
                StringConstantKey.ID -> StringConstantKey(name)
                else -> throw IOException("Unknown data type")
            }
        }
    }

    override fun getValueExternalizer() = object : DataExternalizer<Int> {
        override fun save(out: DataOutput, value: Int) {
            DataInputOutputUtil.writeINT(out, value)
        }

        override fun read(`in`: DataInput) = DataInputOutputUtil.readINT(`in`)
    }

    override fun getVersion() = 0

    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(JavaClassFileType.INSTANCE)

    override fun dependsOnFileContent() = true

    companion object {
        val INDEX_ID = ID.create<BinaryIndexKey, Int>("classfileindexer.index")
    }
}
