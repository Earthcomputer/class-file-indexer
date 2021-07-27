package net.earthcomputer.classfileindexer

sealed class BinaryIndexKey(val name: String) {
    override fun hashCode() = name.hashCode()
    override fun equals(other: Any?) = (other as? ClassIndexKey)?.name == name
}
class ClassIndexKey(name: String) : BinaryIndexKey(name)
class FieldIndexKey(val owner: String, name: String) : BinaryIndexKey(name) {
    override fun hashCode() = 31 * owner.hashCode() + super.hashCode()
    override fun equals(other: Any?) = super.equals(other) && (other as FieldIndexKey).owner == owner
}
class MethodIndexKey(val owner: String, name: String, val desc: String) : BinaryIndexKey(name) {
    override fun hashCode() = 31 * (31 * owner.hashCode() + desc.hashCode()) + super.hashCode()
    override fun equals(other: Any?): Boolean {
        if (!super.equals(other)) return false
        val that = other as MethodIndexKey
        return owner == that.owner && desc == that.name
    }
}
