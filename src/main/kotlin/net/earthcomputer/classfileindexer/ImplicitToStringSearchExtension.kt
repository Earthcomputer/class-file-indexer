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
            val declaringClass = queryParameters.targetMethod.containingClass ?: return@scope
            process(declaringClass, queryParameters, consumer)
            for (inheritor in ClassInheritorsSearch.search(declaringClass)) {
                process(inheritor, queryParameters, consumer)
            }
        }
        return true
    }

    private fun process(owningClass: PsiClass, queryParameters: ImplicitToStringSearch.SearchParameters, consumer: Processor<in PsiExpression>) {
        val internalName = owningClass.internalName ?: return
        val scope = queryParameters.searchScope as? GlobalSearchScope
            ?: GlobalSearchScope.EMPTY_SCOPE.union(queryParameters.searchScope)
        val files = mutableMapOf<VirtualFile, Map<String, Int>>()
        FileBasedIndex.getInstance().processValues(ClassFileIndexExtension.INDEX_ID, internalName, null, { file, value ->
            val v = value[ImplicitToStringKey.INSTANCE]
            if (v != null) {
                files[file] = v
            }
            true
        }, scope)
        if (files.isEmpty()) {
            return
        }
        for ((file, occurrences) in files) {
            val psiFile = PsiManager.getInstance(owningClass.project).findFile(file) as? PsiCompiledFile ?: continue
            for ((location, count) in occurrences) {
                repeat(count) { i ->
                    consumer.process(ImplicitToStringElement(psiFile, ImplicitToStringLocator(internalName, location, i)))
                }
            }
        }
    }

    class ImplicitToStringElement(
        file: PsiCompiledFile,
        locator: DecompiledSourceElementLocator<PsiExpression>
    ) : FakeDecompiledElement<PsiExpression>(file, file, locator), PsiExpression {
        override fun getType(): PsiType {
            return JavaPsiFacade.getElementFactory(file.project).createTypeByFQClassName(CommonClassNames.JAVA_LANG_STRING)
        }
    }
}
