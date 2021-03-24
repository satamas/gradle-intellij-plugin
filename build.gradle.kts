import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    groovy
    kotlin("jvm") version "1.4.30"
    id("com.gradle.plugin-publish") version "0.13.0"
    id("synapticloop.documentr") version "3.1.0"
    `java-gradle-plugin`
    `maven-publish`
    id("com.github.breadmoirai.github-release") version "2.2.9"
    id("org.jetbrains.changelog") version "1.1.1"
}

plugins.withType<JavaPlugin> {
    tasks {
        withType<GroovyCompile> {
            sourceCompatibility = "1.7"
            targetCompatibility = "1.7"
            // TODO: remove after migration
            dependsOn(compileKotlin)
            classpath += files(compileKotlin.get().destinationDir)
        }
        withType<KotlinCompile> {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
}

repositories {
    maven("https://cache-redirector.jetbrains.com/jetbrains.bintray.com/intellij-plugin-service")
    maven("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2")
    maven("https://cache-redirector.jetbrains.com/jcenter.bintray.com")
}

dependencies {
    implementation(localGroovy())
    api(gradleApi())
    implementation("org.jetbrains:annotations:19.0.0")
    implementation("org.jetbrains:marketplace-zip-signer:0.1.3")
    implementation("org.jetbrains.intellij.plugins:structure-base:3.139")
    implementation("org.jetbrains.intellij.plugins:structure-intellij:3.139")
    // should be changed together with plugin-repository-rest-client
    implementation("org.jetbrains.intellij:blockmap:1.0.5") {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation("org.jetbrains.intellij:plugin-repository-rest-client:2.0.15") {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation("de.undercouch:gradle-download-task:4.0.4")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.12.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.12.1")

    testImplementation(gradleTestKit())
    testImplementation("org.spockframework:spock-core:1.0-groovy-2.4") {
        exclude(module = "groovy-all")
    }
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}

version = if (project.property("snapshot")?.toString()?.toBoolean() == true) {
    "${project.property("snapshotVersion")}-SNAPSHOT"
} else {
    project.property("version").toString()
}
group = "org.jetbrains.intellij.plugins"
description = """
**This project requires Gradle 4.9 or newer**

This plugin allows you to build plugins for IntelliJ Platform using specified IntelliJ SDK and bundled/3rd-party plugins.

The plugin adds extra IntelliJ-specific dependencies, patches `processResources` tasks to fill some tags 
(name, version) in `plugin.xml` with appropriate values, patches compile tasks to instrument code with 
nullability assertions and forms classes made with IntelliJ GUI Designer and provides some build steps which might be
helpful while developing plugins for IntelliJ platform.
"""

gradlePlugin {
    plugins.create("intellijPlugin") {
        id = "org.jetbrains.intellij"
        displayName = "Gradle IntelliJ Plugin"
        implementationClass = "org.jetbrains.intellij.IntelliJPlugin"
    }
}

pluginBundle {
    website = "https://github.com/JetBrains/gradle-intellij-plugin"
    vcsUrl = "https://github.com/JetBrains/gradle-intellij-plugin"
    description = "Plugin for building plugins for IntelliJ IDEs"
    tags = listOf("intellij", "jetbrains", "idea")
}

val cacheIntolerantTest = tasks.register<Test>("cacheIntolerantTest") {
    configureTests(this)
    include("**/DownloadIntelliJSpec.class")
    filter.isFailOnNoMatchingTests = false
}

tasks.test {
    configureTests(this)
    exclude("**/DownloadIntelliJSpec.class")
    dependsOn(cacheIntolerantTest)
}

fun configureTests(testTask: Test) {
    val testGradleHomePath = "$buildDir/testGradleHome"
    testTask.doFirst {
        File(testGradleHomePath).mkdir()
    }
    testTask.systemProperties["test.gradle.home"] = testGradleHomePath
    testTask.systemProperties["plugins.repo"] = project.property("pluginsRepo")
    testTask.outputs.dir(testGradleHomePath)
}

val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from(tasks.named("javadoc"))
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    repositories {
        maven {
            name = "snapshot"
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            credentials {
                username = project.property("ossrhUsername") as String
                password = project.property("ossrhPassword") as String
            }
        }
    }
    publications {
        create<MavenPublication>("snapshot") {
            groupId = project.group.toString()
            artifactId = project.name
            version = version.toString()

            artifact(tasks.findByName("jar"))
            artifact(sourcesJar.get())
            artifact(javadocJar.get())

            pom {
                name.set("Gradle IntelliJ Plugin")
                description.set(project.description)
                url.set("https://github.com/JetBrains/gradle-intellij-plugin")

                packaging = "jar"

                scm {
                    connection.set("scm:git:https://github.com/JetBrains/gradle-intellij-plugin/")
                    developerConnection.set("scm:git:https://github.com/JetBrains/gradle-intellij-plugin/")
                    url.set("https://github.com/JetBrains/gradle-intellij-plugin/")
                }

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("zolotov")
                        name.set("Alexander Zolotov")
                        email.set("zolotov@jetbrains.com")
                    }
                }
            }
        }
    }
}

tasks.wrapper {
    gradleVersion = "6.8"
    distributionUrl = "https://cache-redirector.jetbrains.com/services.gradle.org/distributions/gradle-${gradleVersion}-all.zip"
}

changelog {
    version = "${project.version}"
    path = "${project.projectDir}/CHANGES.md"
}

githubRelease {
    val releaseNote = if (changelog.has("${project.version}")) {
        changelog.get("${project.version}").toText()
    } else {
        ""
    }

    setToken(project.property("githubToken") as String)
    owner.set("jetbrains")
    repo.set("gradle-intellij-plugin")
    body.set(releaseNote)
}
