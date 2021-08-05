package net.earthcomputer.classfileindexer

import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil

class ImplicitToStringLocator(
    private val baseClassPtr: SmartPsiElementPointer<PsiClass>,
    className: String,
    location: String,
    index: Int
): DecompiledSourceElementLocator<PsiExpression>(className, location, index) {
    private var baseClass: PsiClass? = null

    override fun findElement(clazz: PsiClass): PsiExpression? {
        baseClass = baseClassPtr.element ?: return null
        try {
            return super.findElement(clazz)
        } finally {
            baseClass = null
        }
    }

    private fun isOurType(type: PsiType): Boolean {
        val resolved = (type as? PsiClassType)?.resolve() ?: return false
        return InheritanceUtil.isInheritorOrSelf(resolved, baseClass, true)
    }

    override fun visitPolyadicExpression(expression: PsiPolyadicExpression) {
        if (expression.operationTokenType == JavaTokenType.PLUS) {
            val resultType = expression.type
            if (resultType != null && resultType.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
                for (operand in expression.operands) {
                    val operandType = operand.type
                    if (operandType != null && isOurType(operandType)) {
                        matchElement(expression)
                    }
                }
            }
        }
        super.visitPolyadicExpression(expression)
    }

    override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
        if (expression.operationTokenType == JavaTokenType.PLUSEQ) {
            val leftType = expression.lExpression.type
            if (leftType != null && leftType.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
                val rightType = expression.rExpression?.type
                if (rightType != null && isOurType(rightType)) {
                    matchElement(expression)
                }
            }
        }
        super.visitAssignmentExpression(expression)
    }
}
