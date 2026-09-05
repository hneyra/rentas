// Contexto acotado `contribuyentes` (ARQ-01 §3.1).
//
// El primero de la onda de negocio: no referencia a ningun otro contexto y todos
// lo referencian a el. Su codigo unico es lo que enlaza predios, vehiculos,
// papeletas, licencias y la cuenta corriente.
//
// No depende de D-02: el padron identifica al sujeto, no calcula nada.

plugins {
    id("kamayuk.modulo")
    id("kamayuk.pruebas-postgres")
}

dependencies {
    testImplementation(testFixtures(project(":kamayuk-rentas-parametros")))
    // La busqueda por aproximacion se resuelve con pg_trgm y un indice GIN, asi que
    // solo se puede probar contra PostgreSQL de verdad. Una prueba con dobles diria
    // que el metodo se llamo, no que encuentra un nombre mal escrito.
    testImplementation(testFixtures(project(":kamayuk-rentas-esquema")))
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework:spring-aop")
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly(libs.postgresql)
}
