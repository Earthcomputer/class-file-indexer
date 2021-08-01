package net.earthcomputer.classfileindexer

import com.intellij.ide.util.EditorHelper
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.usageView.UsageViewUtil
import com.intellij.usages.UsageInfo2UsageAdapter

open class FakeDecompiledElement<T: PsiElement>(
    protected val file: PsiCompiledFile,
    private val myParent: PsiElement,
    private val locator: DecompiledSourceElementLocator<T>
) : FakePsiElement(), Navigatable, IHasNavigationOffset, IHasCustomDescription {

    companion object {
        val USAGE_VIEW_UTIL = UsageViewUtil::class.java.name
        val USAGE_INFO_2_UTIL_ADAPTER = UsageInfo2UsageAdapter::class.java.name
    }

    override fun getParent() = myParent

    override fun navigate(requestFocus: Boolean) {
        val result = findElement() ?: return
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

    override fun getNavigationOffset(): Int {
        val reason = StackWalker.getInstance().walk { stream ->
            stream.dropWhile { !it.className.startsWith("com.intellij.") }
                .dropWhile { it.methodName == "getNavigationOffset" }
                .findFirst().orElse(null)
        } ?: return 0
        val reasonClass = reason.className
        val methodName = reason.methodName
        return if ((reasonClass == USAGE_VIEW_UTIL && methodName == "navigateTo")
            || (reasonClass == USAGE_INFO_2_UTIL_ADAPTER && methodName == "getDescriptor")) {
            findElement()?.textOffset ?: 0
        } else {
            0
        }
    }

    override fun getCustomDescription() = "${locator.locationName}:${locator.locationDesc} #${locator.index}"

    private fun findElement(): T? {
        val clazz = (file.decompiledPsiFile as? PsiJavaFile)?.classes?.firstOrNull() ?: return null
        return locator.findElement(clazz)
    }
}
