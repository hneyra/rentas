// Backend de `rentas`. Un modulo de esquema y uno de verificaciones; los contextos acotados
// llegan en P5.
//
// Las barreras —ArchUnit, el escaner de fuentes, el de aserciones y la frontera de sistema— viven
// en `infrastructure/librerias-backend` y las comparten los cinco repositorios. Se consumen como
// *composite build* y no como artefacto publicado, y el motivo es el modo de fallo: un jar
// publicado a mano se queda viejo sin que nada se ponga rojo, y una verificacion vieja que pasa en
// verde es lo que este proyecto lleva doscientos issues evitando. Con `includeBuild`, Gradle la
// recompila desde el fuente en cada build: no puede quedarse vieja.
//
// LO QUE CUESTA, dicho aqui y no descubierto mas tarde: este backend NO COMPILA sin tener
// `infrastructure` clonado al lado.
val libreriasComunes = file("../../infrastructure/librerias-backend")
require(libreriasComunes.isDirectory) {
    "No esta ${libreriasComunes.canonicalPath}. El backend consume comun-verificaciones como" +
        " composite build, asi que `infrastructure` tiene que estar clonado al lado de" +
        " `rentas`: git clone https://github.com/hneyra/infrastructure ../../infrastructure"
}
includeBuild(libreriasComunes)

rootProject.name = "kamayuk-rentas-backend"

// El esquema: las migraciones, el proceso que las aplica y la prueba de aislamiento multi-tenant.
// Hoy no tiene ni una migracion: el baseline lo genera ADR-0032 y esta etapa no lo inventa.
include("kamayuk-esquema")

// Donde corren las barreras. Es el equivalente de `sgtm-aplicacion` en el monolito: el unico
// modulo que ve a todos los demas.
include("kamayuk-verificaciones")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}
