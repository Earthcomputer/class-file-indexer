package net.earthcomputer.classfileindexer

import com.intellij.usages.TextChunk

interface IHasCustomDescription {
    fun getCustomDescription(): Array<TextChunk>
}
