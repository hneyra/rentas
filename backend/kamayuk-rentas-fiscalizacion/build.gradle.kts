// Contexto acotado `fiscalizacion` (ARQ-01 §3).
//
// Programacion y actas (#45): registro puro sobre copias, sin una sola cifra
// tributaria. Liquidacion, reliquidacion, omisos y subvaluadores (#49): la
// ESTRUCTURA del contraste hallado/declarado, tambien sin una sola cifra —los
// importes liquidados y las multas viven en #198, con `bloqueado:D-02a`—.

plugins {
    id("sgtm.modulo")
    id("sgtm.pruebas-postgres")
}

dependencies {
    // Los dobles en memoria de los puertos de `catastro` (P5C). Sus tablas se fueron con `V6`:
    // lo que estas pruebas necesitaban de ellas era la premisa, y ahi es donde se escribe.
    testImplementation(testFixtures(project(":kamayuk-rentas-catastro")))
    testImplementation(testFixtures(project(":kamayuk-rentas-parametros")))
    // El acta predial referencia la version de ficha catastral vigente a la
    // fecha de la visita (RNF-075), igual que declaracion_jurada en rentas
    // (#28). Solo se importa el paquete raiz de catastro, que es su API
    // publica: LectorDeFichas, LectorDeCaracteristicas y —desde #545—
    // TitularesDelPredio (ARQ-01 §2 catastro ──► fiscalizacion).
    implementation(project(":kamayuk-rentas-catastro"))

    // La liquidacion pregunta si el predio declaro el ejercicio que se le
    // liquida, y eso solo lo puede contestar `rentas`: se pregunta por su
    // paquete raiz, DeclaracionesDelEjercicio.
    //
    // La DETECCION de omisos ya no pasa por ahi (#545): su filtro de condicion
    // tiene que acotar el conjunto antes de paginar, y la condicion se DERIVA
    // del cruce, asi que la consulta es una y vive en `DeteccionRepositoryJdbc`
    // —con su porque escrito en `DeteccionRepository`—.
    implementation(project(":kamayuk-rentas-nucleo"))

    // La liquidacion fija el conjunto SELLADO del ejercicio de cada linea
    // (AC 1 de #49) y lo recupera por identificador, nunca por ejercicio.
    // Traducir «ejercicio» a «conjunto» es cosa de `parametros`.
    implementation(project(":kamayuk-rentas-parametros"))

    // El estado de cuenta de fiscalizacion (RF-056) pregunta cuanto se debe a
    // una fecha, y la unica fuente de eso es el libro. Arista al reves de las
    // otras: es la excepcion que preve ARQ-01 §4 regla 2.
    implementation(project(":kamayuk-rentas-cuentacorriente"))

    // Las pantallas hablan del contribuyente por su codigo, no por su
    // identificador interno: DirectorioDeContribuyentes lo traduce.
    implementation(project(":kamayuk-rentas-contribuyentes"))

    // La prueba del repositorio corre contra PostgreSQL de verdad: provisiona la
    // base como un ambiente real y se conecta como sgtm_app, no como el
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

// El contrato vive fuera de este modulo y `LaMuestraSeSorteaTest` lo lee del
// disco: comprueba que ninguna ruta de fiscalizacion admita una seleccion de
// predios ni una esquela (#550, ADR-0023). Sin declararlo como entrada, editar
// el YAML deja a `test` en UP-TO-DATE y la guarda pasa en **verde rancio** en
// local —en CI corre fresco y muerde, que es la peor forma de enterarse—. Es la
// leccion de #192 punto 2, que #399 volvio a medir sobre este mismo archivo.
tasks.test {
    inputs
        .file(rootProject.file("../docs/50-api/openapi/rentas-v1.yaml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
