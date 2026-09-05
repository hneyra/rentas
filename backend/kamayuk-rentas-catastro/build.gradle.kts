// El ADAPTADOR CLIENTE de `catastro`, y nada mas (P5C).
//
// Hasta P5C este era el contexto acotado `catastro` entero: doscientas clases, quince tablas y su
// dominio. `V6` retiro las tablas y el sistema del predio vive en el repositorio `catastro`
// (ADR-0029). Lo que queda aqui son los NUEVE PUERTOS del paquete raiz —que ya eran el contrato, y
// por eso las veintisiete clases de `src/main` que los consumen no cambiaron ni una linea— y el
// transporte que los implementa.
//
// Es la misma forma en que P5B dejo `kamayuk-rentas-parametros`: puertos y cliente, sin dominio y
// sin una sola consulta. Si este modulo volviera a tener un repositorio, `rentas` leeria tablas de
// `catastro` y el escaner de frontera lo diria.
//
// NO lleva `kamayuk.pruebas-postgres`: no tiene una sola consulta que probar.

plugins {
    id("kamayuk.modulo")
    `java-test-fixtures`
}

dependencies {
    // El cliente habla HTTP con la JDK; de Spring solo entran el estereotipo, `@Value` y el acceso
    // a la peticion en curso —de donde sale el token que se reenvia— y de Jackson el arbol JSON.
    // Ni un cliente HTTP de framework: ver el javadoc de `ClienteHttpDeNormativa`, que explica por
    // que hace falta el `String` crudo.
    implementation("org.springframework:spring-web")
    implementation("tools.jackson.core:jackson-databind")

    // Los dobles en memoria de los nueve puertos, para las pruebas de los otros modulos. Viven
    // aqui y no en cada uno porque son la misma premisa —«este predio tiene este titular»— y
    // repetirla en cuatro modulos es repetir la que un dia se corrige a medias.
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(project(":kamayuk-rentas-dominio-compartido"))
    testFixturesApi(project(":kamayuk-rentas-plataforma"))
    // El cuadro en memoria lanza , que es de : es el tipo que los
    // doce sitios que calculan ya saben cazar, y devolver otro dejaria a la prueba midiendo un
    // camino que en produccion no existe.
    testFixturesApi(project(":kamayuk-rentas-parametros"))
    //  lee las tablas  del escenario con JdbcClient. Es un
    // FIXTURE: el modulo de produccion no tiene ni una consulta, y eso lo comprueba el escaner de
    // frontera, que solo recorre .
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-jdbc")
}
