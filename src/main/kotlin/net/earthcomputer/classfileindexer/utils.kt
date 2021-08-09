package net.earthcomputer.classfileindexer

import com.google.common.collect.MapMaker
import com.intellij.codeEditor.JavaEditorFileSwapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.SlowOperations
import net.earthcomputer.classindexfinder.libs.org.objectweb.asm.Type

private val internalNamesCache = MapMaker().weakKeys().makeMap<PsiClass, CachedValue<String?>>() // concurrent, uses identity for keys
private fun PsiClass.computeInternalName(): String? {
    val containingClass = containingClass ?: return qualifiedName?.replace('.', '/')
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

val PsiClass.descriptor: String?
    get() = internalName?.let { "L$it;" }

val PsiType.descriptor: String?
    get() {
        return when (this) {
            is PsiPrimitiveType -> kind.binaryName
            is PsiArrayType -> "[".repeat(arrayDimensions) + deepComponentType.descriptor
            is PsiClassType -> resolve()?.descriptor
                ?: ("L" + canonicalText.replace('.', '/') + ";") // fails inner classes, best we can do
            else -> null
        }
    }

val PsiField.descriptor: String?
    get() = type.descriptor

val PsiMethod.descriptor: String?
    get() {
        val descriptors = parameterList.parameters.map { it.type.descriptor }
        if (descriptors.contains(null)) return null
        val returnDesc = if (isConstructor) {
            "V"
        } else {
            returnType?.descriptor ?: return null
        }
        return "(" + descriptors.joinToString("") + ")" + returnDesc
    }

inline fun <reified T : PsiElement> PsiElement.getParentOfType() = PsiTreeUtil.getParentOfType(this, T::class.java)

fun Type.isPrimitive() = sort != Type.ARRAY && sort != Type.OBJECT && sort != Type.METHOD

fun isDescriptorOfType(desc: String, type: PsiType) = isDescriptorOfType(Type.getType(desc), type)

private val SLASHES_AND_DOLLARS = "[/$]".toRegex()
fun isDescriptorOfType(desc: Type, type: PsiType): Boolean {
    return when (type) {
        is PsiArrayType ->
            desc.sort == Type.ARRAY &&
                desc.dimensions == type.arrayDimensions &&
                isDescriptorOfType(desc.elementType, type.deepComponentType)
        is PsiPrimitiveType -> desc.isPrimitive() && type.kind.binaryName == desc.descriptor
        is PsiClassType -> {
            if (desc.sort != Type.OBJECT) {
                return false
            }
            val heuristic = desc.internalName.replace(SLASHES_AND_DOLLARS, ".").endsWith(type.className)
            if (!heuristic) {
                return false
            }
            val resolved = type.resolve()
            if (resolved != null) {
                return resolved.internalName == desc.internalName
            }
            // the best we can do
            heuristic
        }
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

fun findCompiledFileWithoutSources(project: Project, file: VirtualFile): PsiCompiledFile? {
    val className = file.nameWithoutExtension
    val actualFile = if (className.contains("\$")) {
        file.parent?.findChild("${className.substringBefore("\$")}.class") ?: file
    } else {
        file
    }
    if (JavaEditorFileSwapper.findSourceFile(project, actualFile) != null) return null
    return PsiManager.getInstance(project).findFile(actualFile) as? PsiCompiledFile
}

fun runReadActionInSmartModeWithWritePriority(project: Project, validityCheck: () -> Boolean, action: () -> Unit): Boolean {
    // avoid deadlocks, IndexNotReadyException may be thrown later
    val hasReadAccess = ApplicationManager.getApplication().isReadAccessAllowed ||
        ApplicationManager.getApplication().isDispatchThread

    val dumbService = DumbService.getInstance(project)
    val progressManager = ProgressManager.getInstance()

    var completed = false
    var canceledInvalid = false
    while (!completed) {
        if (!hasReadAccess) {
            dumbService.waitForSmartMode()
        }
        val canceledByWrite = !progressManager.runInReadActionWithWriteActionPriority(
            action@{
                if (!project.isOpen || !validityCheck()) {
                    canceledInvalid = true
                    return@action
                }
                if (!hasReadAccess && dumbService.isDumb) {
                    return@action
                }
                action()
                completed = true
            },
            null
        )
        if (canceledInvalid) {
            return false
        }
        if (canceledByWrite && hasReadAccess) {
            // can't wait forever on the dispatch thread, it would cause a deadlock
            ProgressManager.canceled(ProgressManager.getInstance().progressIndicator)
            ProgressManager.checkCanceled()
            break
        }
    }

    return true
}

private val SLOW_ALLOWED_FLAG = SlowOperations::class.java.getDeclaredField("ourAllowedFlag")
    .let { it.isAccessible = true; it }

fun withSlowOperationsIfNecessary(func: () -> Unit) {
    if (ApplicationManager.getApplication().isDispatchThread && !SLOW_ALLOWED_FLAG.getBoolean(null)) {
        SlowOperations.allowSlowOperations<RuntimeException>(func)
    } else {
        func()
    }
}
