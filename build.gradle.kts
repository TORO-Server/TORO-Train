plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "net.torosaba"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(kotlin("stdlib"))

    compileOnly("io.papermc.paper:paper-api:26.3.build.227-stable")

    testImplementation("io.papermc.paper:paper-api:26.3.build.227-stable")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveClassifier.set("thin")
}

// Kotlin の標準ライブラリは JAR に入れる。ほかの Kotlin 製プラグインと衝突しないよう、パッケージを移す
tasks.shadowJar {
    archiveBaseName.set("TORO-Train")
    archiveClassifier.set("")
    relocate("kotlin", "net.torosaba.train.libs.kotlin")
    relocate("org.jetbrains.annotations", "net.torosaba.train.libs.annotations")
    relocate("org.intellij.lang.annotations", "net.torosaba.train.libs.intellij")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
