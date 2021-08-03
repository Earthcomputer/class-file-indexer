package net.earthcomputer.classfileindexer

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ImplicitToStringSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor
import com.intellij.util.indexing.FileBasedIndex

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
                val psiFile = PsiManager.getInstance(declaringClass.project).findFile(file) as? PsiCompiledFile ?: continue
                for ((location, count) in occurrences) {
                    repeat(count) { i ->
                        consumer.process(ImplicitToStringElement(id++, psiFile, ImplicitToStringLocator(baseClassPtr, location, i)))
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
        val scope = queryParameters.searchScope as? GlobalSearchScope
            ?: GlobalSearchScope.EMPTY_SCOPE.union(queryParameters.searchScope)
        FileBasedIndex.getInstance().processValues(ClassFileIndexExtension.INDEX_ID, internalName, null, { file, value ->
            val v = value[ImplicitToStringKey.INSTANCE]
            if (v != null) {
                files.computeIfAbsent(file) { mutableMapOf() }.putAll(v)
            }
            true
        }, scope)
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
