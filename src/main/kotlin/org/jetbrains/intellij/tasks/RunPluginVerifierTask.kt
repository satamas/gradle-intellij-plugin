package org.jetbrains.intellij.tasks

import de.undercouch.gradle.tasks.download.DownloadAction
import de.undercouch.gradle.tasks.download.org.apache.http.client.utils.URIBuilder
import org.apache.commons.io.FileUtils
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.ConventionTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskExecutionException
import org.gradle.internal.jvm.Jvm
import org.gradle.internal.os.OperatingSystem
import org.gradle.util.VersionNumber
import org.jetbrains.intellij.IntelliJPluginConstants
import org.jetbrains.intellij.IntelliJPluginExtension
import org.jetbrains.intellij.debug
import org.jetbrains.intellij.error
import org.jetbrains.intellij.getBuiltinJbrVersion
import org.jetbrains.intellij.info
import org.jetbrains.intellij.jbr.JbrResolver
import org.jetbrains.intellij.model.PluginVerifierRepository
import org.jetbrains.intellij.parseXml
import org.jetbrains.intellij.untar
import org.jetbrains.intellij.warn
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.EnumSet
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class RunPluginVerifierTask @Inject constructor(
    objectFactory: ObjectFactory,
) : ConventionTask() {

    companion object {
        private const val VERIFIER_METADATA_URL =
            "https://cache-redirector.jetbrains.com/packages.jetbrains.team/maven/p/intellij-plugin-verifier/intellij-plugin-verifier/org/jetbrains/intellij/plugins/verifier-cli/maven-metadata.xml"
        private const val IDE_DOWNLOAD_URL = "https://data.services.jetbrains.com/products/download"
        private const val CACHE_REDIRECTOR = "https://cache-redirector.jetbrains.com"
        const val VERIFIER_VERSION_LATEST = "latest"

        fun resolveLatestVerifierVersion(): String {
            debug(this, "Resolving Latest Verifier version")
            val url = URL(VERIFIER_METADATA_URL)
            return parseXml(url.openStream(), PluginVerifierRepository::class.java).versioning?.latest
                ?: throw GradleException("Cannot resolve the latest Plugin Verifier version")
        }
    }

    /**
     * List of the {@link FailureLevel} values used for failing the task if any reported issue will match.
     */
    @Input
    val failureLevel: ListProperty<FailureLevel> = objectFactory.listProperty(FailureLevel::class.java)

    /**
     * List of the specified IDE versions used for the verification.
     * By default, it uses the plugin target IDE version.
     */
    @Input
    val ideVersions: ListProperty<String> = objectFactory.listProperty(String::class.java)

    /**
     * List of the paths to locally installed IDE distributions that should be used for verification
     * in addition to those specified in {@link #ideVersions}.
     */
    @InputFiles
    val localPaths: ConfigurableFileCollection = objectFactory.fileCollection()

    /**
     * Returns the version of the IntelliJ Plugin Verifier that will be used.
     * By default, set to "latest".
     */
    @Input
    @Optional
    val verifierVersion: Property<String> = objectFactory.property(String::class.java)

    /**
     * Local path to the IntelliJ Plugin Verifier that will be used.
     * If provided, {@link #verifierVersion} is ignored.
     */
    @Input
    @Optional
    val verifierPath: Property<String> = objectFactory.property(String::class.java)

    /**
     * An instance of the distribution file generated with the build task.
     * If empty, task will be skipped.
     */
    @InputFile
    @SkipWhenEmpty
    val distributionFile: RegularFileProperty = objectFactory.fileProperty()

    /**
     * The path to directory where verification reports will be saved.
     * By default, set to ${project.buildDir}/reports/pluginVerifier.
     */
    @OutputDirectory
    @Optional
    val verificationReportsDir: Property<String> = objectFactory.property(String::class.java)

    /**
     * The path to directory where IDEs used for the verification will be downloaded.
     * By default, set to ${project.buildDir}/pluginVerifier.
     */
    @Input
    @Optional
    val downloadDir: Property<String> = objectFactory.property(String::class.java)

    /**
     * JBR version used by the IntelliJ Plugin Verifier, i.e. "11_0_2b159".
     * All JetBrains Java versions are available at JetBrains Space Packages: https://cache-redirector.jetbrains.com/intellij-jbr
     */
    @Input
    @Optional
    val jbrVersion: Property<String> = objectFactory.property(String::class.java)

    /**
     * The path to directory containing JVM runtime, overrides {@link #jbrVersion}.
     * TODO: fileProperty?
     */
    @Input
    @Optional
    val runtimeDir: Property<String> = objectFactory.property(String::class.java)

    /**
     * The list of classes prefixes from the external libraries.
     * The Plugin Verifier will not report 'No such class' for classes of these packages.
     */
    @Input
    @Optional
    val externalPrefixes: ListProperty<String> = objectFactory.listProperty(String::class.java)

    /**
     * A flag that controls the output format - if set to <code>true</code>, the TeamCity compatible output
     * will be returned to stdout.
     */
    @Input
    @Optional
    val teamCityOutputFormat: Property<Boolean> = objectFactory.property(Boolean::class.java)

    /**
     * Specifies which subsystems of IDE should be checked.
     * Available options: `all` (default), `android-only`, `without-android`.
     */
    @Input
    @Optional
    val subsystemsToCheck: Property<String> = objectFactory.property(String::class.java)

    /**
     * Runs the IntelliJ Plugin Verifier against the plugin artifact.
     * {@link String}
     */
    @TaskAction
    fun runPluginVerifier() {
        val file = distributionFile.orNull
        if (file == null || !file.asFile.exists()) {
            throw IllegalStateException("Plugin file does not exist: $file")
        }

        val ides = ideVersions.get().toSet().map { resolveIdePath(it) }
        if (ides.isEmpty() && localPaths.isEmpty) {
            throw GradleException("`ideVersions` and `localPaths` properties should not be empty")
        }

        val verifierPath = resolveVerifierPath()
        val verifierArgs = mutableListOf("check-plugin")
        verifierArgs += getOptions()
        verifierArgs += file.asFile.canonicalPath
        verifierArgs += ides
        verifierArgs += localPaths.toList().map { it.canonicalPath }

        debug(this, "Distribution file: $file.canonicalPath")
        debug(this, "Verifier path: $verifierPath")

        ByteArrayOutputStream().use { os ->
            project.javaexec {
                it.classpath = project.files(verifierPath)
                it.main = "com.jetbrains.pluginverifier.PluginVerifierMain"
                it.args = verifierArgs
                it.standardOutput = os
            }

            val output = os.toString()
            println(output) // Print output back to stdout since it's been caught for the failure level checker.

            debug(this, "Current failure levels: ${FailureLevel.values().joinToString(", ")}")
            FailureLevel.values().forEach { level ->
                if (failureLevel.get().contains(level) && output.contains(level.testValue)) {
                    debug(this, "Failing task on $failureLevel failure level")
                    throw GradleException(level.toString())
                }
            }
        }
    }

    /**
     * Resolves path to the IntelliJ Plugin Verifier file.
     * At first, checks if it was provided with {@link #verifierPath}.
     * Fetches IntelliJ Plugin Verifier artifact from the {@link IntelliJPluginConstants.DEFAULT_INTELLIJ_PLUGIN_VERIFIER_REPOSITORY}
     * repository and resolves the path to verifier-cli jar file.
     *
     * @return path to verifier-cli jar
     */
    private fun resolveVerifierPath(): String {
        val path = verifierPath.orNull
        if (path != null && path.isNotEmpty()) {
            val verifier = File(path)
            if (verifier.exists()) {
                return path
            }
            warn(this, "Provided Plugin Verifier path doesn't exist: '$path'. Downloading Plugin Verifier: $verifierVersion")
        }

        if (isOffline()) {
            throw TaskExecutionException(this, GradleException(
                "Cannot resolve Plugin Verifier in offline mode. " +
                    "Provide pre-downloaded Plugin Verifier jar file with `verifierPath` property. "
            ))
        }

        val resolvedVerifierVersion = resolveVerifierVersion()
        val repository = project.repositories.maven { it.url = URI(getPluginVerifierRepository(resolvedVerifierVersion)) }
        try {
            debug(this, "Using Verifier in $resolvedVerifierVersion version")
            val dependency = project.dependencies.create("org.jetbrains.intellij.plugins:verifier-cli:$resolvedVerifierVersion:all@jar")
            val configuration = project.configurations.detachedConfiguration(dependency)
            return configuration.singleFile.absolutePath
        } catch (e: Exception) {
            error(this, "Error when resolving Plugin Verifier path", e)
            throw e
        } finally {
            project.repositories.remove(repository)
        }
    }

    /**
     * Resolves the IDE type and version. If just version is provided, type is set to "IC".
     *
     * @param ideVersion IDE version. Can be "2020.2", "IC-2020.2", "202.1234.56"
     * @return path to the resolved IDE
     */
    private fun resolveIdePath(ideVersion: String): String {
        debug(this, "Resolving IDE path for $ideVersion")
        var (type, version) = ideVersion.trim().split('-', limit = 2) + null

        if (version == null) {
            debug(this, "IDE type not specified, setting type to IC")
            version = type
            type = "IC"
        }

        listOf("release", "rc", "eap", "beta").forEach { buildType ->
            debug(project, "Downloading IDE '$type-$version' from $buildType channel to ${downloadDir.get()}")
            try {
                val dir = downloadIde(type!!, version!!, buildType)
                debug(project, "Resolved IDE '$type-$version' path: ${dir.absolutePath}")
                return dir.absolutePath
            } catch (e: IOException) {
                debug(project, "Cannot download IDE '$type-$version' from $buildType channel. Trying another channel...", e)
            }
        }

        throw TaskExecutionException(this, GradleException(
            "IDE '$ideVersion' cannot be downloaded. " +
                "Please verify the specified IDE version against the products available for testing: " +
                "https://jb.gg/intellij-platform-builds-list"
        ))
    }

    /**
     * Downloads IDE from the {@link #IDE_DOWNLOAD_URL} service by the given parameters.
     *
     * @param type IDE type, i.e. IC, PS
     * @param version IDE version, i.e. 2020.2 or 203.1234.56
     * @param buildType release, rc, eap, beta
     * @return {@link File} instance pointing to the IDE directory
     */
    private fun downloadIde(type: String, version: String, buildType: String): File {
        val name = "$type-$version"
        val ideDir = File(downloadDir.get(), name)
        info(this, "Downloading IDE: $name")

        when {
            ideDir.exists() -> debug(this, "IDE already available in $ideDir")
            isOffline() -> throw TaskExecutionException(this, GradleException(
                "Cannot download IDE: $name. Gradle runs in offline mode. " +
                    "Provide pre-downloaded IDEs stored in `downloadDir` or use `localPaths` instead."
            ))
            else -> {
                val ideArchive = File(downloadDir.get(), "${name}.tar.gz")
                val url = resolveIdeUrl(type, version, buildType)

                debug(this, "Downloading IDE from $url")

                DownloadAction(project).apply {
                    src(url)
                    dest(ideArchive.absolutePath)
                    tempAndMove(true)
                    execute()
                }

                try {
                    debug(this, "IDE downloaded, extracting...")
                    untar(project, ideArchive, ideDir)
                    ideDir.listFiles()?.first()?.let { container ->
                        container.listFiles()?.forEach {
                            it.renameTo(File(ideDir, it.name))
                        }
                        container.deleteRecursively()
                    }
                } finally {
                    ideArchive.delete()
                }
                debug(this, "IDE extracted to $ideDir, archive removed")
            }
        }

        return ideDir
    }

    /**
     * Resolves direct IDE download URL provided by the JetBrains Data Services.
     * The URL created with {@link #IDE_DOWNLOAD_URL} contains HTTP redirection, which is supposed to be resolved.
     * Direct download URL is prepended with {@link #CACHE_REDIRECTOR} host for providing caching mechanism.
     *
     * @param type IDE type, i.e. IC, PS
     * @param version IDE version, i.e. 2020.2 or 203.1234.56
     * @param buildType release, rc, eap, beta
     * @return direct download URL prepended with {@link #CACHE_REDIRECTOR} host
     */
    private fun resolveIdeUrl(type: String, version: String, buildType: String): String {
        val url = URIBuilder(IDE_DOWNLOAD_URL)
            .addParameter("code", type)
            .addParameter("platform", "linux")
            .addParameter("type", buildType)
            .addParameter(versionParameterName(version), version)
            .toString()
        debug(this, "Resolving direct IDE download URL for: $url")

        var connection: HttpURLConnection? = null

        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.inputStream

            if (connection.responseCode == HttpURLConnection.HTTP_MOVED_PERM || connection.responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                val redirectUrl = URL(connection.getHeaderField("Location"))
                connection.disconnect()
                debug(this, "Resolved IDE download URL: $url")
                return "$CACHE_REDIRECTOR/${redirectUrl.host}${redirectUrl.file}"
            } else {
                debug(this, "IDE download URL has no redirection provided, skipping.")
            }
        } catch (e: Exception) {
            info(this, "Cannot resolve direct download URL for: $url")
            debug(this, "Download exception stacktrace:", e)
        } finally {
            connection?.disconnect()
        }

        return url
    }

    /**
     * Resolves Plugin Verifier version.
     * If set to {@link #VERIFIER_VERSION_LATEST}, there's request to {@link #VERIFIER_METADATA_URL}
     * performed for the latest available verifier version.
     *
     * @return Plugin Verifier version
     */
    private fun resolveVerifierVersion() =
        verifierVersion.orNull?.takeIf { it != VERIFIER_VERSION_LATEST } ?: resolveLatestVerifierVersion()

    /**
     * Resolves the Java Runtime directory. `runtimeDir` property is used if provided with the task configuration.
     * Otherwise, `jbrVersion` is used for resolving the JBR. If it's not set, or it's impossible to resolve valid
     * version, built-in JBR will be used.
     * As a last fallback, current JVM will be used.
     *
     * @return path to the Java Runtime directory
     */
    private fun resolveRuntimeDir(): String {
        runtimeDir.orNull?.let {
            debug(this, "Runtime specified with properties: $it")
            return it
        }

        val extension = project.extensions.findByType(IntelliJPluginExtension::class.java)
            ?: throw GradleException("Cannot access IntelliJPluginExtension")

        val jbrResolver = JbrResolver(project, this, extension.jreRepository.orNull)
        jbrVersion.orNull?.let {
            jbrResolver.resolve(jbrVersion.orNull)?.let { jbr ->
                debug(this, "Runtime specified with JBR Version property: $it")
                return jbr.javaHome.canonicalPath
            }
            warn(this, "Cannot resolve JBR $it. Falling back to built-in JBR.")
        }

        val jbrPath = when (OperatingSystem.current().isMacOsX) {
            true -> "jbr/Contents/Home"
            false -> "jbr"
        }

        val runIdeTask = project.tasks.findByName(IntelliJPluginConstants.RUN_IDE_TASK_NAME) as RunIdeTask
        getBuiltinJbrVersion(runIdeTask.ideDir.get().asFile)?.let { builtinJbrVersion ->
            jbrResolver.resolve(builtinJbrVersion)?.let { builtinJbr ->
                val javaHome = File(builtinJbr.javaHome, jbrPath)
                if (javaHome.exists()) {
                    debug(this, "Using built-in JBR: $javaHome")
                    return javaHome.canonicalPath
                }
            }
            warn(this, "Cannot resolve builtin JBR $builtinJbrVersion. Falling back to local Java.")
        }

        debug(this, "Using current JVM: ${Jvm.current().javaHome}")
        return Jvm.current().javaHome.canonicalPath
    }

    /**
     * Checks if Gradle is run with offline start parameter.
     *
     * @return Gradle runs in offline mode
     */
    private fun isOffline() = project.gradle.startParameter.isOffline

    /**
     * Collects all the options for the Plugin Verifier CLI provided with the task configuration.
     *
     * @return array with available CLI options
     */
    private fun getOptions(): List<String> {
        val args = mutableListOf(
            "-verification-reports-dir", verificationReportsDir.get(),
            "-runtime-dir", resolveRuntimeDir(),
        )

        externalPrefixes.get().takeIf { it.isNotEmpty() }?.let {
            args.add("-external-prefixes")
            args.add(it.joinToString(":"))
        }
        if (teamCityOutputFormat.get()) {
            args.add("-team-city")
        }
        if (subsystemsToCheck.orNull != null) {
            args.add("-subsystems-to-check")
            args.add(subsystemsToCheck.get())
        }
        if (isOffline()) {
            args.add("-offline")
        }

        return args
    }

    /**
     * Retrieve the Plugin Verifier home directory used for storing downloaded IDEs.
     * Following home directory resolving method is taken directly from the Plugin Verifier to keep the compatibility.
     *
     * @return Plugin Verifier home directory
     */
    private fun verifierHomeDir(): Path {
        System.getProperty("plugin.verifier.home.dir")?.let {
            return Paths.get(it)
        }

        System.getProperty("user.home")?.let {
            return Paths.get(it, ".pluginVerifier")
        }

        return FileUtils.getTempDirectory().toPath().resolve(".pluginVerifier")
    }

    /**
     * Provides target directory used for storing downloaded IDEs.
     * Path is compatible with the Plugin Verifier approach.
     *
     * @return directory for downloaded IDEs
     */
    fun ideDownloadDir(): Path = verifierHomeDir().resolve("ides").also {
        Files.createDirectories(it)
    }

    /**
     * Obtains version parameter name used for downloading IDE artifact.
     * Examples:
     * - 202.7660.26 -> build
     * - 2020.1, 2020.2.3 -> version
     *
     * @param version current version
     * @return version parameter name
     */
    private fun versionParameterName(version: String) = when {
        version.matches("\\d{3}(\\.\\d+)+".toRegex()) -> "build"
        else -> "version"
    }

    private fun getPluginVerifierRepository(version: String) = when {
        VersionNumber.parse(version) >= VersionNumber.parse("1.255") -> IntelliJPluginConstants.DEFAULT_INTELLIJ_PLUGIN_VERIFIER_REPOSITORY
        else -> IntelliJPluginConstants.OLD_INTELLIJ_PLUGIN_VERIFIER_REPOSITORY
    }

    enum class FailureLevel(val testValue: String) {
        COMPATIBILITY_WARNINGS("Compatibility warnings"),
        COMPATIBILITY_PROBLEMS("Compatibility problems"),
        DEPRECATED_API_USAGES("Deprecated API usages"),
        EXPERIMENTAL_API_USAGES("Experimental API usages"),
        INTERNAL_API_USAGES("Internal API usages"),
        OVERRIDE_ONLY_API_USAGES("Override-only API usages"),
        NON_EXTENDABLE_API_USAGES("Non-extendable API usages"),
        PLUGIN_STRUCTURE_WARNINGS("Plugin structure warnings"),
        MISSING_DEPENDENCIES("Missing dependencies"),
        INVALID_PLUGIN("The following files specified for the verification are not valid plugins"),
        NOT_DYNAMIC("Plugin cannot be loaded/unloaded without IDE restart");

        companion object {
            val ALL: EnumSet<FailureLevel> = EnumSet.allOf(FailureLevel::class.java)
            val NONE: EnumSet<FailureLevel> = EnumSet.noneOf(FailureLevel::class.java)
        }
    }
}
