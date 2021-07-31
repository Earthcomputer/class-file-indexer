package net.earthcomputer.classfileindexer

import com.intellij.psi.*

class ImplicitToStringLocator(private val owner: String, location: String, index: Int): DecompiledSourceElementLocator<PsiExpression>(location, index) {
    override fun visitPolyadicExpression(expression: PsiPolyadicExpression) {
        if (expression.operationTokenType == JavaTokenType.PLUS) {
            val resultType = expression.type
            if (resultType != null && resultType.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
                for (operand in expression.operands) {
                    val operandType = operand.type
                    if (operandType is PsiClassType && operandType.resolve()?.internalName == owner) {
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
                if (rightType is PsiClassType && rightType.resolve()?.internalName == owner) {
                    matchElement(expression)
                }
            }
        }
        super.visitAssignmentExpression(expression)
    }
}
