// Donde corren las barreras de `rentas`.
//
// Es el equivalente de `sgtm-aplicacion` en el monolito: el unico modulo que ve a todos los demas,
// y por eso el unico que puede aplicarles las reglas. Hoy no ve ninguno, porque todavia no hay
// contextos acotados; en P5 los declara aqui uno a uno.

plugins {
    id("java-library")
}

group = "kamayuk.rentas"
version = "0.1.0-SNAPSHOT"

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

dependencies {
    // Ve al modulo del esquema, que es el unico que hay. Es la razon de ser de este modulo: en el
    // monolito, `sgtm-aplicacion` es el unico que tiene a todos los demas en el classpath, y por
    // eso es el unico que puede aplicarles las reglas de ArchUnit. En P5 se declaran aqui los
    // contextos acotados, uno por linea y visible en el diff.
    implementation(project(":kamayuk-esquema"))

    // Las barreras, compartidas con los otros cuatro repositorios (composite build; ver
    // settings.gradle.kts). Trae ArchUnit, JUnit y AssertJ consigo como `api`.
    testImplementation("kamayuk.comun:comun-verificaciones")
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // El escaner de aserciones lee `src/test` de TODOS los modulos del repositorio, y esas fuentes
    // no estan en el classpath de este. Sin declararlas como entrada, editar una prueba de otro
    // modulo dejaria esta tarea en UP-TO-DATE y una asercion rota pasaria en verde rancio en
    // local. Es la leccion de #192 punto 2 aplicada al unico escaner que recorre src/test.
    inputs
        .files(
            rootProject.layout.projectDirectory.asFileTree.matching {
                include("*/src/test/java/**/*.java")
                include("*/src/main/java/**/*.java")
                include("*/src/main/resources/db/**/*.sql")
            })
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
