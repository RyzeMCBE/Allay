plugins {
    id("maven-publish")
}

dependencies {
    api(libs.nbt)
    api(libs.slf4j.api)
    api(libs.guava)
    api(libs.gson)
    api(libs.annotations)
    api(libs.joml) {
        // NOTICE: this is an accident that joml marked kotlin-stdlib as its dependency
        // in the recent version, see https://github.com/JOML-CI/JOML/pull/357 for more
        // information. And this is a quick workaround that we just exclude kotlin-stdlib
        // TODO: remove this workaround when joml release 1.10.9
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    api(libs.joml.primitives) {
        // Use our latest version of joml
        exclude(group = "org.joml", module = "joml")
    }
    api(libs.snakeyaml)
    api(libs.semver4j)
}

tasks.withType<Javadoc>().configureEach {
    enabled = true
    isFailOnError = false

    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}

java {
    withJavadocJar()
}

publishing {
    publications {

        // Separate, immutable public publication for Maven Central.
        // Keep the existing ryzeApi publication and GitHub Packages unchanged.
        create<MavenPublication>("centralApi") {
            groupId = "io.github.ryzemcbe"
            artifactId = "engine-api"
            version = providers.gradleProperty("engine.centralVersion")
                .orElse(rootProject.property("api.version").toString())
                .get()
            from(components["java"])
            pom {
                name.set("Engine API (Allay-compatible)")
                description.set("Public Java API for Engine Minecraft Bedrock server, preserving Allay 0.30.0 plugin compatibility.")
                url.set("https://github.com/RyzeMCBE/Engine")
                licenses {
                    license {
                        name.set("GNU Lesser General Public License, Version 3.0")
                        url.set("https://www.gnu.org/licenses/lgpl-3.0.html")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("RyzeMCBE")
                        name.set("RyzeMCBE Development Studio")
                        organization.set("RyzeMCBE")
                        organizationUrl.set("https://github.com/RyzeMCBE")
                    }
                    developer {
                        id.set("AllayMC")
                        name.set("AllayMC Team")
                        organization.set("AllayMC")
                        organizationUrl.set("https://github.com/AllayMC")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/RyzeMCBE/Engine.git")
                    developerConnection.set("scm:git:ssh://git@github.com/RyzeMCBE/Engine.git")
                    url.set("https://github.com/RyzeMCBE/Engine")
                    tag.set("HEAD")
                }
            }
        }
        create<MavenPublication>("ryzeApi") {
            groupId = "org.ryzemcbe.allay"
            artifactId = "api"
            version = providers.gradleProperty("ryzemcbe.apiVersion")
                .orElse("${rootProject.property("api.version")}-SNAPSHOT")
                .get()
            from(components["java"])
            pom {
                name.set("Engine (Allay-compatible) API")
                description.set("API compatible con plugins Allay, mantenida para Engine por RyzeMCBE.")
                url.set("https://github.com/RyzeMCBE/Engine")
            }
        }
    }
    repositories {
        // Staging repository used to build and verify an offline Maven Central
        // upload bundle before sending it to the Central Publishing Portal.
        maven {
            name = "CentralBundle"
            url = uri(rootProject.file("build/central-api-maven"))
        }
        // Produce a complete, versioned Maven repository inside the downloadable
        // Developer SDK. This does not require GitHub Packages credentials.
        maven {
            name = "EngineSdkBundle"
            url = uri(rootProject.file("build/engine-sdk-maven"))
        }
        maven {
            name = "RyzeMCBE"
            url = uri("https://maven.pkg.github.com/RyzeMCBE/Engine")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .getOrElse("")
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .getOrElse("")
            }
        }
    }
}
