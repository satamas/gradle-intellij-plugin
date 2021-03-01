package org.jetbrains.intellij.dependency

import org.gradle.api.Project
import org.jetbrains.intellij.IntelliJPluginExtension
import java.util.Collections

class PluginsRepoConfigurationImpl(val project: Project) : IntelliJPluginExtension.PluginsRepoConfiguration {

    // TODO: Use IntelliJPlugin.DEFAULT_INTELLIJ_PLUGINS_REPO instead
    val DEFAULT_INTELLIJ_PLUGINS_REPO = "https://cache-redirector.jetbrains.com/plugins.jetbrains.com/maven"

    private val pluginsRepositories = mutableListOf<PluginsRepository>()

    override fun marketplace() {
        pluginsRepositories.add(MavenPluginsRepository(project, DEFAULT_INTELLIJ_PLUGINS_REPO))
    }

    override fun maven(url: String) {
        pluginsRepositories.add(MavenPluginsRepository(project, url))
    }

    override fun custom(url: String) {
        pluginsRepositories.add(CustomPluginsRepository(project, url))
    }

    override fun getRepositories(): List<PluginsRepository> = Collections.unmodifiableList(pluginsRepositories)
}
