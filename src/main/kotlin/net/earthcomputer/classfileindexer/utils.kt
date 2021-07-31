package net.earthcomputer.classfileindexer

import com.google.common.collect.MapMaker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.*
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Type

private val internalNamesCache = MapMaker().weakKeys().concurrencyLevel(4).makeMap<PsiClass, CachedValue<String?>>()
private fun PsiClass.computeInternalName(): String? {
    qualifiedName?.let { return it.replace('.', '/') }
    val containingClass = containingClass ?: return null
    val containingInternalName = containingClass.internalName ?: return null
    val name = name
    if (name != null) {
        return "$containingInternalName\$$name"
    }
    var anonymousClassId = 1
    var found = false
    containingClass.accept(object : JavaElementVisitor() {
        override fun visitAnonymousClass(aClass: PsiAnonymousClass?) {
            if (!found) {
                if (aClass === this@computeInternalName) {
                    found = true
                } else {
                    anonymousClassId++
                }
            }
        }
    })
    if (!found) return null
    return "$containingInternalName\$$anonymousClassId"
}

val PsiClass.internalName: String?
    get() {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return internalNamesCache.computeIfAbsent(this) {
            CachedValuesManager.getManager(project).createCachedValue {
                CachedValueProvider.Result.create(computeInternalName(), PsiModificationTracker.MODIFICATION_COUNT)
            }
        }.value
    }

inline fun <reified T: PsiElement> PsiElement.getParentOfType() = PsiTreeUtil.getParentOfType(this, T::class.java)

fun Type.isPrimitive() = sort != Type.ARRAY && sort != Type.OBJECT && sort != Type.METHOD

fun isDescriptorOfType(desc: String, type: PsiType) = isDescriptorOfType(Type.getType(desc), type)

fun isDescriptorOfType(desc: Type, type: PsiType): Boolean {
    return when (type) {
        is PsiArrayType -> desc.sort == Type.ARRAY && desc.dimensions == type.arrayDimensions && isDescriptorOfType(desc.elementType, type.deepComponentType)
        is PsiPrimitiveType -> desc.isPrimitive() && type.kind.binaryName == desc.descriptor
        is PsiClassType -> desc.sort == Type.OBJECT && desc.internalName == type.resolve()?.internalName
        else -> false
    }
}

fun isDescriptorOfMethodType(desc: String, method: PsiMethod) = isDescriptorOfMethodType(Type.getType(desc), method)

fun isDescriptorOfMethodType(desc: Type, method: PsiMethod): Boolean {
    if (!method.isConstructor) {
        val returnType = method.returnType ?: return false
        if (!isDescriptorOfType(desc.returnType, returnType)) {
            return false
        }
    }
    val args = desc.argumentTypes
    val params = method.parameterList.parameters
    if (args.size != params.size) {
        return false
    }
    return args.asSequence().zip(params.asSequence()).all { (arg, param) -> isDescriptorOfType(arg, param.type) }
}

fun getTypeFromDescriptor(project: Project, scope: GlobalSearchScope, desc: String) = getTypeFromDescriptor(project, scope, Type.getType(desc))

fun getTypeFromDescriptor(project: Project, scope: GlobalSearchScope, desc: Type): PsiType? {
    if (desc.isPrimitive()) return PsiPrimitiveType.fromJvmTypeDescriptor(desc.descriptor[0])
    return when (desc.sort) {
        Type.ARRAY -> {
            var type = getTypeFromDescriptor(project, scope, desc.elementType) ?: return null
            repeat(desc.dimensions) {
                type = type.createArrayType()
            }
            type
        }
        Type.OBJECT -> {
            val elementFactory = JavaPsiFacade.getElementFactory(project)
            val innerClasses = desc.internalName.split("$")
            if (innerClasses.size == 1) {
                return elementFactory.createTypeByFQClassName(innerClasses[0].replace('/', '.'), scope)
            }
            var clazz = JavaPsiFacade.getInstance(project).findClass(innerClasses[0].replace('/', '.'), scope) ?: return null
            for (innerName in innerClasses.subList(1, innerClasses.size)) {
                clazz = clazz.findInnerClassByName(innerName, false) ?: return null
            }
            elementFactory.createType(clazz)
        }
        else -> null
    }
}

fun runReadActionInSmartModeWithWritePriority(project: Project, validityCheck: () -> Boolean, action: () -> Unit): Boolean {
    val dumbService = DumbService.getInstance(project)
    val progressManager = ProgressManager.getInstance()

    var completed = false
    var canceled = false
    while (!completed) {
        dumbService.waitForSmartMode()
        progressManager.runInReadActionWithWriteActionPriority(action@{
            if (!project.isOpen || !validityCheck()) {
                canceled = true
                return@action
            }
            if (dumbService.isDumb) {
                return@action
            }
            action()
            completed = true
        }, null)
        if (canceled) {
            return false
        }
    }

    return true
}
