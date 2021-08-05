package net.earthcomputer.classfileindexer

import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.MethodSignatureUtil

class MethodLocator(
    private val methodPtr: SmartPsiElementPointer<PsiMethod>,
    private val strict: Boolean,
    className: String,
    location: String,
    index: Int
) : DecompiledSourceElementLocator<PsiElement>(className, location, index) {
    private var method: PsiMethod? = null

    override fun findElement(clazz: PsiClass): PsiElement? {
        method = methodPtr.element ?: return null
        try {
            return super.findElement(clazz)
        } finally {
            method = null
        }
    }

    private fun isOurMethod(theMethod: PsiMethod?): Boolean {
        if (theMethod == null) {
            return false
        }
        val method = this.method!!
        if (method.isConstructor != theMethod.isConstructor) {
            return false
        }
        if (method.isConstructor && method.containingClass != theMethod.containingClass) {
            return false
        }

        return if (strict) {
            method == theMethod || MethodSignatureUtil.isSuperMethod(method, theMethod)
        } else {
            if (method.name != theMethod.name) {
                return false
            }
            if (method.isConstructor) {
                // already checked our conditions
                return true
            }
            if (method.hasModifierProperty(PsiModifier.PRIVATE)
                || method.hasModifierProperty(PsiModifier.STATIC)) {
                return method.containingClass == theMethod.containingClass
            }
            InheritanceUtil.isInheritorOrSelf(method.containingClass, method.containingClass, true)
        }
    }

    override fun visitNewExpression(expression: PsiNewExpression) {
        super.visitNewExpression(expression)
        if (isOurMethod(expression.resolveConstructor())) {
            matchElement(expression)
        }
    }

    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
        super.visitMethodCallExpression(expression)
        if (isOurMethod(expression.resolveMethod())) {
            matchElement(expression)
        }
    }

    override fun visitMethodReferenceExpression(expression: PsiMethodReferenceExpression) {
        super.visitMethodReferenceExpression(expression)
        if (isOurMethod(expression.resolve() as? PsiMethod)) {
            matchElement(expression)
        }
    }
}
