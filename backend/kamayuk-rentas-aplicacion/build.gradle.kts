// Ensambla el artefacto unico, desplegado en los perfiles web y batch (ADR-0003).
//
// Es tambien donde corren las verificaciones que necesitan ver todo el sistema a la
// vez: las reglas de ArchUnit de ARQ-04 §2 y los limites de modulo de Spring
// Modulith. Ningun otro modulo tiene en su classpath a todos los demas.

plugins {
    id("sgtm.java-base")
    id("sgtm.pruebas")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation(project(":kamayuk-rentas-dominio-compartido"))
    implementation(project(":kamayuk-rentas-plataforma"))

    // El panel de recaudacion (#56). No es un contexto acotado: agrega las APIs
    // publicas de cuentacorriente y tesoreria y no tiene tablas propias.
    implementation(project(":kamayuk-rentas-indicadores"))

    // Los doce contextos acotados de ARQ-01 §3.
    implementation(project(":kamayuk-rentas-contribuyentes"))
    implementation(project(":kamayuk-rentas-catastro"))
    implementation(project(":kamayuk-rentas-rentas"))
    implementation(project(":kamayuk-rentas-parametros"))
    implementation(project(":kamayuk-rentas-fiscalizacion"))
    implementation(project(":kamayuk-rentas-sanciones"))
    implementation(project(":kamayuk-rentas-cuentacorriente"))
    implementation(project(":kamayuk-rentas-tesoreria"))
    implementation(project(":kamayuk-rentas-valores"))
    implementation(project(":kamayuk-rentas-coactiva"))
    implementation(project(":kamayuk-rentas-licencias"))
    implementation(project(":kamayuk-rentas-seguridad"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.modulith:spring-modulith-starter-core")

    // Actuator entra por dos razones: la sonda de vida y las metricas (issue #156).
    // Sin un endpoint que diga si el proceso esta arriba Y llega a la base,
    // `depends_on: service_healthy` del compose no puede significar nada, y el
    // despliegue se queda esperando a un contenedor que quiza nunca sirva una
    // peticion. Se exponen `health` y `prometheus`, y nada mas (application.yaml,
    // SeguridadWeb).
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // El registro de Prometheus. Sin el, `/actuator/prometheus` no existe aunque
    // este en la lista de exposicion: Micrometer necesita SABER en que formato
    // escribir, y este es el que Prometheus sabe leer.
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Las migraciones viven en kamayuk-rentas-esquema y las ejecuta el proceso de despliegue
    // como sgtm_owner. La aplicacion NO migra al arrancar: se conecta como
    // sgtm_app, que no tiene DDL (ARQ-03 §4).
    runtimeOnly(libs.postgresql)

    // Las barreras, compartidas con los otros cuatro repositorios (composite build; ver
    // settings.gradle.kts). Trae ArchUnit consigo como `api`, junto con JUnit y AssertJ.
    testImplementation("kamayuk.comun:comun-verificaciones")

    // La muestra de caso de uso que viola la regla 10 lleva @Transactional: sin
    // spring-tx no compilaria, y sin ella la regla no tendria como demostrarse.
    testImplementation("org.springframework:spring-tx")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
}

// El contrato vive fuera de este modulo y dos pruebas lo leen del disco:
// `ContratoDeApiTest` compara sus rutas con las publicadas, y
// `ParametrosDeLaConsultaTest` compara sus parametros de consulta con lo que cada
// controlador lee. Sin declararlo como entrada, editar el YAML deja a `test` en
// UP-TO-DATE y una rotura del contrato pasa en **verde rancio** en local —en CI
// corre fresco y muerde, que es la peor forma de enterarse—. Es la leccion de
// #192 punto 2, aplicada al contrato: lo destapo #399 al mutar el YAML y ver la
// prueba dar BUILD SUCCESSFUL sin haber corrido.
tasks.test {
    inputs
        .file(rootProject.file("../docs/50-api/openapi/rentas-v1.yaml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Lo mismo para el archivo de formas de la respuesta, que `FormasDeLaApiTest`
    // compara contra lo que producen los controladores (#400): editarlo a mano sin
    // declararlo aqui dejaria la prueba en UP-TO-DATE y la edicion pasaria en verde.
    inputs
        .file(rootProject.file("../docs/50-api/formas-de-la-api.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Y para el censo de respuestas (#732), por lo mismo: lo compara
    // `RespuestasDeLaApiTest` contra lo que los controladores pueden contestar, y sin
    // declararlo aqui una edicion a mano dejaria la tarea UP-TO-DATE y pasaria en verde.
    inputs
        .file(rootProject.file("../docs/50-api/respuestas-de-la-api.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Y lo mismo para las pruebas de TODOS los modulos, que `AsercionesQueNoPuedenFallarTest`
    // lee del disco (#724). Es el unico escaner que recorre `src/test`, y esas fuentes no estan
    // en el classpath de este modulo —solo lo estan las de `src/main`, por las dependencias—,
    // asi que sin declararlas editar una prueba de otro modulo dejaria esta tarea en UP-TO-DATE
    // y una asercion que no puede fallar pasaria en verde rancio. Misma leccion de #192 punto 2.
    inputs
        .files(
            rootProject.layout.projectDirectory.asFileTree.matching {
                include("*/src/test/java/**/*.java")
            })
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Gradle no propaga las propiedades de sistema del build al proceso de prueba
    // (lo mismo que hace `sgtm.pruebas-postgres` con las suyas). Sin esto,
    // `-Dsgtm.formas.regenerar=true` no llega y el archivo no se puede regenerar.
    for (propiedad in listOf("sgtm.formas.regenerar", "sgtm.respuestas.regenerar")) {
        providers.systemProperty(propiedad).orNull?.let { systemProperty(propiedad, it) }
    }
}

// Nombre fijo del artefacto ejecutable. La imagen lo copia por nombre y no por
// comodin: `*.jar` casaria tambien con el `-plain.jar` que produce el plugin de
// java-library, y cual de los dos acaba en el contenedor dependeria del orden
// alfabetico.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("sgtm.jar")
}
