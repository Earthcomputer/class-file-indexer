package net.earthcomputer.classfileindexer

import java.lang.IllegalStateException
import kotlin.ConcurrentModificationException
import kotlin.NoSuchElementException

// A mutable version of SmartFMap
class SmartMap<K: Any?, V: Any>: AbstractMutableMap<K, V>() {
    companion object {
        const val ARRAY_THRESHOLD = 8
    }

    private var value: Any = emptyArray<Any?>()

    @Suppress("UNCHECKED_CAST")
    override fun put(key: K, value: V): V? {
        when (val thisVal = this.value) {
            is Array<*> -> {
                thisVal as Array<Any?>
                for (i in thisVal.indices step 2) {
                    if (key == thisVal[i]) {
                        val old = thisVal[i + 1]
                        thisVal[i + 1] = value
                        return old as V
                    }
                }
                if (thisVal.size * 2 > ARRAY_THRESHOLD) {
                    convertToMap()
                    return (this.value as MutableMap<K, V>).put(key, value)
                }
                val newVal = thisVal.copyOf(thisVal.size + 2)
                newVal[thisVal.size] = key
                newVal[thisVal.size + 1] = value
                this.value = newVal
                return null
            }
            is MutableMap<*, *> -> {
                thisVal as MutableMap<K, V>
                return thisVal.put(key, value)
            }
            else -> throw AssertionError()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun get(key: K): V? {
        return when (val thisVal = value) {
            is Array<*> -> {
                for (i in thisVal.indices step 2) {
                    if (thisVal[i] == key) {
                        return thisVal[i + 1] as V
                    }
                }
                null
            }
            is MutableMap<*, *> -> thisVal[key] as V?
            else -> throw AssertionError()
        }
    }

    override fun containsKey(key: K): Boolean {
        return when (val thisVal = value) {
            is Array<*> -> {
                for (i in thisVal.indices step 2) {
                    if (thisVal[i] == key) {
                        return true
                    }
                }
                false
            }
            is MutableMap<*, *> -> thisVal.containsKey(key)
            else -> throw AssertionError()
        }
    }

    override fun containsValue(value: V): Boolean {
        return when (val thisVal = value) {
            is Array<*> -> {
                for (i in thisVal.indices step 2) {
                    if (thisVal[i + 1] == value) {
                        return true
                    }
                }
                false
            }
            is MutableMap<*, *> -> thisVal.containsValue(value)
            else -> throw AssertionError()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun remove(key: K): V? {
        return when (val thisVal = value) {
            is Array<*> -> {
                for (i in thisVal.indices step 2) {
                    if (thisVal[i] == key) {
                        val prevVal = thisVal[i + 1] as V
                        val newArr = thisVal.copyOf(thisVal.size - 2)
                        System.arraycopy(thisVal, i + 2, newArr, i, thisVal.size - (i + 2))
                        value = newArr
                        return prevVal
                    }
                }
                null
            }
            is MutableMap<*, *> -> {
                thisVal as MutableMap<K, V>
                val prevVal = thisVal.remove(key)
                if (thisVal.size <= ARRAY_THRESHOLD) {
                    convertToArray()
                }
                prevVal
            }
            else -> throw AssertionError()
        }
    }

    override fun clear() {
        value = emptyArray<Any?>()
    }

    override val size
        get() = when (val thisVal = value) {
            is Array<*> -> thisVal.size / 2
            is MutableMap<*, *> -> thisVal.size
            else -> throw AssertionError()
        }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = entriesCache

    private val entriesCache by lazy {
        object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
            private inner class ArrayItr : MutableIterator<MutableMap.MutableEntry<K, V>> {
                private var index = 0
                private var canRemove = false
                private var removed = false

                override fun hasNext() = index < (value as? Array<*> ?: throw ConcurrentModificationException()).size

                @Suppress("UNCHECKED_CAST")
                override fun next(): MutableMap.MutableEntry<K, V> {
                    val thisVal1 = value as? Array<*> ?: throw ConcurrentModificationException()
                    if (removed) throw IllegalStateException()
                    if (index >= thisVal1.size) throw NoSuchElementException()
                    removed = false
                    val ret = MutableEntry(thisVal1[index] as K, thisVal1[index + 1] as V, this@SmartMap, index)
                    index += 2
                    canRemove = true
                    return ret
                }

                @Suppress("UNCHECKED_CAST")
                override fun remove() {
                    if (!canRemove) throw IllegalStateException()
                    canRemove = false
                    removed = true
                    val thisVal1 = value as? Array<Any?> ?: throw ConcurrentModificationException()
                    if (index >= thisVal1.size + 2) throw ConcurrentModificationException()
                    val newVal = thisVal1.copyOf(thisVal1.size - 2)
                    System.arraycopy(thisVal1, index, newVal, index - 2, thisVal1.size - index)
                    index -= 2
                    value = newVal
                }
            }

            override fun add(element: MutableMap.MutableEntry<K, V>): Boolean {
                return put(element.key, element.value) == null
            }

            @Suppress("UNCHECKED_CAST")
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
                return when (val thisVal = value) {
                    is Array<*> -> ArrayItr()
                    is MutableMap<*, *> -> {
                        thisVal as MutableMap<K, V>
                        thisVal.entries.iterator()
                    }
                    else -> throw AssertionError()
                }
            }

            override val size = this@SmartMap.size

            override fun contains(element: MutableMap.MutableEntry<K, V>) = containsKey(element.key)
        }
    }

    private class MutableEntry<K: Any?, V: Any>(
        private val k: K,
        v: V,
        private val smartMap: SmartMap<K, V>,
        private var index: Int
    ) : MutableMap.MutableEntry<K, V> {
        private var cachedValue: V = v

        override val key = k

        @Suppress("UNCHECKED_CAST")
        override val value: V
            get() {
                return when (val thisVal = smartMap.value) {
                    is Array<*> -> {
                        if (index < thisVal.size && thisVal[index] == k) {
                            cachedValue = thisVal[index + 1] as V
                            return cachedValue
                        }
                        for (i in thisVal.indices step 2) {
                            if (thisVal[i] == k) {
                                index = i
                                cachedValue = thisVal[index + 1] as V
                                return cachedValue
                            }
                        }
                        cachedValue
                    }
                    is MutableMap<*, *> -> {
                        val ret = thisVal[k] as V?
                        if (ret != null) cachedValue = ret
                        cachedValue
                    }
                    else -> throw AssertionError()
                }
            }

        @Suppress("UNCHECKED_CAST")
        override fun setValue(newValue: V): V {
            val defaultOldVal = cachedValue
            cachedValue = newValue
            return when (val thisVal = smartMap.value) {
                is Array<*> -> {
                    thisVal as Array<Any?>
                    if (index < thisVal.size && thisVal[index] == k) {
                        val oldVal = thisVal[index + 1] as V
                        thisVal[index + 1] = newValue
                        return oldVal
                    }
                    for (i in thisVal.indices step 2) {
                        index = i
                        val oldVal = thisVal[i + 1] as V
                        thisVal[i + 1] = newValue
                        return oldVal
                    }
                    defaultOldVal
                }
                is MutableMap<*, *> -> {
                    thisVal as MutableMap<K, V>
                    thisVal.put(k, newValue) ?: defaultOldVal
                }
                else -> throw AssertionError()
            }
        }

        override fun equals(other: Any?): Boolean {
            val that = other as? Map.Entry<*, *> ?: return false
            return key == that.key && value == that.value
        }

        override fun hashCode(): Int {
            return key.hashCode() xor value.hashCode()
        }

        override fun toString(): String {
            return "($key, $value)"
        }
    }


    private fun convertToMap() {
        val arr = value as Array<*>
        val newVal = hashMapOf<Any?, Any?>()
        for (i in arr.indices step 2) {
            newVal[arr[i]] = arr[i + 1]
        }
        value = newVal
    }

    private fun convertToArray() {
        val map = value as Map<*, *>
        val newVal = arrayOfNulls<Any?>(map.size * 2)
        for ((index, entry) in map.entries.withIndex()) {
            newVal[index * 2] = entry.key
            newVal[index * 2 + 1] = entry.value
        }
    }
}
