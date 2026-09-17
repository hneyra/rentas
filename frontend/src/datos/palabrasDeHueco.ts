/**
 * **Las palabras con que un conector dice que a un dato le falta algo** (#246).
 *
 * <h2>Por que viven juntas, y por que el tipo sale de ellas y no al reves</h2>
 *
 * Las escribe un conector —que es **dato** y no tiene `t()` delante— y las traduce el interprete:
 * `ausenciaPorCampo` y `Reparto.loQueLaOperacionNoTrae` pasan por `traducir` igual que el rotulo
 * de un campo. O sea que son **claves**, y una clave que nadie lista nadie la echa de menos.
 *
 * Hasta #246 estaban desperdigadas —dos en `conectores.ts` y dos en `conectores/fiscalizacion.ts`—
 * y el inventario del locale las listaba **a mano**, una importacion por palabra. El agujero no es
 * teorico y esta medido: `SIN_PARAMETROS_DEL_SORTEO` llego en #196, nadie se acordo de venir al
 * catalogo, y **no estaba en el locale** — en un segundo idioma esa frase salia en castellano, en
 * verde y sin un solo rojo.
 *
 * Asi que aqui no hay una lista de palabras: hay **un saco del que sale el tipo**. `PalabraDeHueco`
 * es la union de lo que `PALABRAS_DE_HUECO` contiene, y `Reparto.noPublicados` y
 * `Reparto.loQueLaOperacionNoTrae` estan declarados con el. Consecuencia: una palabra nueva escrita
 * en un conector **no compila** hasta que entre en este saco, y en cuanto entra, el inventario del
 * locale la deriva sola. No hace falta acordarse de nada, que es la unica forma de que no se olvide.
 *
 * <h2>Lo que NO entra aqui</h2>
 *
 * **Lo que va en una CELDA de una tabla.** Una celda es dato de la fila y no pasa por `traducir`
 * —`conectores/seguridad.ts` lo dice de `SIN_RIESGO_PUBLICADO`, y `conectores/fiscalizacion.ts` de
 * las suyas—, asi que meterla aqui prometeria una traduccion que nadie pide y la haria salir en el
 * locale como clave sobrante. La linea no es «se parece a una ausencia»: es **por donde viaja**.
 */

/** La palabra del hueco cuando la operacion se pidio y no trae ese dato. */
const NO_PUBLICADO = 'no publicado';

/**
 * Lo que va donde la operacion publica el campo y lo contesta **vacio** (D-02a). Nunca `0.00`.
 *
 * Un cero donde no se ha calculado nada se lee como «no debe nada», que en un sistema de
 * recaudacion es la afirmacion mas cara que se puede hacer por descuido.
 */
const SIN_CIFRAR = 'sin cifrar';

/**
 * **Lo que `territorio` no dibuja, y por que** (#237, #234).
 *
 * El «Cronograma» es una tabla entera y no un campo: sin filas, el interprete dibuja la frase de
 * pantalla, y esa es generica. Esta dice el motivo exacto — que no es que a nadie se le ocurriera
 * publicarlo, sino que la fila guardada **no dice con que modalidad se emitio**.
 */
const SIN_CRONOGRAMA = `El cronograma de cuotas no se dibuja: la determinacion guardada no dice \
con que modalidad se emitio, y sin ella los vencimientos no se pueden resolver. Suponer la \
trimestral publicaria unas fechas de pago que el contribuyente puede no haber recibido.`;

/** «Detectados por cruce» de un programa que no declara sus parametros de sorteo (#196). */
const SIN_PARAMETROS_DEL_SORTEO = `El cruce no se pudo resolver: este programa no declara los \
parametros con que se sortea, y el embudo dice cual falta en «parametroQueFalta». No es cero — \
cero seria «el cruce no senalo a nadie», que es lo contrario de «el cruce no se pudo hacer».`;

/**
 * **El saco: de aqui salen el tipo y el inventario, y por eso no pueden separarse.**
 *
 * `i18n/catalogo-de-claves.ts` lo recorre con `Object.values`, asi que lo que este aqui esta en el
 * locale; y `PalabraDeHueco` sale de aqui, asi que lo que NO este aqui no compila. Las dos
 * direcciones a la vez son lo que cierra el agujero: ni sobra una clave que nadie dice, ni falta
 * una frase que alguien escribio.
 */
export const PALABRAS_DE_HUECO = {
  NO_PUBLICADO,
  SIN_CIFRAR,
  SIN_CRONOGRAMA,
  SIN_PARAMETROS_DEL_SORTEO,
} as const;

/**
 * Lo unico que un conector puede escribir en el hueco de un campo o como trozo que no trae.
 *
 * Es una union de literales y no `string` **a proposito**: con `string` una palabra nueva compila
 * tan campante y se queda fuera del locale, que es el defecto que #246 cierra.
 */
export type PalabraDeHueco = (typeof PALABRAS_DE_HUECO)[keyof typeof PALABRAS_DE_HUECO];

/** Lo que `ElTipoSujeta` afirma: recibir `false` es no compilar, que es justo lo que se quiere. */
type Afirmar<T extends true> = T;

/**
 * **El centinela del tipo, y viene de una rotura que salio VERDE** (#246).
 *
 * `PalabraDeHueco` sale de los VALORES del saco, asi que solo sujeta mientras cada uno conserve su
 * tipo literal. Y una frase larga escrita como `'a' + 'b'` **no lo conserva**: TypeScript tipa la
 * concatenacion como `string`, la union absorbe `string`, y a partir de ahi admite cualquier cosa.
 *
 * No es una precaucion: **es lo que paso al medir**. Con las dos frases largas escritas asi, la
 * rotura de prueba —una palabra de hueco inventada en `conectores/coactiva.ts`— compilo sin una
 * sola queja. La guarda estaba escrita y no sujetaba nada, que es peor que no tenerla, porque
 * ademas tranquiliza.
 *
 * Por eso las dos largas se escriben como plantilla con continuacion de linea —que conserva el
 * literal y no mete el salto de linea dentro— y por eso esto esta aqui: el dia que una vuelva a
 * perderlo, `Afirmar` recibe `false` y **este archivo no compila**, en vez de dejar el tipo abierto
 * en silencio.
 */
export type ElTipoSujeta = Afirmar<string extends PalabraDeHueco ? false : true>;

export { NO_PUBLICADO, SIN_CIFRAR, SIN_CRONOGRAMA, SIN_PARAMETROS_DEL_SORTEO };
