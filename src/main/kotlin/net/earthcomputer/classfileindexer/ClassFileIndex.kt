package net.earthcomputer.classfileindexer

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.util.indexing.FileBasedIndex
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.ClassReader

object ClassFileIndex {
    fun search(name: String, key: BinaryIndexKey, scope: SearchScope): Map<VirtualFile, Map<String, Int>> {
        val globalScope = asGlobal(scope)
        val files = mutableMapOf<VirtualFile, MutableMap<String, Int>>()
        val locationsToSearchFurther = mutableSetOf<Pair<String, String>>()
        FileBasedIndex.getInstance().processValues(
            ClassFileIndexExtension.INDEX_ID, name, null,
            { file, value ->
                ProgressManager.checkCanceled()
                val className by lazy {
                    file.inputStream.use {
                        ClassReader(it).className
                    }
                }
                value[key]?.let {
                    files[file] = it.toMutableMap()
                }
                value[DelegateIndexKey(key)]?.let { delegate ->
                    delegate.keys.mapTo(locationsToSearchFurther) { Pair(it, className) }
                }
                true
            },
            globalScope
        )
        for ((location, owner) in locationsToSearchFurther) {
            searchLocation(location, owner, globalScope) { file, sourceMap ->
                val targetMap = files.computeIfAbsent(file) { mutableMapOf() }
                for ((k, v) in sourceMap) {
                    targetMap.merge(k, v, Integer::sum)
                }
            }
        }
        return files
    }

    fun search(
        name: String,
        keyPredicate: (BinaryIndexKey) -> Boolean,
        scope: SearchScope
    ): Map<VirtualFile, Map<String, Int>> {
        val result = mutableMapOf<VirtualFile, Map<String, Int>>()
        for ((file, keys) in searchReturnKeys(name, keyPredicate, scope)) {
            val targetMap = mutableMapOf<String, Int>()
            for (value in keys.values) {
                for ((k, v) in value) {
                    targetMap.merge(k, v, Integer::sum)
                }
            }
            result[file] = targetMap
        }
        return result
    }

    fun searchReturnKeys(
        name: String,
        keyPredicate: (BinaryIndexKey) -> Boolean,
        scope: SearchScope
    ): Map<VirtualFile, Map<BinaryIndexKey, Map<String, Int>>> {
        val globalScope = asGlobal(scope)
        val files = mutableMapOf<VirtualFile, MutableMap<BinaryIndexKey, MutableMap<String, Int>>>()
        val locationsToSearchFurther = mutableSetOf<Triple<BinaryIndexKey, String, String>>()
        FileBasedIndex.getInstance().processValues(
            ClassFileIndexExtension.INDEX_ID, name, null,
            { file, value ->
                ProgressManager.checkCanceled()
                val className by lazy {
                    file.inputStream.use {
                        ClassReader(it).className
                    }
                }
                for ((key, v) in value) {
                    if (keyPredicate(key)) {
                        files.computeIfAbsent(file) { mutableMapOf() }[key] = v.toMutableMap()
                    } else if (key is DelegateIndexKey && keyPredicate(key.key)) {
                        v.keys.mapTo(locationsToSearchFurther) { Triple(key.key, it, className) }
                    }
                }
                true
            },
            globalScope
        )
        for ((key, location, owner) in locationsToSearchFurther) {
            searchLocation(location, owner, globalScope) { file, sourceMap ->
                val targetMap = files.computeIfAbsent(file) { mutableMapOf() }
                    .computeIfAbsent(key) { mutableMapOf() }
                for ((k, v1) in sourceMap) {
                    targetMap.merge(k, v1, Integer::sum)
                }
            }
        }
        return files
    }

    private fun searchLocation(
        location: String,
        owner: String,
        scope: GlobalSearchScope,
        consumer: (VirtualFile, Map<String, Int>) -> Unit
    ) {
        RecursionManager.doPreventingRecursion(Pair(location, owner), true) {
            val name = location.substringBefore(":")
            val desc = location.substringAfter(":")
            if (desc.contains("(")) {
                search(name, MethodIndexKey(owner, desc), scope).forEach(consumer)
            } else {
                search(name, FieldIndexKey(owner, false), scope).forEach(consumer)
                search(name, FieldIndexKey(owner, true), scope).forEach(consumer)
            }
        }
    }

    private fun asGlobal(scope: SearchScope) = scope as? GlobalSearchScope ?: GlobalSearchScope.EMPTY_SCOPE.union(scope)
}
