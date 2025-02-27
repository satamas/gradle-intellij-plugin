package org.jetbrains.intellij.tasks

import org.gradle.api.Incubating
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.ConventionTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.tooling.BuildException
import org.jetbrains.intellij.IntelliJPluginConstants
import org.jetbrains.intellij.IntelliJPluginExtension
import org.jetbrains.intellij.dependency.IdeaDependency
import org.jetbrains.intellij.releaseType
import java.io.File
import java.net.URI
import javax.inject.Inject

@Incubating
@Suppress("UnstableApiUsage")
open class IntelliJInstrumentCodeTask @Inject constructor(
    objectFactory: ObjectFactory,
    private val fileSystemOperations: FileSystemOperations,
) : ConventionTask() {

    companion object {
        const val FILTER_ANNOTATION_REGEXP_CLASS = "com.intellij.ant.ClassFilterAnnotationRegexp"
        const val LOADER_REF = "java2.loader"
        const val ASM_REPOSITORY_URL = "https://cache-redirector.jetbrains.com/intellij-dependencies"
        const val FORMS_REPOSITORY_URL = "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2"
    }

    private val extension = project.extensions.findByType(IntelliJPluginExtension::class.java)

    @Internal
    val sourceSetOutputClassesDirs: ListProperty<File> = objectFactory.listProperty(File::class.java)

    @Internal
    val sourceSetAllDirs: ListProperty<File> = objectFactory.listProperty(File::class.java)

    @Internal
    val sourceSetResources: ListProperty<File> = objectFactory.listProperty(File::class.java)

    @Internal
    val sourceSetCompileClasspath: ListProperty<File> = objectFactory.listProperty(File::class.java)

    @Input
    @Optional
    val ideaDependency: Property<IdeaDependency> = objectFactory.property(IdeaDependency::class.java)

    @InputFile
    @Optional
    val javac2: RegularFileProperty = objectFactory.fileProperty()

    @Input
    val compilerVersion: Property<String> = objectFactory.property(String::class.java)

    @OutputDirectory
    val outputDir: DirectoryProperty = objectFactory.directoryProperty()

    @InputFiles
    fun getSourceDirs() = sourceSetAllDirs.get().filter {
        it.exists() && !sourceSetResources.get().contains(it)
    }

    @TaskAction
    fun instrumentClasses() {
        copyOriginalClasses(outputDir.get().asFile)

        val classpath = compilerClassPath()

        ant.invokeMethod("taskdef", mapOf(
            "name" to "instrumentIdeaExtensions",
            "classpath" to classpath.joinToString(":"),
            "loaderref" to LOADER_REF,
            "classname" to "com.intellij.ant.InstrumentIdeaExtensions",
        ))

        logger.info("Compiling forms and instrumenting code with nullability preconditions")
        val instrumentNotNull = prepareNotNullInstrumenting(classpath)
        instrumentCode(getSourceDirs(), outputDir.get().asFile, instrumentNotNull)
    }

    // local compiler
    private fun compilerClassPath() = javac2.orNull?.let {
        it.asFile.takeIf(File::exists)?.let { file ->
            File("${ideaDependency.get().classes}/lib").listFiles { _, name ->
                listOf(
                    "jdom.jar",
                    "asm-all.jar",
                    "asm-all-*.jar",
                    "jgoodies-forms.jar",
                    "forms-*.jar",
                ).any { pattern ->
                    val parts = pattern.split('*')
                    name.startsWith(parts.first()) && name.endsWith(parts.last())
                }
            }.orEmpty().filterNotNull() + file
        }
    } ?: compilerClassPathFromMaven()

    private fun compilerClassPathFromMaven(): List<File> {
        val dependency = project.dependencies.create("com.jetbrains.intellij.java:java-compiler-ant-tasks:${compilerVersion.get()}")
        val intellijRepositoryUrl = extension?.intellijRepository?.get() ?: IntelliJPluginConstants.DEFAULT_INTELLIJ_REPOSITORY
        val repos = listOf(
            project.repositories.maven { it.url = URI("$intellijRepositoryUrl/${releaseType(compilerVersion.get())}") },
            project.repositories.maven { it.url = URI(ASM_REPOSITORY_URL) },
            project.repositories.maven { it.url = URI(FORMS_REPOSITORY_URL) },
        )
        try {
            return project.configurations.detachedConfiguration(dependency).files.toList()
        } finally {
            project.repositories.removeAll(repos)
        }
    }

    private fun copyOriginalClasses(outputDir: File) {
        outputDir.deleteRecursively()
        outputDir.mkdir()
        fileSystemOperations.copy {
            it.from(sourceSetOutputClassesDirs.get())
            it.into(outputDir)
        }
    }

    private fun prepareNotNullInstrumenting(classpath: List<File>): Boolean {
        try {
            ant.invokeMethod("typedef", mapOf(
                "name" to "skip",
                "classpath" to classpath.joinToString(":"),
                "loaderref" to LOADER_REF,
                "classname" to FILTER_ANNOTATION_REGEXP_CLASS,
            ))
        } catch (e: BuildException) {
            val cause = e.cause
            if (cause is ClassNotFoundException && FILTER_ANNOTATION_REGEXP_CLASS == cause.message) {
                logger.info("Old version of Javac2 is used, " +
                    "instrumenting code with nullability will be skipped. Use IDEA >14 SDK (139.*) to fix this")
                return false
            } else {
                throw e
            }
        }
        return true
    }

    private fun instrumentCode(srcDirs: List<File>, outputDir: File, instrumentNotNull: Boolean) {
        val headlessOldValue = System.setProperty("java.awt.headless", "true")
        ant.invokeMethod("instrumentIdeaExtensions", mapOf(
            "srcdir" to srcDirs.joinToString(":"),
            "destdir" to outputDir,
            "classpath" to sourceSetCompileClasspath.get().joinToString(":"),
            "includeantruntime" to false,
            "instrumentNotNull" to instrumentNotNull,
        ))

        if (instrumentNotNull) {
            ant.invokeMethod("skip", mapOf(
                "pattern" to "kotlin/Metadata"
            ))
        }

        if (headlessOldValue != null) {
            System.setProperty("java.awt.headless", headlessOldValue)
        } else {
            System.clearProperty("java.awt.headless")
        }
    }
}
