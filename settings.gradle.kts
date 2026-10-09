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
