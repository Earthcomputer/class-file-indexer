package net.earthcomputer.classfileindexer

import com.intellij.ide.util.EditorHelper
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.impl.FakePsiElement

open class FakeDecompiledElement<T: PsiElement>(
    protected val file: PsiCompiledFile,
    private val myParent: PsiElement,
    private val locator: DecompiledSourceElementLocator<T>
) : FakePsiElement(), Navigatable {

    override fun getParent() = myParent

    override fun navigate(requestFocus: Boolean) {
        val clazz = (file.decompiledPsiFile as? PsiJavaFile)?.classes?.firstOrNull() ?: return
        val result = locator.findElement(clazz) ?: return
        val navigatable = result as? Navigatable
        if (navigatable != null) {
            if (navigatable.canNavigate()) {
                navigatable.navigate(requestFocus)
            }
        } else {
            EditorHelper.openInEditor(result)
        }
    }

    override fun canNavigate() = true
}
