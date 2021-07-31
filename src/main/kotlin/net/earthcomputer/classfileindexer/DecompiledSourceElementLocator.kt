package net.earthcomputer.classfileindexer

import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil

open class DecompiledSourceElementLocator<T: PsiElement>(location: String, private val index: Int) : JavaElementVisitor() {
    private var foundElement: T? = null
    private var foundCount = 0
    private val locationName = location.substringBefore(':')
    private val locationDesc = location.substringAfter(':')
    private val locationIsMethod = locationDesc.contains("(")
    private var constructorCallsThis = false

    fun findElement(clazz: PsiClass): T? {
        foundElement = null
        foundCount = 0
        constructorCallsThis = false
        clazz.accept(this)
        return foundElement
    }

    protected fun matchElement(element: T) {
        if (isInLocation(element) && foundCount++ == index) {
            foundElement = element
        }
    }

    private fun isInLocation(element: PsiElement): Boolean {
        val parent = PsiTreeUtil.getParentOfType(
            element,
            PsiMethod::class.java,
            PsiField::class.java,
            PsiRecordComponent::class.java,
            PsiClass::class.java,
            PsiClassInitializer::class.java
        ) ?: return false
        when (parent) {
            is PsiMethod -> {
                if (!locationIsMethod) {
                    return false
                }
                if (parent.isConstructor) {
                    if (locationName != "<init>") {
                        return false
                    }
                } else {
                    if (locationName != parent.name) {
                        return false
                    }
                }
                return isDescriptorOfMethodType(locationDesc, parent)
            }
            is PsiField -> {
                val initializer = parent.initializer
                val enumConstantArgs = (parent as? PsiEnumConstant)?.argumentList
                val isInInitializer = !PsiUtil.isCompileTimeConstant(parent)
                        && ((initializer != null && PsiTreeUtil.isAncestor(element, initializer, false))
                            || (enumConstantArgs != null && PsiTreeUtil.isAncestor(element, enumConstantArgs, false)))
                if (isInInitializer) {
                    if (!locationIsMethod) {
                        return false
                    }
                    val isStatic = parent.hasModifierProperty(PsiModifier.STATIC) || parent is PsiEnumConstant
                    return if (isStatic) {
                        locationName == "<clinit>"
                    } else {
                        locationName == "<init>" && !constructorCallsThis
                    }
                } else {
                    if (locationIsMethod || locationName.isEmpty()) {
                        return false
                    }
                    return parent.name == locationName && isDescriptorOfType(locationDesc, parent.type)
                }
            }
            is PsiRecordComponent -> {
                if (locationIsMethod || locationName.isEmpty()) {
                    return false
                }
                return parent.name == locationName && isDescriptorOfType(locationDesc, parent.type)
            }
            is PsiClass -> {
                return locationName.isEmpty()
            }
            is PsiClassInitializer -> {
                if (!locationIsMethod) {
                    return false
                }
                val isStatic = parent.hasModifierProperty(PsiModifier.STATIC)
                return if (isStatic) {
                    locationName == "<clinit>"
                } else {
                    locationName == "<init>" && !constructorCallsThis
                }
            }
            else -> throw AssertionError()
        }
    }

    override fun visitAnonymousClass(clazz: PsiAnonymousClass) {
        // TODO: handle anonymous classes properly
    }

    override fun visitClass(clazz: PsiClass) {
        if (clazz.getParentOfType<PsiClass>() != null) {
            // TODO: handle inner classes properly
        } else {
            if (locationIsMethod && locationName == "<init>") {
                for (constructor in clazz.constructors) {
                    if (isDescriptorOfMethodType(locationDesc, constructor)) {
                        val firstStatement = constructor.body?.statements?.getOrNull(0) ?: break
                        val firstExpression = (firstStatement as? PsiExpressionStatement)?.expression ?: break
                        val firstMethodExpression = (firstExpression as? PsiMethodCallExpression)?.methodExpression ?: break
                        val methodName = firstMethodExpression.referenceName ?: break
                        if (methodName == "this") {
                            constructorCallsThis = true
                        }
                        break
                    }
                }
            }
            super.visitClass(clazz)
        }
    }
}
