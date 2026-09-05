// Contexto acotado `parametros` (ARQ-01 §3.4), y desde P5B el CLIENTE de `normativa`.
//
// Los demas contextos siguen leyendo solo de aqui, y por la misma interfaz de siempre
// (`LectorDeParametros`). Lo que cambio es de donde salen los valores: ya no de una tabla
// de esta base -las seis se fueron en `V2`- sino de la copia local de un conjunto SELLADO,
// descargada una vez de `normativa` y verificada por su sha256 (ADR-0025 §1).
//
// Aqui NO se escribe ningun valor normativo: publicar es un acto administrativo con doble
// verificacion y ocurre en `normativa`, con su propio rol y su propia base.

plugins {
    id("kamayuk.modulo")
    id("kamayuk.pruebas-postgres")
    `java-test-fixtures`
}

dependencies {
    // Jackson: la respuesta de `normativa` se lee AQUI, del cuerpo en bruto, porque el ETag es el
    // sha256 de esos bytes y un cliente que deserialice por su cuenta entrega un objeto y no los
    // bytes con que se calculo la huella.
    implementation("tools.jackson.core:jackson-databind")

    // Los fixtures publican el escenario de `normativa` que veinte clases de prueba de otros
    // modulos usan para sembrar su premisa. Necesitan ver la plataforma -JdbcClient, Auditoria- y
    // el esquema, igual que las pruebas.
    testFixturesApi(project(":kamayuk-rentas-plataforma"))
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi("org.springframework.boot:spring-boot-starter-jdbc")

    testImplementation(testFixtures(project(":kamayuk-rentas-esquema")))
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework:spring-aop")

    // MockMvc para la lectura de #605: transporte y guardia sin base de datos. Lo que se
    // mide aqui no es la consulta —eso va contra PostgreSQL— sino que el ejercicio viaje
    // por la ruta, que fuera de rango salga 422 y no 500, y que el guardia real deje pasar
    // al que no tiene ningun permiso del catalogo.
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly(libs.postgresql)
}

// Las entradas del corpus normativo se fueron con el a `normativa` (P5B, ADR-0025 §5): las
// pruebas del derivado publicable y del manifiesto de cuadros ya no estan en este modulo, y
// declarar aqui archivos que este repositorio no tiene dejaria la tarea sin poder ejecutarse
// —«An input file was expected to be present but it doesn't exist»—.
