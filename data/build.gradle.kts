dependencies {
    implementation(project(":server"))
    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // GearsMC: veri testleri dosyaları üretim araçlarıyla aynı göreli yoldan ("data/resources/...") okur.
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("importBedrockData") {
    group = "data"
    description = "26.50 veri setini staging-1.26.50'ye üretir (ağ ve yerel bds_registry_dump.json gerekir)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.allaymc.data.importer.BedrockDataImporter"
    workingDir = rootProject.projectDir
}

// GearsMC: veri işlemcilerini (LangBuilder, BlockStateDataProcessor, ...) IDE olmadan çalıştırmak için.
// ./gradlew :data:runMain -PmainClass=org.allaymc.data.LangBuilder
tasks.register<JavaExec>("runMain") {
    group = "data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = providers.gradleProperty("mainClass")
    workingDir = rootProject.projectDir
}
