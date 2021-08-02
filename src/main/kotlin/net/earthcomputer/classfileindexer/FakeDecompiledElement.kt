package net.earthcomputer.classfileindexer

import com.intellij.ide.highlighter.JavaHighlightingColors
import com.intellij.ide.util.EditorHelper
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.pom.Navigatable
import com.intellij.psi.*
import com.intellij.psi.impl.FakePsiElement
import com.intellij.usageView.UsageTreeColors
import com.intellij.usageView.UsageTreeColorsScheme
import com.intellij.usageView.UsageViewUtil
import com.intellij.usages.TextChunk
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.impl.UsagePreviewPanel
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Type
import java.util.stream.Collectors

open class FakeDecompiledElement<T: PsiElement>(
    private val id: Int,
    protected val file: PsiCompiledFile,
    private val myParent: PsiElement,
    private val locator: DecompiledSourceElementLocator<T>,
) : FakePsiElement(), Navigatable, IHasNavigationOffset, IHasCustomDescription {

    companion object {
        private val USAGE_VIEW_UTIL: String = UsageViewUtil::class.java.name
        private val USAGE_INFO_2_UTIL_ADAPTER: String = UsageInfo2UsageAdapter::class.java.name
        private val USAGE_PREVIEW_PANEL: String = UsagePreviewPanel::class.java.name
    }

    fun createReference(target: PsiElement): PsiReference {
        val self = this
        val ref = PsiReferenceBase.createSelfReference(this, target)
        return object : PsiReference by ref {
            override fun getRangeInElement() = self.textRange
        }
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

    override fun getTextRange() = getTextRange(false)

    private fun getTextRange(shiftForCursor: Boolean): TextRange {
        val stackFrames = StackWalker.getInstance().walk { stream ->
            stream.dropWhile { !it.className.startsWith("com.intellij.") }
                .dropWhile { it.methodName == "getNavigationOffset" || it.methodName == "getNavigationRange" }
                .limit(2)
                .collect(Collectors.toList())
        } ?: return TextRange(id * 2, id * 2 + 1)
        val reason = stackFrames.firstOrNull() ?: return TextRange(id * 2, id * 2 + 1)
        val reasonClass = reason.className
        val methodName = reason.methodName
        val isHighlightMethod = reasonClass == USAGE_PREVIEW_PANEL && methodName == "highlight"
        return if ((reasonClass == USAGE_VIEW_UTIL && methodName == "navigateTo")
            || (reasonClass == USAGE_INFO_2_UTIL_ADAPTER && methodName == "getDescriptor")
            || isHighlightMethod) {
            val element = findElement() ?: return TextRange(id * 2, id * 2 + 1)
            val range = element.textRange ?: return TextRange(id * 2, id * 2 + 1)
            val secondFrame = stackFrames.getOrNull(1) ?: return TextRange(id * 2, id * 2 + 1)
            if (shiftForCursor || isHighlightMethod || (secondFrame.className == USAGE_INFO_2_UTIL_ADAPTER && secondFrame.methodName == "openTextEditor")) {
                range.shiftRight(element.textOffset - range.startOffset)
            } else {
                range
            }
        } else {
            TextRange(id * 2, id * 2 + 1)
        }
    }

    override fun getTextRangeInParent() = textRange

    override fun getTextLength() = 1

    override fun getTextOffset() = getTextRange(true).startOffset

    override fun getText() = "A"

    override fun getLineNumber() = id

    override fun getNavigationOffset() = getTextRange(true).startOffset

    override fun getCustomDescription(): Array<TextChunk> {
        val colorScheme = UsageTreeColorsScheme.getInstance().scheme
        val ret = mutableListOf(TextChunk(colorScheme.getAttributes(UsageTreeColors.USAGE_LOCATION), "#${locator.index + 1}"))

        fun makePresentableType(type: Type): List<TextChunk> {
            val plainType = if (type.sort == Type.ARRAY) {
                type.elementType
            } else {
                type
            }
            val plainSimpleName = plainType.className.split('.', '$').last()
            val plainAttr = if (plainType.isPrimitive()) {
                colorScheme.getAttributes(JavaHighlightingColors.KEYWORD)
            } else {
                colorScheme.getAttributes(JavaHighlightingColors.CLASS_NAME_ATTRIBUTES)
            }
            return when (type.sort) {
                Type.ARRAY -> listOf(
                    TextChunk(plainAttr, plainSimpleName),
                    TextChunk(colorScheme.getAttributes(JavaHighlightingColors.BRACKETS), "[]".repeat(type.dimensions))
                )
                else -> listOf(TextChunk(plainAttr, plainSimpleName))
            }
        }

        val methodType = if (locator.locationIsMethod) Type.getMethodType(locator.locationDesc) else null

        if (methodType != null) {
            ret.addAll(makePresentableType(methodType.returnType))
            ret += TextChunk(TextAttributes(), " ")
        } else if (locator.locationDesc.isNotEmpty()) {
            ret.addAll(makePresentableType(Type.getType(locator.locationDesc)))
            ret += TextChunk(TextAttributes(), "")
        }

        val nameAttr = if (methodType != null) {
            colorScheme.getAttributes(JavaHighlightingColors.METHOD_DECLARATION_ATTRIBUTES)
        } else {
            colorScheme.getAttributes(JavaHighlightingColors.INSTANCE_FIELD_ATTRIBUTES)
        }
        ret += TextChunk(nameAttr, locator.locationName)

        if (methodType != null) {
            ret += TextChunk(colorScheme.getAttributes(JavaHighlightingColors.PARENTHESES), "(")
            val argTypes = methodType.argumentTypes
            argTypes.asSequence().map {
                makePresentableType(it)
            }.withIndex().flatMap { (index, it) ->
                if (index == 0) {
                    sequenceOf(it)
                } else {
                    sequenceOf(
                        listOf(
                            TextChunk(colorScheme.getAttributes(JavaHighlightingColors.COMMA), ","),
                            TextChunk(TextAttributes(), " ")
                        ),
                        it
                    )
                }
            }.flatMap { it.asSequence() }
            .forEach { ret += it }
            ret += TextChunk(colorScheme.getAttributes(JavaHighlightingColors.PARENTHESES), ")")
        }

        return ret.toTypedArray()
    }

    private fun findElement(): T? {
        val clazz = (file.decompiledPsiFile as? PsiJavaFile)?.classes?.firstOrNull() ?: return null
        return locator.findElement(clazz)
    }
}
