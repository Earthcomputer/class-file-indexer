package net.earthcomputer.classfileindexer

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiReference
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor

class ReferencesSearchExtension : QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
    override fun execute(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ): Boolean {
        when (val element = queryParameters.elementToSearch) {
            is PsiField -> processField(element, queryParameters, consumer, queryParameters.effectiveSearchScope)
            is PsiClass -> processClass(element, queryParameters, consumer, queryParameters.effectiveSearchScope)
        }

        return true
    }

    private fun processField(
        element: PsiField,
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
        scope: SearchScope
    ) {
        runReadActionInSmartModeWithWritePriority(queryParameters.project, { queryParameters.isQueryValid }) scope@{
            val fieldName = element.name
            val declaringClass = element.containingClass ?: return@scope
            val validOwnerNames = mutableSetOf(declaringClass.internalName)
            for (inheritor in ClassInheritorsSearch.search(declaringClass)) {
                validOwnerNames.add(inheritor.internalName)
            }
            val readFiles = mutableMapOf<VirtualFile, Map<String, Int>>()
            val writeFiles = mutableMapOf<VirtualFile, Map<String, Int>>()
            val results = ClassFileIndex.searchReturnKeys(
                fieldName,
                { key ->
                    key is FieldIndexKey && validOwnerNames.contains(key.owner)
                },
                scope
            )
            if (results.isEmpty()) {
                return@scope
            }
            for ((file, keys) in results) {
                for ((key, value) in keys) {
                    if ((key as FieldIndexKey).isWrite) {
                        writeFiles[file] = value
                    } else {
                        readFiles[file] = value
                    }
                }
            }
            val smartFieldPtr = SmartPointerManager.createPointer(element)
            var id = 0
            fun processFiles(files: Map<VirtualFile, Map<String, Int>>, isWrite: Boolean) {
                for ((file, occurrences) in files) {
                    val psiFile = findCompiledFileWithoutSources(queryParameters.project, file) ?: continue
                    for ((location, count) in occurrences) {
                        repeat(count) { i ->
                            consumer.process(
                                FieldRefElement(id++, psiFile, FieldLocator(smartFieldPtr, isWrite, file.nameWithoutExtension, location, i), isWrite)
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

    private fun processClass(
        element: PsiClass,
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
        scope: SearchScope
    ) {
        runReadActionInSmartModeWithWritePriority(queryParameters.project, { queryParameters.isQueryValid }) scope@{
            val internalName = element.internalName ?: return@scope
            val files = ClassFileIndex.search(internalName, ClassIndexKey.INSTANCE, scope)
            if (files.isEmpty()) {
                return@scope
            }
            var id = 0
            for ((file, occurrences) in files) {
                val psiFile = findCompiledFileWithoutSources(queryParameters.project, file) ?: continue
                for ((location, count) in occurrences) {
                    repeat(count) { i ->
                        consumer.process(
                            ClassRefElement(
                                id++,
                                psiFile,
                                ClassLocator(internalName, file.nameWithoutExtension, location, i)
                            ).createReference(element)
                        )
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
