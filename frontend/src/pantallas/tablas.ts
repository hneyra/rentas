/**
 * **Donde vive lo que se elige en una tabla, y como se llama lo que el servidor contesto** (#186,
 * #187).
 *
 * <h2>Por que esto es un archivo y no cuatro literales repartidos</h2>
 *
 * Porque lo que el interprete de `@kamayuk/ui` ESCRIBE en la ruta —la pagina, el tamano, el campo
 * de orden y su sentido— lo tiene que LEER el conector para poder pedir, y son dos archivos
 * distintos: `pantallas/definiciones/*.ts` y `datos/conectores/*.ts`. Con el nombre escrito a mano
 * en los dos, el dia que uno cambie el mando seguiria moviendo la direccion y **nadie volveria a
 * pedir**: la tabla dibujaria la pagina 3 con las filas de la 0, en verde y sin un solo error.
 *
 * Es el mismo motivo por el que `catalogo.ts` DERIVA `enLaRuta.sujeto` del conector en vez de
 * llevar una lista paralela (#169), y por el que `seEscribe` sale de la definicion.
 *
 * <h2>El sitio de la ruta se llama COMO EL PARAMETRO del contrato, a proposito</h2>
 *
 * `#/aut-cat?pagina=2&ordenarPor=descripcion` viaja a `GET /licencias/ciiu?pagina=2&
 * ordenarPor=descripcion`. Un segundo vocabulario —`?p=2`, `?orden=`— obligaria a una tabla de
 * equivalencias que no protege de nada y que hay que leer dos veces para seguir una peticion desde
 * la barra de direcciones hasta la red.
 *
 * **La consecuencia es que una hoja pagina UNA tabla**: dos tablas paginadas en la misma hoja
 * compartirian el sitio `pagina` y se moverian juntas. No pasa hoy —lo comprueba
 * `verificaciones/la-ruta-de-la-hoja-llega-al-conector.test.ts`— y el dia que pase, lo que hay que
 * decidir es como se llama el sitio de la segunda, no descubrirlo en la pantalla.
 */

/**
 * Los cuatro sitios de la ruta que una tabla paginada y ordenada usa.
 *
 * Los cuatro son ademas parametros que el contrato publica para las operaciones que los reciben
 * —`docs/50-api/parametros-de-la-api.json`—, y eso lo comprueba una guarda: mandar uno que el
 * contrato no declare es construir sobre un nombre que nada de este repositorio puede verificar
 * (#26).
 *
 * **Y desde #236 son los MISMOS cuatro que el backend admite, no cuatro que se le parecen.** El
 * cuarto se llamaba `direccion` en los dos lados, que es la palabra con la que el dominio nombra un
 * domicilio: como `GuardiaDeParametros.DIALECTO_DE_LA_PAGINACION` los admite en TODA operacion,
 * toda pantalla con un filtro «Dirección» nacia rota —`GET /licencias/funcionamiento`,
 * `GET /autorizaciones/anuncios`—. El sentido se aparto, y el nombre al que se aparto no se
 * invento: `OrdenDeLaTabla.sentidoEnLaRuta`, de `@kamayuk/ui`, ya decia `sentido` desde el
 * principio, de modo que hasta #236 el interprete llamaba «sentido» a lo que escribia en un sitio
 * llamado «direccion».
 *
 * Que los dos lados sigan diciendo lo mismo lo cruza
 * `verificaciones/el-dialecto-de-la-paginacion-es-el-del-backend.test.ts`, contra
 * `_dialectoDeLaPaginacion` de `docs/50-api/parametros-de-la-api.json` —que sale de la constante
 * del guardia—. Renombrar uno de los dos a solas sale rojo.
 */
export const EN_LA_RUTA = {
  pagina: 'pagina',
  tamano: 'tamano',
  ordenarPor: 'ordenarPor',
  sentido: 'sentido',
} as const;

/**
 * **El nombre con que viaja el `hayMas` que el SERVIDOR dijo**, para la tabla `clave`.
 *
 * `DefinicionDeTabla.paginacion.hayMas` es el NOMBRE de un dato de `DatosDeLaPantalla.nombrados`,
 * no el dato: el interprete lo busca ahi (`TablaDelBloque.tsx`). Quien lo pone es el conector, con
 * el `hayMas` del envoltorio de la respuesta — **nunca contando las filas recibidas**: con el tope
 * alcanzado, contarlas diria que no hay mas justo cuando las hay.
 *
 * Derivado del nombre de la tabla y no escrito dos veces, por lo de arriba.
 */
export const hayMasDe = (tabla: string): string => `${tabla}.hayMas`;

/** Y el de cuantas paginas dijo que hay. Mismo trato: lo dice el servidor (`totalPaginas`). */
export const paginasDe = (tabla: string): string => `${tabla}.paginas`;
