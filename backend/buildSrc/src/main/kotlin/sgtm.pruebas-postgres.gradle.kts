// Pruebas que necesitan un PostgreSQL real (CAL-01 §2: prohibida la base en
// memoria para pruebas de persistencia, porque H2 no tiene RLS).
//
// Por omision las pruebas levantan un contenedor con Testcontainers. Estas cuatro
// propiedades permiten apuntar a un PostgreSQL ya existente donde no se puede
// descargar la imagen; hay que reenviarlas al proceso de prueba porque Gradle no
// propaga las propiedades de sistema del build.

plugins {
    id("sgtm.pruebas")
}

tasks.withType<Test>().configureEach {
    listOf(
        "kamayuk.pruebas.postgres.url",
        "kamayuk.pruebas.postgres.usuario",
        "kamayuk.pruebas.postgres.clave",
        "kamayuk.pruebas.postgres.imagen",
    ).forEach { propiedad ->
        providers.systemProperty(propiedad).orNull?.let { systemProperty(propiedad, it) }
    }
}
