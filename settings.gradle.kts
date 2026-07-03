pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    println("AGC notice: .git metadata was not found. Local ZIP builds are allowed; manifest git fields will use safe fallback values.")
}

rootProject.name = "agc"

for (name in listOf("paper-api", "paper-server")) {
    include(name)
    file(name).mkdirs()
}

optionalInclude("test-plugin")
optionalInclude("paper-generator")

fun optionalInclude(name: String, op: (ProjectDescriptor.() -> Unit)? = null) {
    val settingsFile = file("$name.settings.gradle.kts")
    if (settingsFile.exists()) {
        apply(from = settingsFile)
        findProject(":$name")?.let { op?.invoke(it) }
    } else {
        settingsFile.writeText(
            """
            // Uncomment to enable the '$name' project
            // include(":$name")

            """.trimIndent()
        )
    }
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val paperVersionChannel = providers.gradleProperty("channel").get().trim()
    val paperBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (paperBuildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$paperBuildNumber-${paperVersionChannel.lowercase()}"
    }
    version = versionString
}

if (providers.gradleProperty("paperBuildCacheEnabled").orNull.toBoolean()) {
    val buildCacheUsername = providers.gradleProperty("paperBuildCacheUsername").orElse("").get()
    val buildCachePassword = providers.gradleProperty("paperBuildCachePassword").orElse("").get()
    if (buildCacheUsername.isBlank() || buildCachePassword.isBlank()) {
        println("The Paper remote build cache is enabled, but no credentials were provided. Remote build cache will not be used.")
    } else {
        val buildCacheUrl = providers.gradleProperty("paperBuildCacheUrl")
            .orElse("https://gradle-build-cache.papermc.io/")
            .get()
        val buildCachePush = providers.gradleProperty("paperBuildCachePush").orNull?.toBoolean()
            ?: System.getProperty("CI").toBoolean()
        buildCache {
            remote<HttpBuildCache> {
                url = uri(buildCacheUrl)
                isPush = buildCachePush
                credentials {
                    username = buildCacheUsername
                    password = buildCachePassword
                }
            }
        }
    }
}
