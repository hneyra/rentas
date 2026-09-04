// Contexto acotado `tesoreria` (ARQ-01 §3.8).
//
// El punto donde entra el dinero: caja tributaria, caja de tasas y el recibo
// con su numeracion (#33, RF-080, RF-081, RF-133).
//
// ASIENTA ABONOS; NUNCA DETERMINA. Aqui no hay una sola regla de calculo:
// cuanto se debe lo dice `cuentacorriente` releyendo su libro, y lo que este
// modulo hace con esa cifra es documentarla en un recibo. Si la caja calculara
// deuda, el sistema tendria dos verdades.

plugins {
    id("sgtm.modulo")
    id("sgtm.pruebas-postgres")
}

dependencies {
    testImplementation(testFixtures(project(":kamayuk-rentas-parametros")))
    // Las dos APIs publicas que la caja consume: leer la deuda a la fecha de pago
    // (ConsultaDeDeudaPublica) y asentar su abono (RegistroDeAbonos). Nunca sus
    // tablas: Spring Modulith verifica que no se cruce el limite (ARQ-01 §4).
    implementation(project(":kamayuk-rentas-cuentacorriente"))
    // Resolver el codigo de contribuyente que llega por HTTP a su identificador (#15).
    implementation(project(":kamayuk-rentas-contribuyentes"))
    // El interes de fraccionamiento, el maximo de cuotas y la politica de redondeo de
    // la cuota salen del conjunto sellado, nunca del codigo (#35, regla 5, D-02b).
    implementation(project(":kamayuk-rentas-parametros"))

    // Las pruebas de repositorio y de atomicidad corren contra PostgreSQL de verdad:
    // provisionan la base como un ambiente real y se conectan como sgtm_app, no como
    // el superusuario que entrega Testcontainers (CAL-01 §3.2). Contra un doble no se
    // puede demostrar ni el FOR UPDATE, ni el REVOKE UPDATE, ni que una transaccion
    // deje cero filas al fallar a mitad.
    testImplementation(testFixtures(project(":kamayuk-rentas-esquema")))
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")

    // El caso de uso se prueba envuelto en un proxy transaccional de verdad, para que
    // lo que se verifique sea la anotacion y no un TransactionTemplate de la prueba.
    testImplementation("org.springframework:spring-aop")

    // MockMvc para el endpoint: transporte sin base de datos.
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly(libs.postgresql)
}
