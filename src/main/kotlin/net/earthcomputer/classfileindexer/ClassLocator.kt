package net.earthcomputer.classfileindexer

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceList
import com.intellij.psi.PsiTypeElement

class ClassLocator(
    internalName: String,
    location: String,
    index: Int
) : DecompiledSourceElementLocator<PsiElement>(location, index) {
    private val descriptor = "L$internalName;"

    override fun visitTypeElement(typeElement: PsiTypeElement) {
        super.visitTypeElement(typeElement)

        if (isDescriptorOfType(descriptor, typeElement.type)) {
            matchElement(typeElement)
        }
    }

    override fun visitReferenceList(list: PsiReferenceList) {
        super.visitReferenceList(list)

        for ((element, type) in list.referenceElements.zip(list.referencedTypes)) {
            if (isDescriptorOfType(descriptor, type)) {
                matchElement(element)
            }
        }
    }
}
