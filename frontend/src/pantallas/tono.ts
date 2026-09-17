/**
 * De que color va una insignia, deducido de lo que DICE la celda.
 *
 * <h2>Por que del texto y no de un campo aparte</h2>
 *
 * Porque es lo que hace el artboard —su metodo `tono(t)`— y porque la alternativa es peor: un
 * campo `tono` al lado de cada celda seria un dato que hay que mantener a mano en 113 filas, y el
 * dia que alguien escriba «Vencida» con el tono de «Conforme» la pantalla mentiria en verde.
 *
 * <h2>Y por que el reparto es asi</h2>
 *
 * Son las tres respuestas que una ventanilla necesita de un vistazo: **esto esta mal y hay que
 * actuar** (`mal`), **esto se va a poner mal si nadie lo toca** (`atencion`), y **esto esta bien**
 * (`ok`). Las tres son un JUICIO sobre la fila. La cuarta —`info`— no lo es, y por eso es la que
 * dice **«no se»**.
 *
 * <h2>El verde se GANA. Hasta #175 se regalaba</h2>
 *
 * Hasta #175 esto enumeraba solo los dos grupos que piden accion y **todo lo demas caia en `ok`**.
 * El argumento escrito era que enumerar los estados buenos pintaria de rojo lo que simplemente no
 * estuviera en la lista — y es cierto, pero la conclusion no se seguia: lo que no esta en ninguna
 * lista no es ni bueno ni malo, es **desconocido**, y el valor por omision de un semaforo no puede
 * ser el verde.
 *
 * Lo que se midio, con las respuestas interceptadas en Chromium:
 *
 * · **`ini-parado`** —la pantalla que enumera el trabajo parado— recibe en su columna de insignia
 *   `porQueCuestaDinero`, que es una **frase** y no un estado: «sin emitir no se pueden notificar
 *   ni cobrar, y prescriben». Ninguna regla la reconocia, asi que la interfaz pintaba **en verde,
 *   con la insignia de «conforme», trabajo que esta parado y cuesta dinero**.
 * · Y no era solo esa pantalla. De las **17** cadenas que el artboard escribe en una columna de
 *   insignia, **12** caian en el verde por omision, y entre ellas estaban «Alto» (el riesgo ITSE
 *   de un giro, que llega del backend en `aut-cat`), «Con diferencia», «Pendiente», «Programado»,
 *   «En deposito» y «Medio». Al enumerarlas, **solo 6 se ganan el verde**.
 *
 * Desde #175 hay **tres** listas y una salida: lo que ninguna reconoce sale con el tono de «no se»
 * ({@link TONO_SIN_RECONOCER}). Y sigue siendo cierto lo que decia el argumento viejo: una lista
 * de buenos incompleta no pinta de rojo nada — pinta de **neutro**, que es lo que de verdad sabe.
 *
 * <h2>Por que «no se» es `info` y no `atencion`</h2>
 *
 * Porque `atencion` afirma algo que nadie midio —«esto se va a poner mal si nadie lo toca»—, y
 * eso es inventar un estado igual que lo inventaba el verde, solo que hacia el otro lado. Con
 * `atencion` por omision, las 6 cadenas del artboard y las 4 frases de `ini-parado` saldrian en
 * ambar: un aviso que sale siempre deja de leerse, y con el se va el ambar que si decia algo.
 *
 * `info` es el unico de los cuatro tonos que **no es un juicio** sobre la fila, y por eso es el
 * unico que puede decir «no se». Y no se pierde nada al usarlo: `Insignia` exige su texto dentro
 * —`children` no es opcional, porque un estado que solo se comunica por color no se comunica a
 * quien no distingue ese color—, asi que el tono neutro deja de CALIFICAR el estado sin dejar de
 * ENSENARLO.
 *
 * <h2>Y por que se decide AQUI y no en la libreria</h2>
 *
 * Porque se miro antes de escribirlo (`kamayuk-lib`#87): `@kamayuk/ui` **no publica un tono
 * neutro** que este archivo pudiera heredar. Lo que publica son dos piezas parecidas y distintas,
 * y la diferencia importa:
 *
 * · **`DefinicionDeTabla.sinDato`** es la celda que llega `null` —una raya y su frase, nunca un
 *   hueco en blanco—. Aqui la celda **si trae valor**: lo que falta no es el dato, es saber que
 *   significa. Una celda vacia y un estado que no se reconoce no se dibujan igual.
 * · **`ReglaDeLaInsignia`** ya obliga a lo mismo que esto hace, por la otra via: sus casos llevan
 *   `otro` **obligatorio**, y su variante por dato lleva `siNoTrae`. O sea, la libreria no deja
 *   que el sistema se calle sobre lo que no encaja — le exige **nombrarlo**. `tonoDeLaInsignia` es
 *   la tercera via, la que Rentas usa, y hasta #175 era la unica de las tres que se callaba.
 *
 * Asi que no se inventa un concepto nuevo: se nombra el que la libreria ya exige nombrar.
 *
 * <h2>Lo que esto no arreglaba, y como se cerro: quitando la columna (#218)</h2>
 *
 * Hasta #218 la quinta columna de `ini-parado` recibia una frase donde el artboard dibuja un
 * estado («Vencida», «Por vencer»). Con el tono de «no se» la pantalla dejo de mentir, pero su
 * insignia no decia nada, y aqui quedaba escrito que lo que faltaba era que el backend publicara
 * el estado ([#183](https://github.com/hneyra/rentas/issues/183)).
 *
 * **#183 se midio y se cerro sin implementarlo**: los cuatro puertos de
 * `GET /indicadores/trabajo-parado` devuelven un agregado y **ninguno publica la antiguedad** de
 * lo que esta parado —devolver la lista para poder medirla es lo que prohibe el AC 4 de #56—, y de
 * las **nueve** filas `PLAZO` del corpus **ninguna** es el plazo que la administracion tiene para
 * desatascar ninguno de los cuatro frentes. O sea que no era una insignia que esperaba a alguien:
 * era una que **no se podia encender nunca**.
 *
 * Asi que lo que sobraba era la columna de insignia, y #218 la quito **en el artboard y en la
 * definicion a la vez**. La quinta columna sigue, y sigue diciendo `porQueCuestaDinero`. Este
 * reparto **no cambia**: deducir el estado de la frase, aqui o en el conector, seria
 * **inventarlo** — y es lo que prohibe la regla de `datos/conectores.ts`.
 */

import type { TonoDeInsignia } from '@kamayuk/ui';

/*
 * Los cuatro tonos salen de la libreria, **derivados de la pieza** y no copiados.
 *
 * Hasta #153 se derivaban aqui mismo —`ComponentProps<typeof Insignia>['tono']`— porque
 * `@kamayuk/ui` no publicaba el tipo suelto. Desde `kamayuk-lib`#27 lo publica, derivado de la
 * pieza de la misma forma, como `TonoDeInsignia`: es lo que el interprete le pide a este archivo.
 *
 * <h2>Y por que este archivo NO subio con el interprete</h2>
 *
 * Porque el reparto es vocabulario de ESTE sistema: «coactiva», «vencida», «con deuda». La
 * libreria lo recibe por `tonoDeLaInsignia`, que es obligatoria y sin valor por omision —uno que
 * pintara todo de `ok` dibujaria «Vencida» en verde sin que nada lo delatara—.
 */

/**
 * Lo que ya ha ido mal: hay que actuar hoy.
 *
 * **«Prescrito» entra con #230**, y es la primera palabra que se anade a esta lista desde #175.
 * Es un juicio y no un estado neutro: un ejercicio prescrito es deuda que la administracion **ya no
 * puede exigir** —el art. 43 del TUO del Codigo Tributario le quita la accion, no la obligacion—, y
 * la pantalla que la escribe, `val-tip`, existe para que eso se vea venir. Con el tono de «no se»
 * saldria del mismo color que «Vigente» en la columna de al lado, que es lo contrario de lo que
 * esta tabla tiene que decir de un vistazo.
 *
 * Va anclada por palabra como la lista de CONFORME: sin el ancla, `prescrit` casaria dentro de
 * cualquier palabra que lo contenga, y una lista de MALOS que se pasa de larga pinta de rojo lo que
 * esta bien.
 */
const MAL = /coactiva|observado|vencida|denegado|\bprescrit[ao]\b/;
/** Lo que va a ir mal: hay plazo, pero corre. */
const ATENCION = /con deuda|por vencer|en tramite|en trámite/;
/**
 * Lo que esta conforme. **Enumerado desde #175**, porque el verde hay que ganarlo.
 *
 * Las nueve salen del vocabulario que el artboard escribe en una columna de insignia y de lo que
 * los conectores ponen en ella: «Conforme», «Vigente» y «Activa» (el padron de licencias publica
 * `VIGENTE`), «Al dia», «Cancelada», «Pagado», «Inspeccionado» y «Bajo» (el riesgo ITSE que
 * `aut-cat` recibe). **«Emitida» NO esta**: emitir no es cobrar — un valor emitido y sin notificar
 * es justamente uno de los frentes que `ini-parado` cuenta, y darle verde seria volver al defecto.
 *
 * **Va anclada por palabra, y las otras dos no.** No es descuido: una lista de MALOS que se pase
 * de larga pinta de rojo lo que esta bien, y se ve; una de BUENOS que se pase de larga pinta de
 * verde lo que esta mal, y no se ve. Sin el ancla, `\bbajo\b` casaria dentro de «tra**bajo**».
 */
const CONFORME =
  /\bconforme\b|\bvigente\b|\bactiv[ao]\b|\bal d[ií]a\b|\bcancelad[ao]\b|\bpagad[ao]\b|\binspeccionad[ao]\b|\bbajo\b/;

/**
 * El tono de «no se»: ni conforme, ni alarma. Ver el javadoc del archivo.
 *
 * Se exporta porque es lo que comprueban la prueba de este reparto y la guarda que barre las 40
 * definiciones: una guarda que escribiera `'info'` a mano seguiria en verde el dia que este
 * archivo volviera a `'ok'` por otro camino.
 */
export const TONO_SIN_RECONOCER: TonoDeInsignia = 'info';

/**
 * Si alguna de las tres reglas reconoce el texto, o sea si su tono es un JUICIO y no un «no se».
 *
 * Es la mitad que la guarda de #175 necesita para poder decir algo mas fuerte que «no es verde»:
 * **ningun texto llega a `ok` sin que una regla lo nombre**.
 */
export function reconocido(texto: string): boolean {
  const s = texto.toLowerCase();
  return MAL.test(s) || ATENCION.test(s) || CONFORME.test(s);
}

export function tonoDe(texto: string): TonoDeInsignia {
  const s = texto.toLowerCase();
  if (MAL.test(s)) return 'mal';
  if (ATENCION.test(s)) return 'atencion';
  if (CONFORME.test(s)) return 'ok';
  return TONO_SIN_RECONOCER;
}
