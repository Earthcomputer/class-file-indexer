package net.earthcomputer.classfileindexer

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiUtil

class FieldLocator(
    private val fieldPtr: SmartPsiElementPointer<PsiField>,
    private val isWrite: Boolean,
    className: String,
    location: String,
    index: Int
) : DecompiledSourceElementLocator<PsiElement>(className, location, index) {
    private var field: PsiField? = null

    override fun findElement(clazz: PsiClass): PsiElement? {
        field = fieldPtr.element ?: return null
        try {
            return super.findElement(clazz)
        } finally {
            field = null
        }
    }

    override fun visitReferenceExpression(expression: PsiReferenceExpression) {
        super.visitReferenceExpression(expression)
        if (PsiUtil.isAccessedForWriting(expression) == isWrite) {
            if (expression.isReferenceTo(field!!)) {
                matchElement(expression)
            }
        }
    }
}
