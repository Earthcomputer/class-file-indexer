package net.earthcomputer.classfileindexer

import com.intellij.psi.*
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor

class MethodReferencesSearchExtension : QueryExecutor<PsiReference, MethodReferencesSearch.SearchParameters> {
    override fun execute(
        queryParameters: MethodReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ): Boolean {
        runReadActionInSmartModeWithWritePriority(queryParameters.project, { queryParameters.isQueryValid }) scope@{
            val method = queryParameters.method
            val methodDesc = method.descriptor ?: return@scope
            val declaringClass = method.containingClass ?: return@scope
            val internalName = declaringClass.internalName ?: return@scope
            val allowedOwners = mutableSetOf(internalName)
            val allowedDescs = mutableSetOf(methodDesc)
            val subMethodsHide = method.hasModifierProperty(PsiModifier.STATIC)
            if (!method.isConstructor && !method.hasModifierProperty(PsiModifier.PRIVATE) && !(subMethodsHide && declaringClass.isInterface)) {
                val classesWithHiddenMethods = mutableSetOf<String>()
                derivedClassLoop@
                for (derived in ClassInheritorsSearch.search(declaringClass)) {
                    val derivedInternalName = derived.internalName ?: continue
                    if (subMethodsHide) {
                        var superType = derived.superClass
                        while (superType != null) {
                            val superInternalName = superType.internalName
                            if (superInternalName != null) {
                                if (classesWithHiddenMethods.contains(superInternalName)) {
                                    classesWithHiddenMethods += derivedInternalName
                                    continue@derivedClassLoop
                                }
                                if (superInternalName == internalName) {
                                    break
                                }
                            }
                            superType = superType.superClass
                        }
                    }
                    allowedOwners += derivedInternalName
                    for (pair in derived.findMethodsAndTheirSubstitutorsByName(method.name, false)) {
                        val parentSubstitutor = TypeConversionUtil.getSuperClassSubstitutor(declaringClass, derived, PsiSubstitutor.EMPTY)
                        val parentSignature = method.getSignature(parentSubstitutor)
                        val derivedMethod = pair.first
                        val derivedSignature = derivedMethod.getSignature(pair.second)
                        if (MethodSignatureUtil.isSubsignature(parentSignature, derivedSignature)) {
                            if (subMethodsHide) {
                                classesWithHiddenMethods += derivedInternalName
                            } else {
                                val derivedDesc = derivedMethod.descriptor
                                if (derivedDesc != null) {
                                    allowedDescs += derivedDesc
                                }
                            }
                        }
                    }
                }
            }
            val methodBinaryName = if (method.isConstructor) {
                "<init>"
            } else {
                method.name
            }
            val files = ClassFileIndex.search(methodBinaryName, { key ->
                key is MethodIndexKey
                        && allowedOwners.contains(key.owner)
                        && (!queryParameters.isStrictSignatureSearch || allowedDescs.contains(key.desc))
            }, queryParameters.effectiveSearchScope)
            if (files.isEmpty()) {
                return@scope
            }
            val methodPtr = SmartPointerManager.createPointer(method)
            var id = 0
            for ((file, occurrences) in files) {
                val psiFile = findCompiledFileWithoutSources(queryParameters.project, file) ?: continue
                for ((location, count) in occurrences) {
                    repeat(count) { i ->
                        consumer.process(
                            MethodRefElement(
                                id++,
                                psiFile,
                                MethodLocator(methodPtr, queryParameters.isStrictSignatureSearch, file.nameWithoutExtension, location, i)
                            ).createReference(method)
                        )
                    }
                }
            }
        }
        return true
    }

    class MethodRefElement(
        id: Int,
        file: PsiCompiledFile,
        locator: DecompiledSourceElementLocator<PsiElement>
    ) : FakeDecompiledElement<PsiElement>(id, file, file, locator)
}
