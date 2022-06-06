import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

buildscript {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }
    dependencies {
        classpath("org.ow2.asm:asm:9.3")
        classpath("org.ow2.asm:asm-commons:9.3")
    }
}

fun properties(key: String) = project.findProperty(key).toString()

plugins {
    // Java support
    id("java")
    // Kotlin support
    id("org.jetbrains.kotlin.jvm") version "1.6.21"
    // Gradle IntelliJ Plugin
    id("org.jetbrains.intellij") version "1.6.0"
    // Gradle Changelog Plugin
    id("org.jetbrains.changelog") version "1.3.1"
    // Gradle Qodana Plugin
    id("org.jetbrains.qodana") version "0.1.13"
    // ktlint linter - read more: https://github.com/JLLeitschuh/ktlint-gradle
    id("org.jlleitschuh.gradle.ktlint") version "10.3.0"
}

val artifactTypeAttribute = Attribute.of("artifactType", String::class.java)
val repackagedAttribute = Attribute.of("repackaged", Boolean::class.javaObjectType)

val repackage: Configuration by configurations.creating {
    attributes.attribute(repackagedAttribute, true)
}

group = properties("pluginGroup")
version = properties("pluginVersion")

// Configure project's dependencies
abstract class MyRepackager : TransformAction<TransformParameters.None> {
    @InputArtifact
    abstract fun getInputArtifact(): Provider<FileSystemLocation>
    override fun transform(outputs: TransformOutputs) {
        val input = getInputArtifact().get().asFile
        val output = outputs.file(
            input.name.let {
                if (it.endsWith(".jar"))
                    it.replaceRange(it.length - 4, it.length, "-repackaged.jar")
                else
                    "$it-repackaged"
            }
        )
        println("Repackaging ${input.absolutePath} to ${output.absolutePath}")
        ZipOutputStream(output.outputStream()).use { zipOut ->
            ZipFile(input).use { zipIn ->
                val entriesList = zipIn.entries().toList()
                val entriesSet = entriesList.mapTo(mutableSetOf()) { it.name }
                for (entry in entriesList) {
                    val newName = if (entry.name.contains("/") && !entry.name.startsWith("META-INF/")) {
                        "net/earthcomputer/classfileindexer/libs/" + entry.name
                    } else {
                        entry.name
                    }
                    zipOut.putNextEntry(ZipEntry(newName))
                    if (entry.name.endsWith(".class")) {
                        val writer = ClassWriter(0)
                        ClassReader(zipIn.getInputStream(entry)).accept(
                            ClassRemapper(
                                writer,
                                object : Remapper() {
                                    override fun map(internalName: String?): String? {
                                        if (internalName == null) return null
                                        return if (entriesSet.contains("$internalName.class")) {
                                            "net/earthcomputer/classfileindexer/libs/$internalName"
                                        } else {
                                            internalName
                                        }
                                    }
                                }
                            ),
                            0
                        )
                        zipOut.write(
                            writer.toByteArray()
                        )
                    } else {
                        zipIn.getInputStream(entry).copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
            zipOut.flush()
        }
    }
}

repositories {
    mavenCentral()
}
dependencies {
    attributesSchema {
        attribute(repackagedAttribute)
    }
    artifactTypes.getByName("jar") {
        attributes.attribute(repackagedAttribute, false)
    }
    registerTransform(MyRepackager::class) {
        from.attribute(repackagedAttribute, false).attribute(artifactTypeAttribute, "jar")
        to.attribute(repackagedAttribute, true).attribute(artifactTypeAttribute, "jar")
    }

    repackage("org.ow2.asm:asm:9.3")
    implementation(files(repackage.files))
}

// Configure gradle-intellij-plugin plugin.
// Read more: https://github.com/JetBrains/gradle-intellij-plugin
intellij {
    pluginName.set(properties("pluginName"))
    version.set(properties("platformVersion"))
    type.set(properties("platformType"))
    downloadSources.set(properties("platformDownloadSources").toBoolean())
    updateSinceUntilBuild.set(true)

    // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file.
    plugins.set(properties("platformPlugins").split(',').map(String::trim).filter(String::isNotEmpty))
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    version.set(properties("pluginVersion"))
    groups.set(emptyList())
}

// Configure Gradle Qodana Plugin - read more: https://github.com/JetBrains/gradle-qodana-plugin
qodana {
    cachePath.set(projectDir.resolve(".qodana").canonicalPath)
    reportPath.set(projectDir.resolve("build/reports/inspections").canonicalPath)
    saveReport.set(true)
    showReport.set(System.getenv("QODANA_SHOW_REPORT")?.toBoolean() ?: false)
}

tasks {
    // Set the JVM compatibility versions
    properties("javaVersion").let {
        withType<JavaCompile> {
            options.encoding = "UTF-8"
            sourceCompatibility = it
            targetCompatibility = it
        }
        withType<KotlinCompile> {
            kotlinOptions.jvmTarget = it
        }
    }

    wrapper {
        gradleVersion = properties("gradleVersion")
    }

    patchPluginXml {
        version.set(properties("pluginVersion"))
        sinceBuild.set(properties("pluginSinceBuild"))
        untilBuild.set(properties("pluginUntilBuild"))

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription.set(
            projectDir.resolve("README.md").readText().lines().run {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end))
            }.joinToString("\n").run { markdownToHTML(this) }
        )

        // Get the latest available change notes from the changelog file
        changeNotes.set(
            provider {
                changelog.run {
                    getOrNull(properties("pluginVersion")) ?: getLatest()
                }.toHTML()
            }
        )
    }

    // Configure UI tests plugin
    // Read more: https://github.com/JetBrains/intellij-ui-test-robot
    runIdeForUiTests {
        systemProperty("robot-server.port", "8082")
        systemProperty("ide.mac.message.dialogs.as.sheets", "false")
        systemProperty("jb.privacy.policy.text", "<!--999.999-->")
        systemProperty("jb.consents.confirmation.enabled", "false")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        dependsOn("patchChangelog")
        token.set(System.getenv("PUBLISH_TOKEN"))
        // pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels.set(listOf(properties("pluginVersion").split('-').getOrElse(1) { "default" }.split('.').first()))
    }
}
