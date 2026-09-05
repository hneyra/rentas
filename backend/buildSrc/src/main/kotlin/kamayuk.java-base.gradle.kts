// Convenciones comunes a todo modulo Java del backend.
// Aqui no se declara ninguna dependencia de framework: eso decide cada capa.

plugins {
    `java-library`
}

group = "kamayuk.rentas"
version = "0.1.0-SNAPSHOT"

// ADR-0001 fija Java 25. La propiedad permite construir en un entorno que
// todavia no lo tiene; CI usa siempre el valor por omision de gradle.properties.
val versionDeJava = providers.gradleProperty("kamayuk.java.version").getOrElse("25").toInt()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(versionDeJava))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
