plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.lanhu"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        create("IC", "2024.3.5")
    }
    implementation("org.sejda.imageio:webp-imageio:0.1.6")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.belerweb:pinyin4j:2.5.1")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
        }
    }
    buildSearchableOptions = false
}

tasks.test {
    useJUnitPlatform()
}
