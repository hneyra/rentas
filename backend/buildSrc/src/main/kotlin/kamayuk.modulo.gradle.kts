// Convenciones de un contexto acotado (ARQ-01 §3): capas dominio / aplicacion /
// infraestructura dentro del mismo modulo Gradle (ARQ-04 §1).
//
// El BOM de Spring Boot se importa para alinear versiones, pero NO se aplica el
// plugin de Spring Boot: solo kamayuk-rentas-aplicacion produce un artefacto ejecutable.
// La capa `dominio` no debe importar Spring; eso se verifica con analisis
// estatico, no con la ausencia de la dependencia.

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kamayuk.java-base")
    id("kamayuk.pruebas")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(platform(libs.findLibrary("spring-boot-bom").get()))
    "implementation"(project(":kamayuk-rentas-dominio-compartido"))

    // La capa `infraestructura` de todo contexto persiste con el mismo patron
    // (JdbcClient, paginacion con orden validado, la transaccion que emite el
    // SET LOCAL). Vive en kamayuk-rentas-plataforma, que declara `api` sobre
    // spring-boot-starter-jdbc: de ahi sale el JdbcClient sin declararlo aqui.
    //
    // Que la capa `dominio` no lo use es cosa del analisis estatico, no de la
    // ausencia de la dependencia: Gradle no distingue paquetes dentro de un
    // modulo, y ArchUnit si.
    "implementation"(project(":kamayuk-rentas-plataforma"))
}
