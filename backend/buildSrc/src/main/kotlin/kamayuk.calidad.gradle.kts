// Formato, estilo y nulidad (ARQ-04 §5). Las tres son bloqueantes.
//
// Reparto deliberado, para que no se peleen entre si:
//   - Spotless impone el FORMATO y sabe arreglarlo solo (`./gradlew spotlessApply`).
//   - Checkstyle revisa lo que el formato no ve: nombres, tipos prohibidos, trampas
//     del lenguaje. Su configuracion no menciona formato.
//   - NullAway revisa la NULIDAD sobre las anotaciones de JSpecify.
//
// Una configuracion de estilo que pelea con el formateador se acaba desactivando, y
// entonces no queda ninguna de las dos.

import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("kamayuk.java-base")
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
    checkstyle
}

spotless {
    java {
        target("src/**/*.java")
        // AOSP: 4 espacios y 100 columnas. La variante de 2 espacios deja el codigo
        // con dominio en español ilegible en cuanto hay tres niveles de anidamiento.
        googleJavaFormat("1.36.1").aosp()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("sql") {
        target("src/**/*.sql")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = "13.9.0"
    configDirectory.set(rootProject.layout.projectDirectory.dir("config/checkstyle"))
    isIgnoreFailures = false
    maxWarnings = 0
}

// Checkstyle sobre las pruebas tambien: las clases de muestra que violan reglas a
// proposito viven ahi, y conviene que las violen solo en lo que dicen violar.
tasks.withType<Checkstyle>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(false)
    }
}

val libs = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

dependencies {
    "errorprone"(libs.findLibrary("errorprone-core").get())
    "errorprone"(libs.findLibrary("nullaway").get())
    "api"(libs.findLibrary("jspecify").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        // Solo NullAway. Error Prone completo trae cientos de comprobaciones y
        // adoptarlas todas de golpe en un proyecto que empieza es la forma segura de
        // acabar con -Xep:...:OFF por todas partes.
        disableAllChecks.set(true)
        check("NullAway", net.ltgt.gradle.errorprone.CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "kamayuk.rentas")
        option("NullAway:JSpecifyMode", "true")
    }
}

// En las pruebas la nulidad se relaja: una prueba que verifica que algo falla ante
// null tiene que poder pasar null.
listOf("compileTestJava", "compileTestFixturesJava").forEach { tarea ->
    tasks.matching { it.name == tarea }.configureEach {
        (this as JavaCompile).options.errorprone {
            check("NullAway", net.ltgt.gradle.errorprone.CheckSeverity.OFF)
        }
    }
}
