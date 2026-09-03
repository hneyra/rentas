// El esquema de `rentas`: las migraciones, el migrador y la prueba de aislamiento.
//
// Deliberadamente NO depende de Spring, igual que su origen en el monolito: la prueba verifica el
// MOTOR de base de datos, no la aplicacion, y levantar un contexto de Spring solo agregaria formas
// de que pase en verde por el motivo equivocado.
//
// HOY NO HAY NI UNA MIGRACION. El baseline por sistema lo genera ADR-0032 y esta etapa no lo
// inventa; lo que si esta desde el primer dia es la prueba, que verifica el mecanismo —los roles,
// RLS con FORCE, y que el rol de la aplicacion no sea superusuario— sobre una tabla que ella misma
// crea. Cuando llegue el baseline, su censo pasa a tener tablas que censar sin cambiar una linea.

plugins {
    id("java-library")
    id("java-test-fixtures")
    id("application")
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
    // Flyway va en el codigo de produccion y no solo en los fixtures porque el despliegue migra
    // con ESTE codigo. Si el contenedor de migracion trajera su propia version, lo verificado en
    // CI y lo desplegado en la municipalidad dejarian de ser lo mismo sin que nada lo dijera.
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testFixturesApi(platform(libs.testcontainers.bom))
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.flyway.core)
    testFixturesRuntimeOnly(libs.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.postgresql)
}

application {
    mainClass.set("kamayuk.rentas.esquema.Migrador")
    applicationName = "migrar"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Sin esto, un fallo de aislamiento podria quedar oculto por el cache de Gradle cuando cambia
    // solo el motor de base de datos y no las fuentes.
    outputs.upToDateWhen { false }

    // El motor externo, para las maquinas sin Docker (ver README del esquema). Gradle no propaga
    // las propiedades de sistema del build al proceso de prueba.
    for (propiedad in listOf(
        "kamayuk.pruebas.postgres.url",
        "kamayuk.pruebas.postgres.usuario",
        "kamayuk.pruebas.postgres.clave",
        "kamayuk.pruebas.postgres.imagen"
    )) {
        providers.systemProperty(propiedad).orNull?.let { systemProperty(propiedad, it) }
    }
}
