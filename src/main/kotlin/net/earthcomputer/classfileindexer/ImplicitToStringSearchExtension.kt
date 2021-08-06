package net.earthcomputer.classfileindexer

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ImplicitToStringSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor

class ImplicitToStringSearchExtension : QueryExecutor<PsiExpression, ImplicitToStringSearch.SearchParameters> {
    override fun execute(
        queryParameters: ImplicitToStringSearch.SearchParameters,
        consumer: Processor<in PsiExpression>
    ): Boolean {
        runReadActionInSmartModeWithWritePriority(queryParameters.targetMethod.project, {
            queryParameters.targetMethod.isValid
        }) scope@{
            val files = mutableMapOf<VirtualFile, MutableMap<String, Int>>()
            val declaringClass = queryParameters.targetMethod.containingClass ?: return@scope
            addFiles(declaringClass, queryParameters, files)
            for (inheritor in ClassInheritorsSearch.search(declaringClass)) {
                addFiles(declaringClass, queryParameters, files)
            }
            val baseClassPtr = SmartPointerManager.createPointer(declaringClass)
            var id = 0
            for ((file, occurrences) in files) {
                val psiFile = findCompiledFileWithoutSources(declaringClass.project, file) ?: continue
                for ((location, count) in occurrences) {
                    repeat(count) { i ->
                        consumer.process(
                            ImplicitToStringElement(
                                id++,
                                psiFile,
                                ImplicitToStringLocator(baseClassPtr, file.nameWithoutExtension, location, i)
                            )
                        )
                    }
                }
            }
        }
        return true
    }

    private fun addFiles(
        owningClass: PsiClass,
        queryParameters: ImplicitToStringSearch.SearchParameters,
        files: MutableMap<VirtualFile, MutableMap<String, Int>>) {
        val internalName = owningClass.internalName ?: return
        val results = ClassFileIndex.search(internalName, ImplicitToStringKey.INSTANCE, queryParameters.searchScope)
        for ((file, sourceMap) in results) {
            val targetMap = files.computeIfAbsent(file) { mutableMapOf() }
            for ((k, v) in sourceMap) {
                targetMap.merge(k, v, Integer::sum)
            }
        }
    }

    class ImplicitToStringElement(
        id: Int,
        file: PsiCompiledFile,
        locator: DecompiledSourceElementLocator<PsiExpression>
    ) : FakeDecompiledElement<PsiExpression>(id, file, file, locator), PsiExpression {
        override fun getType(): PsiType {
            return JavaPsiFacade.getElementFactory(file.project).createTypeByFQClassName(CommonClassNames.JAVA_LANG_STRING)
        }
    }
}
