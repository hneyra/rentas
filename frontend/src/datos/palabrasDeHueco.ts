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
 * **Lo que `territorio` no dibuja de UNA determinacion, y por que** (#237, #234, #252).
 *
 * El «Cronograma» es una tabla entera y no un campo: sin filas, el interprete dibuja la frase de
 * pantalla, y esa es generica. Esta dice el motivo exacto.
 *
 * <h2>La mitad que #252 le quita, y la que le deja</h2>
 *
 * Hasta aqui esta frase salia **siempre**, porque la hoja no repartia ni una cuota. Desde #252 la
 * tabla se dibuja, y entonces esta frase ya no puede decir «falta el conector»: **saldria mintiendo
 * justo sobre la pantalla que acaba de conectarse**, que es el defecto que #239 midio —una frase
 * que manda a arreglar algo que ya esta arreglado—.
 *
 * Lo que queda es el caso que ninguna conexion arregla: una fila **anterior a `V21`** no dice con
 * que modalidad se emitio, asi que sus vencimientos no se pueden resolver. Esa determinacion sale
 * con `modalidad: null` y `cuotas: []`, y esto es lo que se lee entonces. Es la unica situacion en
 * que la frase viaja.
 */
const SIN_CRONOGRAMA = `De esta determinacion no consta con que cronograma se emitio: su fila es \
anterior a la migracion que guarda la modalidad, asi que sus vencimientos no se pueden resolver y \
la tabla queda en blanco. No es que no tenga cuotas — es que no se sabe cuales fueron, y las \
trimestrales supuestas serian unos vencimientos que el contribuyente puede no haber recibido.`;

/**
 * **Lo que va donde una CORRIDA no sello el dato que el campo pide** (#312, D-02b).
 *
 * No es `NO_PUBLICADO` y no es `SIN_CIFRAR`, y las tres se leen distinto:
 *
 *   · `NO_PUBLICADO` dice «la operacion no trae ese campo» — el trabajo esta en el backend.
 *   · `SIN_CIFRAR` dice «lo trae y viene vacio porque no se ha calculado».
 *   · esta dice «lo trae, y **esa corrida** no lo guardo»: la fila es anterior a `V23`, o la
 *     corrida no determino a nadie y entonces no hubo conjunto sellado del que sacarlo. Ninguna
 *     conexion lo arregla y el backend no tiene nada que hacer — no hay nada cierto que escribir.
 *
 * **Y sobre todo no es un cero.** El derecho de emision de aquella corrida SE COBRO: esta sumado
 * dentro de `montoEmitido`, de donde no se puede volver a separar. Un `S/ 0.00` ahi afirmaria que
 * no se cobro, que en un sistema de recaudacion es la afirmacion mas cara que se puede hacer por
 * descuido.
 */
const NO_CONSTA_EN_LA_CORRIDA = 'no consta en la corrida';

/**
 * **Lo que va donde un campo sale de la ultima corrida y el ejercicio todavia no tiene ninguna**
 * (#354).
 *
 * `GET /rentas/predial/corridas/ultima` contesta **204** cuando no hay corrida ni simulacion del
 * ejercicio (#523), y eso es el estado normal de cualquier municipalidad entre el 1 de enero y su
 * primera corrida, o recien implantada. No es `NO_PUBLICADO` —la operacion SI publica el campo— ni
 * `NO_CONSTA_EN_LA_CORRIDA` —no hay corrida de la que no conste—, y sobre todo no es un cero: «cero
 * observados» es el resultado de una corrida limpia, y aqui no hay resultado todavia.
 */
const SIN_CORRIDA_DEL_EJERCICIO = 'todavia sin corrida';

/**
 * **Lo que va donde un campo es del internamiento VIGENTE y el ultimo de la placa ya salio** (#387).
 *
 * «Dias de custodia» de `tra-veh`. No es `NO_PUBLICADO` —la operacion SI publico el internamiento,
 * con sus dias— ni un cero: es que el vehiculo **no esta en el deposito**, y los dias que llegan son
 * los que estuvo la ultima vez, contados hasta su salida. Escribirlos con la fecha de hoy al lado,
 * que es lo que la ficha hacia, los leia como la custodia que corre ahora.
 */
const FUERA_DEL_DEPOSITO = 'no esta en el deposito';

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
  NO_CONSTA_EN_LA_CORRIDA,
  SIN_CORRIDA_DEL_EJERCICIO,
  SIN_CRONOGRAMA,
  SIN_PARAMETROS_DEL_SORTEO,
  FUERA_DEL_DEPOSITO,
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

export {
  NO_PUBLICADO,
  SIN_CIFRAR,
  NO_CONSTA_EN_LA_CORRIDA,
  SIN_CORRIDA_DEL_EJERCICIO,
  SIN_CRONOGRAMA,
  SIN_PARAMETROS_DEL_SORTEO,
  FUERA_DEL_DEPOSITO,
};
