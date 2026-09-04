// Contexto acotado `cuentacorriente` (ARQ-01 §3).
//
// El libro de asientos (issue #21): cargos, abonos y su reversion. La primera
// funcionalidad de negocio del contexto; el resto (saldo proyectado, altas y
// bajas de deuda) sigue bloqueado por D-02.

plugins {
    id("sgtm.modulo")
    id("sgtm.pruebas-postgres")
}

dependencies {
    testImplementation(testFixtures(project(":kamayuk-rentas-parametros")))
    // La prueba del repositorio corre contra PostgreSQL de verdad: provisiona la
    // base como un ambiente real y se conecta como sgtm_app, no como el
    // superusuario que entrega Testcontainers (CAL-01 §3.2).
    testImplementation(testFixtures(project(":kamayuk-rentas-esquema")))
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")

    // El caso de uso se prueba envuelto en un proxy transaccional de verdad, para
    // que lo que se verifique sea la anotacion y no un TransactionTemplate escrito
    // por la propia prueba.
    testImplementation("org.springframework:spring-aop")
    testImplementation("org.springframework:spring-test")

    // El formato impreso de un alta o una baja (#24) se emite con EmitirDocumento, y
    // su repositorio guarda el modelo como JSON: la prueba necesita el mapeador para
    // construirlo igual que lo construye la aplicacion.
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testRuntimeOnly(libs.postgresql)
}
