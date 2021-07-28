package net.earthcomputer.classfileindexer

sealed class BinaryIndexKey(val name: String) {
    override fun hashCode() = name.hashCode()
    override fun equals(other: Any?) = (other as? BinaryIndexKey)?.name == name && javaClass == other.javaClass
}
class ClassIndexKey(name: String) : BinaryIndexKey(name) {
    companion object {
        const val ID = 0
    }
}
class FieldIndexKey(val owner: String, name: String) : BinaryIndexKey(name) {
    override fun hashCode() = 31 * owner.hashCode() + super.hashCode()
    override fun equals(other: Any?) = super.equals(other) && (other as FieldIndexKey).owner == owner
    companion object {
        const val ID = 1
    }
}
class MethodIndexKey(val owner: String, name: String, val desc: String) : BinaryIndexKey(name) {
    override fun hashCode() = 31 * (31 * owner.hashCode() + desc.hashCode()) + super.hashCode()
    override fun equals(other: Any?): Boolean {
        if (!super.equals(other)) return false
        val that = other as MethodIndexKey
        return owner == that.owner && desc == that.name
    }
    companion object {
        const val ID = 2
    }
}
class StringConstantKey(value: String) : BinaryIndexKey(value) {
    val value
        get() = name
    companion object {
        const val ID = 3
    }
}
