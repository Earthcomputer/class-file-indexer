package net.earthcomputer.classfileindexer

import com.intellij.util.io.DataInputOutputUtil
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

sealed class BinaryIndexKey(private val id: Int) {
    override fun hashCode() = id
    override fun equals(other: Any?) = id == (other as? BinaryIndexKey)?.id
    override fun toString() = "${javaClass.simpleName}.INSTANCE"

    open fun write(output: DataOutput) {
        DataInputOutputUtil.writeINT(output, id)
    }
    companion object {
        fun read(input: DataInput): BinaryIndexKey {
            return when (DataInputOutputUtil.readINT(input)) {
                ClassIndexKey.ID -> ClassIndexKey.INSTANCE
                FieldIndexKey.ID -> FieldIndexKey.read(input)
                MethodIndexKey.ID -> MethodIndexKey.read(input)
                StringConstantKey.ID -> StringConstantKey.INSTANCE
                ImplicitToStringKey.ID -> ImplicitToStringKey.INSTANCE
                DelegateIndexKey.ID -> DelegateIndexKey.read(input)
                else -> throw IOException("Unknown binary index key type")
            }
        }
    }
}
class ClassIndexKey private constructor() : BinaryIndexKey(ID) {
    companion object {
        const val ID = 0
        val INSTANCE = ClassIndexKey()
    }
}
class FieldIndexKey(val owner: String, val isWrite: Boolean) : BinaryIndexKey(ID) {
    override fun hashCode() = 31 * (31 * owner.hashCode() + isWrite.hashCode()) + super.hashCode()
    override fun equals(other: Any?): Boolean {
        if (!super.equals(other)) return false
        val that = other as FieldIndexKey
        return owner == that.owner && isWrite == that.isWrite
    }
    override fun toString() = "FieldIndexKey($owner, $isWrite)"

    override fun write(output: DataOutput) {
        super.write(output)
        output.writeUTF(owner)
        output.writeBoolean(isWrite)
    }

    companion object {
        const val ID = 1
        fun read(input: DataInput) = FieldIndexKey(input.readUTF().intern(), input.readBoolean())
    }
}
class MethodIndexKey(val owner: String, val desc: String) : BinaryIndexKey(ID) {
    override fun hashCode() = 31 * (31 * owner.hashCode() + desc.hashCode()) + super.hashCode()
    override fun equals(other: Any?): Boolean {
        if (!super.equals(other)) return false
        val that = other as MethodIndexKey
        return owner == that.owner && desc == that.desc
    }
    override fun toString() = "MethodIndexKey($owner, $desc)"

    override fun write(output: DataOutput) {
        super.write(output)
        output.writeUTF(owner)
        output.writeUTF(desc)
    }

    companion object {
        const val ID = 2
        fun read(input: DataInput) = MethodIndexKey(input.readUTF().intern(), input.readUTF().intern())
    }
}
class StringConstantKey private constructor() : BinaryIndexKey(ID) {
    companion object {
        const val ID = 3
        val INSTANCE = StringConstantKey()
    }
}
class ImplicitToStringKey private constructor() : BinaryIndexKey(ID) {
    companion object {
        const val ID = 4
        val INSTANCE = ImplicitToStringKey()
    }
}
class DelegateIndexKey(val key: BinaryIndexKey) : BinaryIndexKey(ID) {
    override fun hashCode() = 31 * key.hashCode() + super.hashCode()
    override fun equals(other: Any?): Boolean {
        if (!super.equals(other)) return false
        val that = other as DelegateIndexKey
        return key == that.key
    }
    override fun toString() = "DelegateIndexKey($key)"

    override fun write(output: DataOutput) {
        super.write(output)
        key.write(output)
    }

    companion object {
        const val ID = 5
        fun read(input: DataInput) = DelegateIndexKey(BinaryIndexKey.read(input))
    }
}
