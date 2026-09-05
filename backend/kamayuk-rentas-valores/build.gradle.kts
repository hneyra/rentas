// Contexto acotado `valores` (ARQ-01 §3).
//
// La primera funcionalidad de negocio: numeracion correlativa y generacion
// individual de OP/RD/RM (#37). Un valor no crea deuda -la formaliza-, asi
// que depende de cuentacorriente para leerla y para mover su fase, y de
// contribuyentes para resolver a quien se le emite.

plugins {
    id("kamayuk.modulo")
    id("kamayuk.pruebas-postgres")
}

dependencies {
    testImplementation(testFixtures(project(":kamayuk-rentas-parametros")))
    // Leer la deuda a congelar (ConsultaDeDeudaPublica) y mover su fase al
    // emitir (MovimientoDeFase): las dos APIs publicas de cuentacorriente
    // que un "acto posterior" a la determinacion necesita (ARQ-01 §4 regla 2).
    implementation(project(":kamayuk-rentas-cuentacorriente"))
    // Resolver el codigo de contribuyente de la peticion a su identificador, y el
    // domicilio fiscal vigente a la fecha de la diligencia, que es donde se notifica (#15, #39).
    implementation(project(":kamayuk-rentas-contribuyentes"))
    // Los plazos del Codigo Tributario -notificacion, prescripcion, inicio del computo-
    // salen del conjunto sellado vigente a la fecha del hecho, nunca de una constante
    // (regla 5, #39). `parametros` es de solo lectura para todos los demas (ARQ-01 §4 regla 3).
    implementation(project(":kamayuk-rentas-parametros"))

    // La prueba del repositorio corre contra PostgreSQL de verdad: provisiona la
    // base como un ambiente real y se conecta como kamayuk_app, no como el
    // superusuario que entrega Testcontainers (CAL-01 §3.2).
    testImplementation(testFixtures(project(":kamayuk-rentas-esquema")))
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")

    // El caso de uso se prueba envuelto en un proxy transaccional de verdad, para
    // que lo que se verifique sea la anotacion y no un TransactionTemplate escrito
    // por la propia prueba.
    testImplementation("org.springframework:spring-aop")

    // MockMvc para el endpoint: transporte sin base de datos.
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly(libs.postgresql)
}

// Las pruebas del derivado publicable leen el CSV de `normativa`, el repositorio hermano
// (#192, y desde P5B ADR-0025 §5: el corpus se fue con el servicio). Sin declararlo
// como entrada, editar el CSV deja a `test` en UP-TO-DATE y una rotura del derivado pasa
// en verde rancio en local; en CI corre fresco y muerde, pero el sintoma local mentiria.
tasks.test {
    inputs
        .file(rootProject.file("../../normativa/docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
