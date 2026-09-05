// kamayuk-rentas-plataforma no es un contexto acotado: es la infraestructura tecnica que
// lleva el contexto de tenant desde TenantContext hasta la transaccion de base de
// datos (ARQ-03 §2). Ningun contexto de negocio la llama; la usa Spring.
//
// Vive fuera de kamayuk-rentas-aplicacion porque ese modulo ensambla y nada mas (ADR-0003),
// y fuera de kamayuk-rentas-dominio-compartido porque depende de Spring y el dominio no
// puede depender de Spring.

plugins {
    id("kamayuk.java-base")
    id("kamayuk.pruebas-postgres")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":kamayuk-rentas-dominio-compartido"))

    // spring-boot-starter-jdbc trae spring-jdbc y HikariCP. El pool es parte del
    // contrato aqui: la verificacion al devolver la conexion necesita poder
    // descartarla, no solo cerrarla.
    api("org.springframework.boot:spring-boot-starter-jdbc")

    // El filtro lee el claim del token ya validado (ADR-0005). Configurar el emisor
    // y el JWKS es trabajo de la iteracion de identidad; aqui solo se necesita el
    // tipo Jwt.
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // La capa web comun —contrato, errores, paginacion— vive aqui, en
    // kamayuk.rentas.web, y la usan los controladores de los doce contextos: por eso
    // starter-web es `api` y no `implementation`. Trae ademas la servlet API que
    // necesita TenantContextFilter.
    //
    // El perfil batch no atiende HTTP, pero es el mismo artefacto (ADR-0003), asi
    // que las clases estan igualmente y no arrancan ningun servidor por existir.
    api("org.springframework.boot:spring-boot-starter-web")

    testImplementation(testFixtures(project(":kamayuk-rentas-esquema")))

    // Solo para la prueba de la cadena de identidad: verifica que /actuator/health
    // y /actuator/prometheus siguen siendo lo unico publico (issue #156). El
    // actuator de produccion lo trae kamayuk-rentas-aplicacion, que es quien lo despliega.
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    // Sin el registro, el endpoint "prometheus" no se activa —404, no 200 vacio—
    // y la prueba de arriba estaria comprobando el camino equivocado.
    testImplementation("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly(libs.postgresql)
}
