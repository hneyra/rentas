// Las barreras —ArchUnit, el escaner de fuentes, el de aserciones y la frontera de sistema— viven
// en `infrastructure/librerias-backend` y las comparten los cinco repositorios.
//
// Se consume como *composite build* y no como artefacto publicado, y el motivo es el modo de
// fallo: un jar publicado a mano se queda viejo sin que nada se ponga rojo, y una verificacion
// vieja que pasa en verde es exactamente lo que este proyecto lleva doscientos issues evitando
// (#192 §2, y el `verde rancio` de #399). Con `includeBuild`, Gradle recompila la libreria desde
// el fuente en cada build del backend: no puede quedarse vieja.
//
// LO QUE CUESTA, dicho aqui y no descubierto mas tarde: este backend YA NO COMPILA sin tener
// `infrastructure` clonado al lado. Es una dependencia nueva de la maquina de quien construye, y
// por eso se comprueba antes con un mensaje que dice que hacer, en vez de dejar que Gradle falle
// con «project directory does not exist».
val libreriasComunes = file("../../infrastructure/librerias-backend")

// LA UNICA SALIDA, Y SOLO PARA CONSTRUIR EL ARTEFACTO (C-7, punto 5).
//
// El `Dockerfile` construye con el contexto en la raiz de ESTE repositorio, y
// `infrastructure/librerias-backend` vive en un clon hermano: fuera del contexto, y sin forma de
// meterlo dentro —un `.dockerignore` no puede describir un contexto que es el directorio padre—.
// Asi que la imagen se paraba en el `require` de aqui. Estaba escrito como hueco desde P3 y no lo
// medía nadie, porque ninguno de los cuatro repositorios construye su imagen en CI todavia.
//
// Lo que se midio antes de decidir: `comun-verificaciones` es `testImplementation` y **solo** de
// `kamayuk-rentas-aplicacion`. La imagen construye `bootJar` e `installDist` y no corre ni una
// prueba, asi que no necesita la libreria para nada — lo unico que la necesitaba era este
// `require`.
//
// De ahi la propiedad: con ella el build se queda SIN las verificaciones, y para que eso no pueda
// convertirse en «verificar sin verificar» el `build.gradle.kts` de la raiz **hace fallar toda
// tarea de prueba** mientras este puesta. O sea: o esta la libreria, o no hay verificacion; nunca
// una verificacion que pasa en verde sin la libreria, que es el modo de fallo que el composite
// build existe para impedir (#192).
val soloElArtefacto = providers.gradleProperty("kamayuk.sinLibreriasComunes").isPresent

// LO QUE CUESTA, dicho aqui y no descubierto mas tarde: este backend NO COMPILA sus pruebas sin
// tener `infrastructure` clonado al lado.
require(libreriasComunes.isDirectory || soloElArtefacto) {
    "No esta ${libreriasComunes.canonicalPath}. El backend consume comun-verificaciones como" +
        " composite build, asi que `infrastructure` tiene que estar clonado al lado de" +
        " `rentas`: git clone https://github.com/hneyra/infrastructure ../../infrastructure"
}
if (!soloElArtefacto) {
    includeBuild(libreriasComunes)
}

rootProject.name = "kamayuk-rentas-backend"

// Compartido: objetos de valor y contexto de tenant. No depende de ningun
// contexto acotado (ARQ-01 §4 regla 6).
include("kamayuk-rentas-dominio-compartido")

// Esquema: migraciones Flyway y la prueba de aislamiento multi-tenant.
// No es un contexto acotado; es infraestructura de datos comun a todos.
include("kamayuk-rentas-esquema")

// Plataforma: lleva el contexto de tenant hasta la transaccion (ARQ-03 §2).
// Tampoco es un contexto acotado.
include("kamayuk-rentas-plataforma")

// Indicadores: el panel de recaudacion (#56, RF-130). Tampoco es un contexto
// acotado —ARQ-01 §3 fija doce y este no es el trece—: no tiene modelo, no tiene
// tablas y no decide nada. Agrega lo que cuentacorriente y tesoreria ya publican,
// y su build declara que solo puede ver esos dos.
include("kamayuk-rentas-indicadores")

// Los doce contextos acotados de ARQ-01 §3. Nacieron vacios —la estructura fijo
// los limites antes de que hubiera codigo que los cruzara— y hoy los doce tienen
// codigo de negocio; el estado por contexto esta en ARQ-01 §5.
include("kamayuk-rentas-contribuyentes")
include("kamayuk-rentas-catastro")
// El contexto acotado `rentas`. Se llama `nucleo` desde R-N (2026-09-05): el patron
// `kamayuk-<sistema>-<contexto>` producia `kamayuk-rentas-rentas` alli donde el contexto
// principal se llama igual que su sistema, y la direccion pidio quitar la repeticion. El
// patron queda intacto; lo que cambia es el nombre del contexto.
include("kamayuk-rentas-nucleo")
include("kamayuk-rentas-parametros")
include("kamayuk-rentas-fiscalizacion")
include("kamayuk-rentas-sanciones")
include("kamayuk-rentas-cuentacorriente")
include("kamayuk-rentas-tesoreria")
include("kamayuk-rentas-valores")
include("kamayuk-rentas-coactiva")
include("kamayuk-rentas-licencias")
include("kamayuk-rentas-seguridad")

// Ensambla el artefacto unico, en perfiles web y batch (ADR-0003).
include("kamayuk-rentas-aplicacion")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}
