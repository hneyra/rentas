// Convenciones de prueba: JUnit y AssertJ para todo modulo que tenga pruebas.
// Testcontainers NO va aqui: solo lo usan los modulos con pruebas de persistencia,
// y no conviene arrastrarlo a las pruebas de reglas tributarias, que deben poder
// ejecutarse sin Docker ni base de datos.

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    // La cadena es calidad -> java-base, asi que aplicar esta trae las dos.
    id("kamayuk.calidad")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

// #450: en las pruebas, un viaje de red con una transaccion abierta NO SALE — lanza. En
// produccion `ViajeDeRed` solo avisa, porque un llamador que nadie enumero no puede dejar de
// atender por un defecto de rendimiento; aqui tiene que salir rojo en la prueba que lo ejerce, o
// la guarda solo cazaria lo que ya se sabe. La lee `ViajeDeRedTest`, que falla si esto se quita.
tasks.withType<Test>().configureEach {
    systemProperty("kamayuk.red.con-transaccion", "fallar")
}
