// Contexto acotado `coactiva` (ARQ-01 §3.10).
//
// El expediente coactivo: la carpeta que agrupa los valores exigibles de un
// contribuyente y lleva su propio ciclo (#40, RF-100, RF-106).
//
// EMPIEZA DONDE TERMINA EL PASE. Aqui no se decide si una deuda es exigible:
// eso lo decidio `valores` al registrar el movimiento PCO, con la diligencia
// que lo sustenta y el plazo parametrizado. Este modulo agrupa lo que ya lo es,
// lo numera y lleva la traza de lo que le pasa.
//
// Y NO CALCULA DEUDA. Cuanto se debe a una fecha lo dice `cuentacorriente`
// releyendo su libro; lo que este modulo hace con esa cifra es mostrarla con la
// fecha a la que esta (regla 9).

plugins {
    id("sgtm.modulo")
    id("sgtm.pruebas-postgres")
}

dependencies {
    // Las tres APIs publicas que el expediente consume. Nunca sus tablas: Spring
    // Modulith verifica que no se cruce el limite (ARQ-01 §4).
    //
    //  - valores.ValoresEnCoactiva: que valores tienen pase, que obligaciones
    //    formalizan, y la respuesta ACO que #39 dejo anunciada.
    //  - cuentacorriente.ConsultaDeDeudaPublica: cuanto se debe A UNA FECHA.
    //  - contribuyentes.DirectorioDeContribuyentes: resolver el codigo que llega
    //    por HTTP a su identificador.
    implementation(project(":kamayuk-rentas-valores"))
    implementation(project(":kamayuk-rentas-cuentacorriente"))
    implementation(project(":kamayuk-rentas-contribuyentes"))

    // Y la cuarta, desde #41: parametros.LectorDeParametros. De ahi sale el plazo
    // de los siete dias habiles que la REC-1 concede (art. 14.1 de la Ley 26979).
    // Que este en una ley no lo convierte en una constante del programa: la regla
    // 5 lo quiere como dato sellado por ejercicio, igual que #39 hizo con el plazo
    // de reclamacion de un valor.
    implementation(project(":kamayuk-rentas-parametros"))

    // Y las dos que #42 agrega, las dos por API publica y ninguna por sus tablas:
    //
    //  - tesoreria.FraccionamientoCoactivo: el convenio coactivo NO se reimplementa
    //    aqui. Es el mismo mecanismo de #35 -misma deuda acogible releida del libro,
    //    mismas condiciones del conjunto sellado, mismo quiebre que devuelve a la
    //    fase de ORIGEN-, invocado por su puerto. Lo que coactiva agrega son sus dos
    //    guardas: expediente vivo y deuda que venga de la fase coactiva.
    //  - rentas.BeneficiosDelContribuyente: que beneficio tiene REGISTRADO el
    //    obligado, para la consulta de deudas en beneficio (RF-107). Solo el
    //    registro: el efecto sobre el importe es D-02b (#191) y no se calcula.
    implementation(project(":kamayuk-rentas-tesoreria"))
    implementation(project(":kamayuk-rentas-rentas"))

    // Las pruebas de repositorio y del ciclo completo corren contra PostgreSQL de
    // verdad, conectadas como sgtm_app y no como el superusuario que entrega
    // Testcontainers (CAL-01 §3.2). Contra un doble no se puede demostrar ni el
    // REVOKE UPDATE del historial, ni que un valor no entre en dos expedientes.
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

// Las pruebas del derivado publicable leen el CSV del repositorio (#192). Sin declararlo
// como entrada, editar el CSV deja a `test` en UP-TO-DATE y una rotura del derivado pasa
// en verde rancio en local; en CI corre fresco y muerde, pero el sintoma local mentiria.
tasks.test {
    inputs
        .file(rootProject.file("../docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
