// Contexto acotado `rentas` (ARQ-01 §3.3).
//
// El padron vehicular (#26) y beneficios y exoneraciones (#27): registro puro, sin una
// sola cifra. La determinacion —predial, arbitrios, vehicular, alcabala— sigue bloqueada
// por D-02, y lo que falta no es la estructura sino los valores normativos.

plugins {
    id("kamayuk.modulo")
    id("kamayuk.pruebas-postgres")
}

dependencies {
    // Los dobles en memoria de los puertos de `catastro` (P5C). Sus tablas se fueron con `V6`:
    // lo que estas pruebas necesitaban de ellas era la premisa, y ahi es donde se escribe.
    testImplementation(testFixtures(project(":kamayuk-rentas-catastro")))
    testImplementation(testFixtures(project(":kamayuk-rentas-parametros")))
    // Dos dependencias a otro contexto acotado, y estan aqui para que se vean.
    //
    // Los valores referenciales de vehiculos son datos normativos: cuelgan de un conjunto sellado,
    // no de un ejercicio. Traducir «ejercicio» a «conjunto» es cosa de `parametros` —es quien sabe
    // que significa sellado y cual es la version vigente— y se importa solo su paquete raiz, que es
    // la API publica (ARQ-01 §4.1).
    //
    // La alternativa era resolver el conjunto en el SQL de rentas. Mas corto, invisible para
    // Modulith, y el dia que alguien olvide el `AND estado = 'SELLADO'` se lee un conjunto abierto
    // sin que nada falle.
    implementation(project(":kamayuk-rentas-parametros"))

    // La declaracion jurada (#28) referencia la version de ficha catastral vigente a su
    // fecha, para poder reconstruir el pasado (RNF-075). El mapa de contextos (ARQ-01 §2)
    // declara justo esta arista —catastro ──► rentas—, y solo se importa el paquete raiz
    // de catastro, que es su API publica: LectorDeFichas.
    implementation(project(":kamayuk-rentas-catastro"))

    // consulta_vehiculos (#25) necesita la deuda vigente de cada vehiculo. El mapa de contextos
    // (ARQ-01 §2) declara la arista cuentacorriente ──► rentas al reves de las otras dos: es
    // justo la excepcion que preve la regla 2 («cuentacorriente no conoce a nadie», ARQ-01 §4).
    // Solo se importa el paquete raiz de cuentacorriente, que es su API publica:
    // ConsultaDeDeudaPublica, y desde #25 tambien MovimientosDelLibro -los pagos y las
    // altas y bajas de la consulta unificada-.
    implementation(project(":kamayuk-rentas-cuentacorriente"))

    // consulta_unificada (#25, RF-046) es la ficha consolidada del contribuyente, y las
    // tres dependencias de abajo son las que la hacen posible SIN romper ARQ-01 §4 regla 2.
    //
    // La pantalla que mas datos agrega es de `cuentacorriente` -saldo, deudas, pagos,
    // altas y bajas-, pero no puede vivir alli: «cuentacorriente no conoce a nadie», y la
    // ficha necesita ademas los convenios y los valores. `rentas` es el unico de los
    // cuatro que puede depender de los otros tres sin cerrar ningun ciclo, y ademas es el
    // contexto DE la pantalla: «Consulta unificada predial-arbitrios».
    //
    // De cada uno se importa solo el paquete raiz, que es su API publica:
    //   - contribuyentes -> DirectorioDeContribuyentes, para resolver el codigo y poder
    //     responder 404 en vez de una ficha vacia sobre alguien que no existe.
    //   - tesoreria      -> ConveniosDelContribuyente, la pestaña «Fraccionamientos».
    //   - valores        -> ValoresDelContribuyente, la pestaña «Valores».
    implementation(project(":kamayuk-rentas-contribuyentes"))
    implementation(project(":kamayuk-rentas-tesoreria"))
    implementation(project(":kamayuk-rentas-valores"))

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

// La prueba del cuadro del predial lee el derivado publicable del corpus de `normativa`,
// el repositorio hermano (#395, como #192 en
// kamayuk-rentas-valores y kamayuk-rentas-coactiva). Sin declararlo como entrada, editar el CSV deja `test` en
// UP-TO-DATE y una rotura del derivado pasa en verde rancio en local.
tasks.test {
    inputs
        .file(rootProject.file("../../normativa/docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv"))
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Y el lote de hechos que publica `catastro` (C-8): `IngestionDeCatastroJdbcTest` lo lee para
    // medir el camino entero. Sin declararlo, cambiar la forma del evento —o el algoritmo de la
    // huella agregada— deja `test` en UP-TO-DATE y la rotura pasa en VERDE RANCIO en local.
    //
    // No se supone: se midio. Cambiando el separador de la huella agregada en `catastro` y
    // republicando el lote, esta tarea dijo BUILD SUCCESSFUL sin ejecutar una sola prueba. Es la
    // leccion de #192 punto 2, tercera vez en este proyecto.
    inputs
        .file(rootProject.file("../../catastro/docs/50-api/eventos/lote-de-eventos.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
