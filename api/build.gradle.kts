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
        create<MavenPublication>("ryzeApi") {
            groupId = "org.ryzemcbe.allay"
            artifactId = "api"
            version = providers.gradleProperty("ryzemcbe.apiVersion")
                .orElse("${rootProject.property("api.version")}-SNAPSHOT")
                .get()
            from(components["java"])
            pom {
                name.set("RyzeMCBE Allay API")
                description.set("API para desarrollar plugins del fork RyzeMCBE/Allay.")
                url.set("https://github.com/RyzeMCBE/Allay")
            }
        }
    }
    repositories {
        maven {
            name = "RyzeMCBE"
            url = uri("https://maven.pkg.github.com/RyzeMCBE/Allay")
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
