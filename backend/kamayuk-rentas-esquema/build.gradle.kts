// kamayuk-rentas-esquema no es un contexto acotado: contiene las migraciones Flyway, el
// proceso que las aplica y la prueba de aislamiento multi-tenant, que es bloqueante.
//
// Deliberadamente NO depende de Spring. La prueba verifica el motor de base de
// datos, no la aplicacion; levantar un contexto de Spring solo agregaria formas
// de que pase en verde por el motivo equivocado. El migrador tampoco lo necesita:
// es Flyway, un driver y una comprobacion del ambiente.
//
// El arranque de la base (roles + migraciones) se publica como test fixtures: lo
// reutiliza kamayuk-rentas-plataforma para su prueba con el pool real. Un segundo arranque
// copiado seria un segundo sitio donde olvidar que el rol no puede ser superusuario.

plugins {
    id("kamayuk.java-base")
    id("kamayuk.pruebas-postgres")
    `java-test-fixtures`
    application
}

dependencies {
    // Flyway va en el codigo de produccion y no solo en los fixtures porque el
    // despliegue migra con ESTE codigo. Si el contenedor de migracion trajera su
    // propia version, lo verificado en CI y lo desplegado en la municipalidad
    // dejarian de ser lo mismo sin que nada lo dijera.
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    // El BOM va en `api` y no en `implementation`: quien consuma los fixtures
    // recibe testcontainers en su classpath y necesita tambien sus versiones.
    testFixturesApi(platform(libs.testcontainers.bom))
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesRuntimeOnly(libs.postgresql)

    testRuntimeOnly(libs.postgresql)
}

// La distribucion del migrador: `installDist` deja bin/ y lib/ listos para copiar
// a la imagen. No es un fat jar a proposito —el classpath explicito hace visible
// que ahi dentro solo hay Flyway y un driver— ni un artefacto de Spring Boot: este
// modulo no conoce Spring y no va a empezar por el proceso de despliegue.
application {
    mainClass.set("kamayuk.rentas.esquema.Migrador")
    applicationName = "migrar"
}

tasks.test {
    // Sin esto, un fallo de aislamiento podria quedar oculto por el cache de Gradle
    // cuando cambia solo el motor de base de datos y no las fuentes.
    outputs.upToDateWhen { false }
}
