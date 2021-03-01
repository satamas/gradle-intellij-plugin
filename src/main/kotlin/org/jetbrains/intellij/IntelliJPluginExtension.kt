package org.jetbrains.intellij

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.tooling.BuildException
import org.jetbrains.intellij.dependency.IdeaDependency
import org.jetbrains.intellij.dependency.PluginDependency
import org.jetbrains.intellij.dependency.PluginsRepoConfigurationImpl
import org.jetbrains.intellij.dependency.PluginsRepository
import java.io.File

/**
 * Configuration options for the {@link org.jetbrains.intellij.IntelliJPlugin}.
 */
@Suppress("UnstableApiUsage")
open class IntelliJPluginExtension(objects: ObjectFactory, projectName: String, projectBuildDir: File, private val project: Project) {

    // TODO: Use IntelliJPlugin.DEFAULT_SANDBOX instead
    val DEFAULT_SANDBOX = "idea-sandbox"

    // TODO: Use IntelliJPlugin.DEFAULT_INTELLIJ_REPO instead
    val DEFAULT_INTELLIJ_REPO = "https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository"

    // TODO: Use IntelliJPlugin.DEFAULT_INTELLIJ_PLUGINS_REPO instead
    val DEFAULT_INTELLIJ_PLUGINS_REPO = "https://cache-redirector.jetbrains.com/plugins.jetbrains.com/maven"

    // TODO: Use IntelliJPlugin.IDEA_CONFIGURATION_NAME instead
    val IDEA_CONFIGURATION_NAME = "idea"

    // TODO: Use IntelliJPlugin.IDEA_PLUGINS_CONFIGURATION_NAME instead
    val IDEA_PLUGINS_CONFIGURATION_NAME = "ideaPlugins"

//    @Optional
//    @Internal
    private val pluginsProperty = objects.listProperty(Any::class.java).apply {
        set(emptyList())
    }
    /**
     * The list of bundled IDE plugins and plugins from the <a href="https://plugins.jetbrains.com/">JetBrains Plugin Repository</a>.
     */
    var plugins: List<Any>
        get() = pluginsProperty.getOrElse(emptyList())
        set(value) = pluginsProperty.set(value)

    private val localPathProperty = objects.property(String::class.java)
    /**
     * The path to locally installed IDE distribution that should be used as a dependency.
     */
    var localPath: String?
        get() = localPathProperty.orNull
        set(value) = localPathProperty.set(value)

    private val localSourcesPathProperty = objects.property(String::class.java)
    /**
     * The path to local archive with IDE sources.
     */
    var localSourcesPath: String?
        get() = localSourcesPathProperty.orNull
        set(value) = localSourcesPathProperty.set(value)

    private val versionProperty = objects.property(String::class.java)
    /**
     * The version of the IntelliJ Platform IDE that will be used to build the plugin.
     * <p/>
     * Please see <a href="https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html">Plugin Compatibility</a> in SDK docs for more details.
     */
    var version: String?
        get() {
            val v = versionProperty.orNull ?: return null
            if (v.startsWith("JPS-")) {
                return v.substring(4)
            }
            if (v.startsWith("IU-") || v.startsWith("IC-") ||
                v.startsWith("RD-") || v.startsWith("CL-")
                || v.startsWith("PY-") || v.startsWith("PC-") || v.startsWith("GO-")) {
                return v.substring(3)
            }
            return v
        }
        set(value) = versionProperty.set(value)

    private val typeProperty = objects.property(String::class.java).apply {
        set("IC")
    }
    /**
     * The type of IDE distribution (IC, IU, CL, PY, PC, RD or JPS).
     * <p/>
     * The type might be included as a prefix in {@link #version} value.
     */
    var type: String
        get() {
            val t = typeProperty.get()
            val v = versionProperty.orNull ?: return "IC"

            if (v.startsWith("IU-") || "IU" == t) {
                return "IU"
            } else if (v.startsWith("JPS-") || "JPS" == t) {
                return "JPS"
            } else if (v.startsWith("CL-") || "CL" == t) {
                return "CL"
            } else if (v.startsWith("PY-") || "PY" == t) {
                return "PY"
            } else if (v.startsWith("PC-") || "PC" == t) {
                return "PC"
            } else if (v.startsWith("RD-") || "RD" == t) {
                return "RD"
            } else if (v.startsWith("GO-") || "GO" == t) {
                return "GO"
            } else {
                return "IC"
            }
        }
        set(value) = typeProperty.set(value)

    private val pluginNameProperty = objects.property(String::class.java).apply {
        set(projectName)
    }
    /**
     * The name of the target zip-archive and defines the name of plugin artifact.
     * By default: <code>${project.name}</code>
     */
    var pluginName: String
        get() = pluginNameProperty.get()
        set(value) = pluginNameProperty.set(value)

    private val updateSinceUntilBuildProperty = objects.property(Boolean::class.java).apply {
        set(true)
    }
    /**
     * Patch plugin.xml with since and until build values inferred from IDE version.
     */
    var updateSinceUntilBuild: Boolean
        get() = updateSinceUntilBuildProperty.get()
        set(value) = updateSinceUntilBuildProperty.set(value)

    private val sameSinceUntilBuildProperty = objects.property(Boolean::class.java).apply {
        set(false)
    }
    /**
     * Patch plugin.xml with an until build value that is just an "open" since build.
     */
    var sameSinceUntilBuild: Boolean
        get() = sameSinceUntilBuildProperty.get()
        set(value) = sameSinceUntilBuildProperty.set(value)

    private val instrumentCodeProperty = objects.property(Boolean::class.java).apply {
        set(true)
    }
    /**
     * Instrument Java classes with nullability assertions and compile forms created by IntelliJ GUI Designer.
     */
    var instrumentCode: Boolean
        get() = instrumentCodeProperty.get()
        set(value) = instrumentCodeProperty.set(value)

    private val alternativeIdePathProperty = objects.property(String::class.java)
    /**
     * The absolute path to the locally installed JetBrains IDE, which is used for running.
     * <p/>
     * @deprecated use `ideDirectory` option in `runIde` and `buildSearchableOptions` task instead.
     */
    @Deprecated("Use `ideDirectory` option in `runIde` and `buildSearchableOptions` task instead.")
    var alternativeIdePath: String?
        get() = alternativeIdePathProperty.orNull
        set(value) = alternativeIdePathProperty.set(value)

    private val sandboxDirectoryProperty = objects.property(String::class.java).apply {
        set(File(projectBuildDir, DEFAULT_SANDBOX).absolutePath)
    }
    /**
     * The path of sandbox directory that is used for running IDE with developing plugin.
     * By default: <code>${project.buildDir}/idea-sandbox</code>.
     */
    var sandboxDirectory: String
        get() = sandboxDirectoryProperty.get()
        set(value) = sandboxDirectoryProperty.set(value)

    private val intellijRepoProperty = objects.property(String::class.java).apply {
        set(DEFAULT_INTELLIJ_REPO)
    }
    /**
     * Url of repository for downloading IDE distributions.
     */
    var intellijRepo: String
        get() = intellijRepoProperty.get()
        set(value) = intellijRepoProperty.set(value)

    private val pluginsRepoProperty = objects.property(String::class.java).apply {
        set(DEFAULT_INTELLIJ_PLUGINS_REPO)
    }
    /**
     * Url of repository for downloading plugin dependencies.
     *
     * @deprecated Use closure syntax to configure multiple repositories
     */
    @Deprecated("Use closure syntax to configure multiple repositories.")
    var pluginsRepo: String
        get() = pluginsRepoProperty.get()
        set(value) = pluginsRepoProperty.set(value)

    /**
     * Returns object to configure multiple repositories for downloading plugins.
     */
    fun pluginsRepo(): PluginsRepoConfiguration {
        if (pluginsRepoConfiguration == null) {
            pluginsRepoConfiguration = PluginsRepoConfigurationImpl(project)
        }
        return pluginsRepoConfiguration!!
    }

    /**
     * Configure multiple repositories for downloading plugins.
     */
    fun pluginsRepo( block: Closure<Any>) {
        project.configure(pluginsRepo(), block)
    }

    /**
     * Configure multiple repositories for downloading plugins.
     */
    fun pluginsRepo(block: Action<PluginsRepoConfiguration>) {
        block.execute(pluginsRepo())
    }

    private val jreRepoProperty = objects.property(String::class.java)
    /**
     * Url of repository for downloading JetBrains Java Runtime.
     */
    var jreRepo: String?
        get() = jreRepoProperty.orNull
        set(value) = jreRepoProperty.set(value)

    private val ideaDependencyCachePathProperty = objects.property(String::class.java)
    /**
     * The absolute path to the local directory that should be used for storing IDE distributions.
     */
    var ideaDependencyCachePath: String?
        get() = ideaDependencyCachePathProperty.orNull
        set(value) = ideaDependencyCachePathProperty.set(value)

    private val downloadSourcesProperty = objects.property(Boolean::class.java).apply {
        set(!System.getenv().containsKey("CI"))
    }
    /**
     * Download IntelliJ sources while configuring Gradle project.
     */
    var downloadSources: Boolean
        get() = downloadSourcesProperty.get()
        set(value) = downloadSourcesProperty.set(value)

    private val configureDefaultDependenciesProperty = objects.property(Boolean::class.java).apply {
        set(!System.getenv().containsKey("CI"))
    }
    /**
     * Turning it off disables configuring dependencies to intellij sdk jars automatically,
     * instead the intellij, intellijPlugin and intellijPlugins functions could be used for an explicit configuration
     */
    var configureDefaultDependencies: Boolean
        get() = configureDefaultDependenciesProperty.get()
        set(value) = configureDefaultDependenciesProperty.set(value)

    private val extraDependenciesProperty = objects.listProperty(String::class.java).apply {
        set(emptyList())
    }
    /**
     * configure extra dependency artifacts from intellij repo
     *  the dependencies on them could be configured only explicitly using intellijExtra function in the dependencies block
     */
    var extraDependencies: List<String>
        get() = extraDependenciesProperty.getOrElse(emptyList())
        set(value) = extraDependenciesProperty.set(value)


    var ideaDependency: IdeaDependency? = null
        get() {
            if (field == null) {
                debug(project, "IDE dependency is resolved", Throwable())
                project.configurations.getByName(IDEA_CONFIGURATION_NAME).resolve()
                if (field == null) {
                    throw BuildException("Cannot resolve ideaDependency", null)
                }
            }
            return field
        }

    private var pluginDependenciesConfigured = false
    private var pluginsRepoConfiguration: PluginsRepoConfigurationImpl? = null
    private val pluginDependencies = mutableSetOf<PluginDependency>()

//    fun getBuildVersion(): String = IdeVersion.createIdeVersion(ideaDependency.buildNumber).asStringWithoutProductCode()

    fun addPluginDependency(pluginDependency: PluginDependency) {
        pluginDependencies.add(pluginDependency)
    }

    fun getUnresolvedPluginDependencies(): Set<PluginDependency> {
        if (pluginDependenciesConfigured) {
            return emptySet()
        }
        return pluginDependencies
    }

    fun getPluginDependencies(): Set<PluginDependency> {
        if (!pluginDependenciesConfigured) {
            debug(project, "Plugin dependencies are resolved", Throwable())
            project.configurations.getByName(IDEA_PLUGINS_CONFIGURATION_NAME).resolve()
            pluginDependenciesConfigured = true
        }
        return pluginDependencies
    }


    fun getPluginsRepos(): List<PluginsRepository> {
        if (pluginsRepoConfiguration == null) {
            //noinspection GrDeprecatedAPIUsage
            pluginsRepo().maven(this.pluginsRepo)
        }
        return pluginsRepoConfiguration!!.getRepositories()
    }

    interface PluginsRepoConfiguration {

        /**
         * Use default marketplace repository
         */
        fun marketplace()

        /**
         * Use a Maven repository with plugin artifacts
         */
        fun maven(url: String)

        /**
         * Use custom plugin repository. The URL should point to the `plugins.xml` or `updatePlugins.xml` file.
         */
        fun custom(url: String)

        fun getRepositories(): List<PluginsRepository>
    }
}
