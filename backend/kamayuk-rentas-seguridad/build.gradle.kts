// Contexto acotado `seguridad` (ARQ-01 §3.12).
//
// Transversal: todos dependen de el y el de ninguno. La autenticacion vive
// fuera (ADR-0005); aqui esta la autorizacion, que es la del manual.
//
// De momento solo el camino de escritura de permisos que el issue #7 necesita
// para demostrar que un cambio de configuracion deja auditoria. El
// mantenimiento completo es de #9 y #12; el guardia que los comprueba, de #8.

plugins {
    id("sgtm.modulo")
    id("sgtm.pruebas-postgres")
}

dependencies {
    testImplementation(testFixtures(project(":kamayuk-rentas-esquema")))
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework:spring-aop")

    // MockMvc para el endpoint de la bitacora: transporte sin base de datos, que
    // es donde se ve que los filtros que el contrato declara son los que el
    // controlador lee (#544).
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly(libs.postgresql)
}

// RF-122: la lista de opciones tiene una sola fuente, el catalogo de NEG-03, que a
// su vez se genera del prototipo de interfaz. El build la copia a los recursos de
// este modulo para que la siembra de accesos la lea del jar.
//
// Copiarla en lugar de mantener una segunda lista es lo que hace cierta la promesa
// del manual: una opcion nueva en el catalogo aparece como acceso configurable en
// el siguiente arranque, sin que nadie tenga que acordarse de nada.
//
// La comprobacion de que el archivo existe NO es defensiva: un `Copy` cuya fuente
// no esta se salta con NO-SOURCE y el build sigue en verde, dejando un jar sin
// catalogo. Eso paso de verdad —la imagen de Docker se construia con el contexto
// en `backend/`, y el catalogo vive fuera— y el sintoma aparecio dos versiones
// despues: la aplicacion arrancaba, servia peticiones, y la implantacion no
// encontraba ninguna opcion que sembrar. Aqui falla el build, que es donde se ve.
//
// Va en una tarea aparte y no en un `doFirst` del `Copy` porque un `Copy` sin
// fuente se salta ENTERO —NO-SOURCE— y sus acciones no llegan a ejecutarse: el
// guardia se saltaria junto con lo que guarda.
val catalogo = "../docs/10-negocio/catalogo-de-opciones.md"
val archivoDelCatalogo = rootProject.layout.projectDirectory.file(catalogo).asFile

val exigirCatalogo = tasks.register("exigirCatalogoDeOpciones") {
    description = "Falla si el catalogo de opciones (NEG-03) no esta donde el build lo busca."
    val ruta = archivoDelCatalogo
    val donde = catalogo
    outputs.upToDateWhen { false }
    doLast {
        if (!ruta.exists()) {
            throw GradleException(
                "No esta $donde, y sin el el jar sale sin las 134 opciones: nadie" +
                    " puede dar permiso a ninguna pantalla, y la implantacion de una" +
                    " municipalidad no tiene accesos que sembrar. Si esto ocurre dentro" +
                    " de una imagen de Docker, el contexto de compilacion no incluye docs/.",
            )
        }
    }
}

val catalogoDeOpciones = tasks.register<Copy>("copiarCatalogoDeOpciones") {
    description = "Copia el catalogo de opciones (NEG-03) a los recursos de seguridad."
    dependsOn(exigirCatalogo)
    from(archivoDelCatalogo)
    into(layout.buildDirectory.dir("generated/recursos/seguridad"))
}

sourceSets {
    named("main") {
        resources.srcDir(catalogoDeOpciones.map { it.destinationDir.parentFile })
    }
}
