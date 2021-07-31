package net.earthcomputer.classfileindexer

import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor

class MethodReferencesSearchExtension : QueryExecutor<PsiReference, MethodReferencesSearch.SearchParameters> {
    override fun execute(
        queryParameters: MethodReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ): Boolean {
        // TODO("Not yet implemented")
        return true
    }
}
