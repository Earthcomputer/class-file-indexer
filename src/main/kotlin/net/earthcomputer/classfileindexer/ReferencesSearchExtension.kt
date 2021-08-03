package net.earthcomputer.classfileindexer

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor
import com.intellij.util.indexing.FileBasedIndex

class ReferencesSearchExtension : QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
    override fun execute(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ): Boolean {
        val scope = queryParameters.effectiveSearchScope as? GlobalSearchScope
            ?: GlobalSearchScope.EMPTY_SCOPE.union(queryParameters.effectiveSearchScope)
        when (val element = queryParameters.elementToSearch) {
            is PsiField -> processField(element, queryParameters, consumer, scope)
            is PsiClass -> processClass(element, queryParameters, consumer, scope)
        }

        return true
    }

    private fun processField(element: PsiField, queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>, scope: GlobalSearchScope) {
        runReadActionInSmartModeWithWritePriority(queryParameters.project, {queryParameters.isQueryValid}) scope@{
            val fieldName = element.name
            val declaringClass = element.containingClass ?: return@scope
            val validOwnerNames = mutableSetOf(declaringClass.internalName)
            for (inheritor in ClassInheritorsSearch.search(declaringClass)) {
                validOwnerNames.add(inheritor.internalName)
            }
            val readFiles = mutableMapOf<VirtualFile, MutableMap<String, Int>>()
            val writeFiles = mutableMapOf<VirtualFile, MutableMap<String, Int>>()
            FileBasedIndex.getInstance().processValues(ClassFileIndexExtension.INDEX_ID, fieldName, null, { file, value ->
                for ((key, v) in value) {
                    if (key is FieldIndexKey && validOwnerNames.contains(key.owner)) {
                        if (key.isWrite) {
                            writeFiles.computeIfAbsent(file) { mutableMapOf() }.putAll(v)
                        } else {
                            readFiles.computeIfAbsent(file) { mutableMapOf() }.putAll(v)
                        }
                    }
                }
                true
            }, scope)
            if (readFiles.isEmpty() && writeFiles.isEmpty()) {
                return@scope
            }
            val smartFieldPtr = SmartPointerManager.createPointer(element)
            var id = 0
            fun processFiles(files: Map<VirtualFile, MutableMap<String, Int>>, isWrite: Boolean) {
                for ((file, occurrences) in files) {
                    val psiFile = PsiManager.getInstance(queryParameters.project).findFile(file) as? PsiCompiledFile ?: continue
                    for ((location, count) in occurrences) {
                        repeat(count) { i ->
                            consumer.process(
                                FieldRefElement(id++, psiFile, FieldLocator(smartFieldPtr, isWrite, location, i), isWrite)
                                    .createReference(element)
                            )
                        }
                    }
                }
            }
            processFiles(readFiles, false)
            processFiles(writeFiles, true)
        }
    }

    private fun processClass(element: PsiClass, queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>, scope: GlobalSearchScope) {
        runReadActionInSmartModeWithWritePriority(queryParameters.project, { queryParameters.isQueryValid }) scope@{
            val internalName = element.internalName ?: return@scope
            val files = mutableMapOf<VirtualFile, Map<String, Int>>()
            FileBasedIndex.getInstance().processValues(ClassFileIndexExtension.INDEX_ID, internalName, null, { file, value ->
                val v = value[ClassIndexKey.INSTANCE]
                if (v != null) {
                    files[file] = v
                }
                true
            }, scope)
            if (files.isEmpty()) {
                return@scope
            }
            var id = 0
            for ((file, occurrences) in files) {
                val psiFile = PsiManager.getInstance(queryParameters.project).findFile(file) as? PsiCompiledFile ?: continue
                for ((location, count) in occurrences) {
                    repeat(count) { i ->
                        consumer.process(ClassRefElement(id++, psiFile, ClassLocator(internalName, location, i)).createReference(element))
                    }
                }
            }
        }
    }

    class FieldRefElement(
        id: Int,
        file: PsiCompiledFile,
        locator: DecompiledSourceElementLocator<PsiElement>,
        private val myIsWrite: Boolean
    ) : FakeDecompiledElement<PsiElement>(id, file, file, locator), PsiElement, IIsWriteOverride {
        override fun isWrite() = myIsWrite
    }

    class ClassRefElement(
        id: Int,
        file: PsiCompiledFile,
        locator: DecompiledSourceElementLocator<PsiElement>
    ) : FakeDecompiledElement<PsiElement>(id, file, file, locator)
}
