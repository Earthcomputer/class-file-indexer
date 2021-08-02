package net.earthcomputer.classfileindexer

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.util.io.isFile
import net.bytebuddy.agent.ByteBuddyAgent
import java.io.File
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.jar.*

class MyAppLifecycleListener : AppLifecycleListener {
    companion object {
        const val AGENT_CLASS_NAME = "net.earthcomputer.classfileindexer.MyAgent"
    }

    override fun appStarting(projectFromCommandLine: Project?) {
        val jarFile = File.createTempFile("agent", ".jar")
        val jarPath = jarFile.toPath()

        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name("Agent-Class")] = AGENT_CLASS_NAME
        manifest.mainAttributes[Attributes.Name("Can-Retransform-Classes")] = "true"
        manifest.mainAttributes[Attributes.Name("Can-Redefine-Classes")] = "true"

        JarOutputStream(Files.newOutputStream(jarPath), manifest).use { jar ->
            fun copyAgentClass(agentClassName: String) {
                val entryName = agentClassName.replace('.', '/') + ".class"
                jar.putNextEntry(JarEntry(entryName))

                val (input, closeable) = findAgentClass(agentClassName) ?: throw AssertionError()
                if (closeable != null) {
                    closeable.use {
                        input.use {
                            input.copyTo(jar)
                        }
                    }
                } else {
                    input.use {
                        input.copyTo(jar)
                    }
                }

                jar.closeEntry()
            }

            fun writeEntry(name: String, inputStream: InputStream) {
                jar.putNextEntry(JarEntry(name))
                inputStream.copyTo(jar)
                jar.closeEntry()
            }

            copyAgentClass(AGENT_CLASS_NAME)
            copyAgentClass("$AGENT_CLASS_NAME\$1")
            copyAgentClass("$AGENT_CLASS_NAME\$HookClassVisitor")
            copyAgentClass("$AGENT_CLASS_NAME\$HookClassVisitor\$Target")
            copyAgentClass("$AGENT_CLASS_NAME\$HookClassVisitor\$HookInfo")
            copyAgentClass("$AGENT_CLASS_NAME\$HookClinitVisitor")
            copyAgentClass("$AGENT_CLASS_NAME\$HookMethodVisitor")
            copyAgentClass("$AGENT_CLASS_NAME\$UsageInfoGetNavigationOffsetVisitor")
            copyAgentClass("$AGENT_CLASS_NAME\$HasCustomDescriptionVisitor")
            copyAgentClass("$AGENT_CLASS_NAME\$InitChunksMethodVisitor")
            copyAgentClass("$AGENT_CLASS_NAME\$IsAccessedForWriteMethodVisitor")
            copyAgentClass("$AGENT_CLASS_NAME\$JavaReadWriteAccessDetectorMethodVisitor")

            copyAllAgentClasses("net.earthcomputer.classindexfinder.libs.org.objectweb.asm.", ::writeEntry)
        }

        val runtimeMxBeanName = ManagementFactory.getRuntimeMXBean().name
        val pid = runtimeMxBeanName.substringBefore('@')

        ByteBuddyAgent.attach(jarFile, pid)

        jarFile.deleteOnExit()
    }

    private fun findAgentClass(agentClassName: String): Pair<InputStream, AutoCloseable?>? {
        val pluginId = PluginId.findId("net.earthcomputer.classfileindexer") ?: return null
        val pluginPath = PluginManager.getInstance().findEnabledPlugin(pluginId)?.pluginPath ?: return null
        val entryName = agentClassName.replace('.', '/') + ".class"

        if (!Files.isDirectory(pluginPath)) {
            val pluginJar = JarFile(pluginPath.toFile())
            val stream = pluginJar.getInputStream(pluginJar.getJarEntry(entryName))
            return Pair(stream, pluginJar)
        }

        val relPath = agentClassName.replace(".", File.separator) + ".class"
        var path = pluginPath.resolve(relPath)
        if (Files.exists(path)) {
            return Pair(Files.newInputStream(path), null)
        }
        path = pluginPath.resolve("classes").resolve(relPath)
        if (Files.exists(path)) {
            return Pair(Files.newInputStream(path), null)
        }

        path = pluginPath.resolve("lib")
        if (Files.exists(path)) {
            for (file in path.toFile().listFiles() ?: return null) {
                if (!file.name.endsWith(".jar")) {
                    continue
                }
                val jarPath = file.toPath()
                val jar = JarFile(jarPath.toFile())
                val jarEntry = jar.getJarEntry(entryName)
                if (jarEntry == null) {
                    jar.close()
                } else {
                    return Pair(jar.getInputStream(jarEntry), jar)
                }
            }
        }

        return null
    }

    private fun copyAllAgentClasses(prefix: String, consumer: (String, InputStream) -> Unit) {
        val pluginId = PluginId.findId("net.earthcomputer.classfileindexer") ?: return
        val pluginPath = PluginManager.getInstance().findEnabledPlugin(pluginId)?.pluginPath ?: return
        val entryPrefix = prefix.replace('.', '/')

        if (!Files.isDirectory(pluginPath)) {
            JarFile(pluginPath.toFile()).use { pluginJar ->
                val entries = pluginJar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith(entryPrefix)) {
                        consumer(entry.name, pluginJar.getInputStream(entry))
                    }
                }
            }
        }

        val relPath = prefix.replace(".", File.separator)
        var path = pluginPath.resolve(relPath)
        if (Files.exists(path)) {
            Files.walk(path).filter { it.isFile() }.forEach { file ->
                consumer(pluginPath.relativize(file).toString().replace(File.separator, "/"), Files.newInputStream(file))
            }
        }
        val basePath = pluginPath.resolve("classes")
        path = basePath.resolve(relPath)
        if (Files.exists(path)) {
            Files.walk(path).filter { it.isFile() }.forEach { file ->
                consumer(basePath.relativize(file).toString().replace(File.separator, "/"), Files.newInputStream(file))
            }
        }

        path = pluginPath.resolve("lib")
        if (Files.exists(path)) {
            for (file in path.toFile().listFiles() ?: return) {
                if (!file.name.endsWith(".jar")) {
                    continue
                }
                val jarPath = file.toPath()
                JarFile(jarPath.toFile()).use { jar ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.startsWith(entryPrefix)) {
                            consumer(entry.name, jar.getInputStream(entry))
                        }
                    }
                }
            }
        }
    }
}
