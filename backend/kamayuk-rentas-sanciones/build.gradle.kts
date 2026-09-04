// Contexto acotado `sanciones` (ARQ-01 §3).
//
// El catalogo de codigos de infraccion (#43) es la primera funcionalidad: registro puro,
// sin ningun literal tributario (regla 5) y sin una sola cifra de multa. Calcular la multa
// sigue bloqueado por D-02.

plugins {
    id("sgtm.modulo")
    id("sgtm.pruebas-postgres")
}

dependencies {
    testImplementation(testFixtures(project(":kamayuk-rentas-parametros")))
    // La papeleta asienta su cargo por referencia externa, sin depender de
    // ninguna clase interna de cuentacorriente (#46, ARQ-01 §4 regla 2): solo se
    // importa el paquete raiz, que es su API publica: GeneradorDeCargos.
    implementation(project(":kamayuk-rentas-cuentacorriente"))

    // Con #50, tres APIs publicas mas. Nunca sus tablas: Spring Modulith verifica
    // que no se cruce el limite (ARQ-01 §4).
    //
    //  - contribuyentes.DirectorioDeContribuyentes: el nombre y el domicilio del
    //    obligado que la resolucion de gerencia imprime y notifica.
    //  - parametros.LectorDeParametros: de ahi salen el plazo del descargo y el
    //    que la resolucion ordinaria concede. Que esten en una norma es
    //    exactamente lo que los hace dato (regla 5), igual que #39 y #41.
    //  - tesoreria.CobrosDeTasas: la comprobacion de que la custodia esta pagada
    //    antes de soltar un vehiculo del deposito. La casilla que el prototipo
    //    dibuja la marca quien entrega el vehiculo; el recibo lo dice la caja.
    implementation(project(":kamayuk-rentas-contribuyentes"))
    implementation(project(":kamayuk-rentas-parametros"))
    implementation(project(":kamayuk-rentas-tesoreria"))

    // Con #53, una mas: valores.EmisionDeValoresDeMultas. Es lo que hace posible el
    // primer criterio de aceptacion del issue -«la generacion masiva reutiliza la
    // numeracion de #37; no inventa un correlativo propio»- sin abrir el modulo:
    // `sanciones` pide «emiteme la resolucion de multa de esta obligacion» y recibe
    // el numero ya puesto por valor_correlativo (V26). La dependencia va en este
    // sentido y nunca al reves: `valores` no sabe que existe una papeleta.
    implementation(project(":kamayuk-rentas-valores"))

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
