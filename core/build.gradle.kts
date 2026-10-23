plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Descarga las fuentes (PvPoke, PokeMiners) y genera el JSON compacto que se empaqueta en la app.
tasks.register<JavaExec>("generateGameData") {
    group = "esmeraldago"
    description = "Genera app/src/main/assets/gamedata.json desde las fuentes online"
    mainClass.set("com.esmeraldago.core.data.GenerateGameDataKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(
        rootProject.file("app/src/main/assets/gamedata.json").absolutePath,
        rootProject.file("data-cache").absolutePath,
    )
}
