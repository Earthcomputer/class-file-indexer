package net.earthcomputer.classfileindexer

sealed class BinaryIndexKey(val id: Int) {
    override fun hashCode() = id
    override fun equals(other: Any?) = id == (other as? BinaryIndexKey)?.id
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
    companion object {
        const val ID = 1
    }
}
class MethodIndexKey(val owner: String, val desc: String) : BinaryIndexKey(ID) {
    override fun hashCode() = 31 * (31 * owner.hashCode() + desc.hashCode()) + super.hashCode()
    override fun equals(other: Any?): Boolean {
        if (!super.equals(other)) return false
        val that = other as MethodIndexKey
        return owner == that.owner && desc == that.desc
    }
    companion object {
        const val ID = 2
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
