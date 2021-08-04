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

class ClassFileIndexExtension : FileBasedIndexExtension<String, Map<BinaryIndexKey, Map<String, Int>>>() {
    override fun getName() = INDEX_ID

    override fun getIndexer() = DataIndexer<String, Map<BinaryIndexKey, Map<String, Int>>, FileContent> { content ->
        val bytes = content.content
        val cv = IndexerClassVisitor()
        ClassReader(bytes).accept(cv, ClassReader.SKIP_FRAMES)
        cv.index as Map<String, Map<BinaryIndexKey, Map<String, Int>>>
    }

    override fun getKeyDescriptor() = object : KeyDescriptor<String> {
        override fun getHashCode(value: String): Int = value.hashCode()

        override fun isEqual(val1: String, val2: String) = val1 == val2

        override fun save(out: DataOutput, value: String) {
            out.writeUTF(value)
        }

        override fun read(input: DataInput) = input.readUTF().intern()
    }

    override fun getValueExternalizer() = object : DataExternalizer<Map<BinaryIndexKey, Map<String, Int>>> {
        override fun save(out: DataOutput, value: Map<BinaryIndexKey, Map<String, Int>>) {
            DataInputOutputUtil.writeINT(out, value.size)
            for ((key, counts) in value) {
                DataInputOutputUtil.writeINT(out, key.id)
                when (key) {
                    is FieldIndexKey -> {
                        out.writeUTF(key.owner)
                        out.writeBoolean(key.isWrite)
                    }
                    is MethodIndexKey -> {
                        out.writeUTF(key.owner)
                        out.writeUTF(key.desc)
                    }
                    else -> {
                        // no extra data
                    }
                }
                DataInputOutputUtil.writeINT(out, counts.size)
                for ((location, count) in counts) {
                    out.writeUTF(location)
                    DataInputOutputUtil.writeINT(out, count)
                }
            }
        }

        override fun read(input: DataInput): Map<BinaryIndexKey, Map<String, Int>> {
            val result = SmartMap<BinaryIndexKey, MutableMap<String, Int>>()
            repeat(DataInputOutputUtil.readINT(input)) {
                val key = when (DataInputOutputUtil.readINT(input)) {
                    ClassIndexKey.ID -> ClassIndexKey.INSTANCE
                    FieldIndexKey.ID -> FieldIndexKey(input.readUTF().intern(), input.readBoolean())
                    MethodIndexKey.ID -> MethodIndexKey(input.readUTF().intern(), input.readUTF().intern())
                    StringConstantKey.ID -> StringConstantKey.INSTANCE
                    ImplicitToStringKey.ID -> ImplicitToStringKey.INSTANCE
                    else -> throw IOException("Unknown key type")
                }
                val counts = SmartMap<String, Int>()
                repeat(DataInputOutputUtil.readINT(input)) {
                    val location = input.readUTF().intern()
                    val count = DataInputOutputUtil.readINT(input)
                    counts[location] = count
                }
                result[key] = counts
            }
            return result
        }
    }

    override fun getVersion() = 2

    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(JavaClassFileType.INSTANCE)

    override fun dependsOnFileContent() = true

    companion object {
        val INDEX_ID = ID.create<String, Map<BinaryIndexKey, Map<String, Int>>>("classfileindexer.index")
    }
}
