package net.earthcomputer.classfileindexer

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiUtil

class FieldLocator(
    private val field: PsiField,
    private val isWrite: Boolean,
    location: String,
    index: Int
) : DecompiledSourceElementLocator<PsiElement>(location, index) {
    override fun visitReferenceExpression(expression: PsiReferenceExpression) {
        super.visitReferenceExpression(expression)
        if (PsiUtil.isAccessedForWriting(expression) == isWrite) {
            if (expression.isReferenceTo(field)) {
                matchElement(expression)
            }
        }
    }
}
