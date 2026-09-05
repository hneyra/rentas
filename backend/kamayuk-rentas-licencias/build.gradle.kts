// Contexto acotado `licencias` (ARQ-01 §3.11).
//
// La licencia municipal de funcionamiento, sus giros CIIU, sus duplicados y su
// cancelacion (#44, RF-110, RF-111, RF-112).
//
// LA LICENCIA NO GENERA DEUDA; EL ANUNCIO SI. El derecho de tramite de una
// licencia se paga en caja de tasas ANTES de emitir —por eso este modulo lee
// recibos— y un derecho de tramite no es deuda tributaria: no se determina, no
// devenga interes y no prescribe. La tasa de un anuncio (#51, RF-114) es otra
// cosa: se determina al autorizar, se cobra despues y puede quedar impaga, asi
// que es deuda y entra en el libro. Con #51 este modulo pasa a depender de
// `cuentacorriente`, y lo hace por su API PUBLICA —`GeneradorDeCargos`, en el
// paquete raiz— y nunca por sus tablas: Spring Modulith rechaza cualquier
// entrada por `.dominio` o `.infraestructura` (ARQ-01 §4 regla 2).
//
// Lo que sigue sin estar es la deuda de ARBITRIOS del establecimiento: la
// determina `rentas` con tablas de ordenanza bloqueadas por D-02b.

plugins {
    id("kamayuk.modulo")
    id("kamayuk.pruebas-postgres")
}

dependencies {
    // Los dobles en memoria de los puertos de `catastro` (P5C). Sus tablas se fueron con `V6`:
    // lo que estas pruebas necesitaban de ellas era la premisa, y ahi es donde se escribe.
    testImplementation(testFixtures(project(":kamayuk-rentas-catastro")))
    testImplementation(testFixtures(project(":kamayuk-rentas-parametros")))
    // Las tres APIs publicas que la licencia consume. Nunca sus tablas: Spring
    // Modulith verifica que no se cruce el limite (ARQ-01 §4).
    //
    //  - tesoreria.RecibosDeTramite: comprobar que el derecho esta pagado, sin
    //    leer `recibo` (AC 1 de #44).
    //  - contribuyentes.DirectorioDeContribuyentes: resolver el titular que llega
    //    por HTTP y su nombre para el papel.
    //  - catastro.LectorDeFichasEconomicas: la ficha economica del predio donde
    //    esta el establecimiento (#19, AC 3 de #44).
    implementation(project(":kamayuk-rentas-tesoreria"))
    implementation(project(":kamayuk-rentas-contribuyentes"))
    implementation(project(":kamayuk-rentas-catastro"))

    // Y con #51, la quinta: cuentacorriente.GeneradorDeCargos. Registrar un
    // anuncio GENERA la deuda por su tasa, y esa deuda se le PIDE al libro por
    // su API publica en vez de asentarla por cuenta propia. Nunca sus tablas.
    implementation(project(":kamayuk-rentas-cuentacorriente"))

    // Y la cuarta: parametros.LectorDeParametros. De ahi sale QUE concepto del
    // TUPA cobra el derecho de tramite. El importe no: ese vive en la tabla
    // `tasa` desde V3, con su ordenanza y su vigencia, y copiarlo al conjunto
    // sellado dejaria dos sitios donde vive la misma tarifa (regla 5).
    implementation(project(":kamayuk-rentas-parametros"))

    // Las pruebas de repositorio y del ciclo completo corren contra PostgreSQL de
    // verdad, conectadas como kamayuk_app y no como el superusuario que entrega
    // Testcontainers (CAL-01 §3.2). Contra un doble no se puede demostrar ni el
    // REVOKE UPDATE de V37, ni que dos duplicados simultaneos no compartan
    // ordinal, ni que RLS aisle la licencia.
    testImplementation(testFixtures(project(":kamayuk-rentas-esquema")))
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")

    // El caso de uso se prueba envuelto en un proxy transaccional de verdad, para
    // que lo que se verifique sea la anotacion y no un TransactionTemplate de la
    // prueba.
    testImplementation("org.springframework:spring-aop")

    // MockMvc para el endpoint: transporte sin base de datos.
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly(libs.postgresql)
}
