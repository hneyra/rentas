// Convenciones comunes a todo modulo Java del backend.
// Aqui no se declara ninguna dependencia de framework: eso decide cada capa.

plugins {
    `java-library`
}

group = "kamayuk.rentas"
version = "0.1.0-SNAPSHOT"

// ADR-0001 fija Java 25. La propiedad permite construir en un entorno que
// todavia no lo tiene; CI usa siempre el valor por omision de gradle.properties.
val versionDeJava = providers.gradleProperty("kamayuk.java.version").getOrElse("25").toInt()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(versionDeJava))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// El javadoc se revisa AL COMPILAR, y no en una tarea aparte que nadie corre (#629). Hasta #629
// `./gradlew javadoc` salia rojo en trece modulos —65 errores: 43 encabezados `<h2>` dentro del
// javadoc de un metodo o un campo, que javadoc numera bajo el `<h3>` del miembro; 18 `{@link}` a
// clases que se fueron con catastro y caja o que el modulo no ve; dos tablas sin `<caption>` y dos
// `@param` de un record escritos en la clase de al lado— y no lo veia nadie, porque ni la CI ni
// `build` lo corren. Con doclint en `javac` el mismo analisis corre en cada compilacion, sin tarea
// nueva que recordar. `all/protected` mira lo que javadoc publica —lo publico y lo protegido—, y
// `-missing` deja fuera los comentarios que faltan. Solo `src/main`: las pruebas no publican
// javadoc.
//
// `-missing` se queda, y lo que falta se exige en otro sitio (#642): `ApiPublicaSinJavadocTest`,
// en `verificarArquitectura`, pide el comentario de todo tipo publico, de todo metodo publico de un
// caso de uso y de todo metodo de interfaz. Aqui no se puede, medido: el grupo `missing` trae con
// el comentario las etiquetas —7 601 avisos en `src/main`: 1 525 comentarios que faltan y 6 032
// `@param`/`@return`/`@throws` de comentarios que ya existen; los 1 448 de `./gradlew javadoc`
// eran catorce modulos parados en el tope de cien—; `-Xdoclint/package:` acota TODO doclint, asi
// que fuera de esos paquetes apagaria lo de arriba; y lo que falta son avisos, que solo paran la
// compilacion con `-Werror`, que haria fatal tambien cada aviso de `-Xlint:all`.
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add("-Xdoclint:all/protected,-missing")
}
