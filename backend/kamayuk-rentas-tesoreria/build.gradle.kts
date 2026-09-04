// Contexto acotado `tesoreria` (ARQ-01 §3.8), PARTIDO EN DOS por P5D (ADR-0026).
//
// El modulo hace hoy DOS cosas, y conviene decirlo aqui porque su nombre ya no lo dice:
//
//  1. LLEVA EL CONTEXTO DEL CONVENIO de fraccionamiento —preconvenio, cronograma,
//     formalizacion, quiebre y reformulacion— CON SU DOMINIO Y SUS TABLAS, que se quedan
//     de verdad en esta base. Un convenio es deuda reprogramada: tiene interes, tiene
//     quiebre y tiene consecuencias coactivas, y si viajara a `caja`, `caja` adquiriria
//     reglas tributarias y dejaria de poder cobrar un puesto de mercado (ADR-0026 §5).
//
//  2. ES EL ADAPTADOR CLIENTE DE `caja`. `V7` retiro de esta base las diez tablas por las
//     que entra el dinero —recibo, su detalle, sus movimientos, su serie, la ventanilla,
//     su arqueo, el turno, su detalle, el catalogo del TUPA y el area—. Los cuatro
//     puertos del paquete raiz que preguntan por ellas NO se tocaron: eran el contrato
//     desde #44, #50 y #56, y por eso `licencias`, `sanciones`, `coactiva` e
//     `indicadores` no cambiaron ni una linea. Lo unico que cambio es quien los
//     implementa.
//
// POR ESO CONSERVA `sgtm.pruebas-postgres`, al reves que `kamayuk-rentas-catastro` y
// `kamayuk-rentas-parametros`, que quedaron como clientes puros y se lo quitaron: aqui
// `ConvenioRepositoryJdbc` y `MovimientoDeConvenioRepositoryJdbc` siguen escribiendo SQL
// contra tablas de este sistema, y eso se prueba contra PostgreSQL de verdad o no se
// prueba.
//
// ASIENTA ABONOS; NUNCA DETERMINA. Aqui no hay una sola regla de calculo de deuda: cuanto
// se debe lo dice `cuentacorriente` releyendo su libro.

plugins {
    id("sgtm.modulo")
    id("sgtm.pruebas-postgres")
}

dependencies {
    testImplementation(testFixtures(project(":kamayuk-rentas-parametros")))
    // Las dos APIs publicas que el convenio consume: leer la deuda a la fecha
    // (ConsultaDeDeudaPublica) y acogerla o devolverla (AcogimientoAConvenio). Nunca sus
    // tablas: Spring Modulith verifica que no se cruce el limite (ARQ-01 §4).
    implementation(project(":kamayuk-rentas-cuentacorriente"))
    // Resolver el codigo de contribuyente que llega por HTTP a su identificador (#15).
    implementation(project(":kamayuk-rentas-contribuyentes"))
    // El interes de fraccionamiento, el maximo de cuotas y la politica de redondeo de
    // la cuota salen del conjunto sellado, nunca del codigo (#35, regla 5, D-02b).
    implementation(project(":kamayuk-rentas-parametros"))

    // P5D: el cliente de `caja`. Habla HTTP con la JDK; de Spring solo entran el
    // estereotipo, `@Value` y el acceso a la peticion en curso —de donde sale el token
    // que se reenvia— y de Jackson el arbol JSON. Ni un cliente HTTP de framework, por lo
    // mismo que en `ClienteHttpDeNormativa` y `ClienteHttpDeCatastro`.
    //
    // `spring-web` no estaba antes y ahora hace falta: los controladores lo traian por el
    // `starter-web` del ensamblado, y lo que se necesita aqui —`RequestContextHolder`—
    // vive en el propio `spring-web`, no en el starter.
    implementation("org.springframework:spring-web")
    implementation("tools.jackson.core:jackson-databind")

    // Las pruebas del convenio corren contra PostgreSQL de verdad: provisionan la base
    // como un ambiente real y se conectan como sgtm_app, no como el superusuario que
    // entrega Testcontainers (CAL-01 §3.2). Contra un doble no se puede demostrar ni el
    // aislamiento, ni el REVOKE UPDATE, ni que una transaccion deje cero filas al fallar
    // a mitad.
    testImplementation(testFixtures(project(":kamayuk-rentas-esquema")))
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")

    // El caso de uso se prueba envuelto en un proxy transaccional de verdad, para que
    // lo que se verifique sea la anotacion y no un TransactionTemplate de la prueba.
    testImplementation("org.springframework:spring-aop")

    // MockMvc para el endpoint: transporte sin base de datos.
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly(libs.postgresql)
}
