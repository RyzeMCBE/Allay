rootProject.name = "Allay"

// include multi modules
include(":api")
include(":server")
include(":codegen")
include(":data")

val requiredProtocolRegressionTest = file(
    "protocol-local/bedrock-codec/src/test/java/org/cloudburstmc/protocol/bedrock/codec/" +
            "v2168/serializer/ItemStackResponseSerializer_v2168Test.java"
)
check(requiredProtocolRegressionTest.isFile) {
    """
    protocol-local is missing the required v2168 inventory framing fix.
    Run these commands before building:
      git submodule sync --recursive
      git submodule update --init --recursive
    """.trimIndent()
}

includeBuild("protocol-local") {
    dependencySubstitution {
        substitute(module("org.allaymc.protocol:bedrock-codec"))
            .using(project(":bedrock-codec"))

        substitute(module("org.allaymc.protocol:bedrock-connection"))
            .using(project(":bedrock-connection"))
    }
}

val stateUpdaterDir = file("stateupdater-local")
check(stateUpdaterDir.resolve("settings.gradle.kts").isFile) {
    """
    stateupdater-local is missing.
    Initialize the Git submodules before building:
      git submodule sync --recursive
      git submodule update --init --recursive
    """.trimIndent()
}

includeBuild("stateupdater-local") {
    dependencySubstitution {
        substitute(module("org.allaymc.stateupdater:common"))
            .using(project(":common"))
        substitute(module("org.allaymc.stateupdater:block-updater"))
            .using(project(":block-updater"))
        substitute(module("org.allaymc.stateupdater:item-updater"))
            .using(project(":item-updater"))
    }
}
