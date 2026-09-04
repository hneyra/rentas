// Contexto acotado `catastro` (ARQ-01 §3).
//
// Todavia sin funcionalidad de negocio —la primera esta bloqueada por D-01 y
// D-02—, pero ya con el catalogo vial: es el repositorio de ejemplo del patron
// de persistencia (issue #5), elegido porque no arrastra ninguna regla de
// calculo y si tiene municipalidad_id y politica RLS, que es lo que hay que
// demostrar.

plugins {
    id("sgtm.modulo")
    id("sgtm.pruebas-postgres")
}

dependencies {
    testImplementation(testFixtures(project(":kamayuk-rentas-parametros")))
    // Dos dependencias a otro contexto acotado, y las dos aqui para que se vean.
    //
    // La titularidad guarda un contribuyente_id y nada mas: el nombre, el codigo y el domicilio
    // viven en el padron. La consulta de fichas (RF-006) filtra por titular y el reporte de ficha
    // (RF-010) imprime sus datos, asi que uno de los dos tiene que preguntarle al otro.
    //
    // Se importa solo kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes —el paquete raiz, que
    // es la API publica (ARQ-01 §4.1)—; Spring Modulith rechaza cualquier import a .dominio o
    // .aplicacion del vecino. La alternativa era un JOIN a la tabla contribuyente desde el SQL de
    // catastro: mas rapido de escribir, invisible para Modulith, y roto en silencio el dia que el
    // padron cambie una columna.
    implementation(project(":kamayuk-rentas-contribuyentes"))

    // Las tablas de valuacion (#17) cuelgan de un conjunto de parametros sellado, no de un
    // ejercicio suelto: traducir «ejercicio» a «conjunto» es cosa de `parametros` —es quien sabe
    // que significa sellado y cual es la version vigente—, igual que ya hizo `rentas` para el valor
    // referencial vehicular (#141). Se importa solo su paquete raiz, la API publica.
    implementation(project(":kamayuk-rentas-parametros"))

    // La prueba del repositorio corre contra PostgreSQL de verdad: provisiona la
    // base como un ambiente real y se conecta como sgtm_app, no como el
    // superusuario que entrega Testcontainers (CAL-01 §3.2).
    testImplementation(testFixtures(project(":kamayuk-rentas-esquema")))
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")

    // El caso de uso se prueba envuelto en un proxy transaccional de verdad, para
    // que lo que se verifique sea la anotacion y no un TransactionTemplate escrito
    // por la propia prueba.
    testImplementation("org.springframework:spring-aop")

    // MockMvc para el endpoint: se prueba el transporte —forma del JSON, parametros,
    // traduccion de errores— sin base de datos. Lo que la base verifica ya tiene sus
    // pruebas aparte, y separarlas hace que cada fallo diga que se rompio.
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly(libs.postgresql)
}
