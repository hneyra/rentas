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
 * (`ok`). `info` no sale de aqui: es para avisos de la pantalla, no para calificar una fila.
 *
 * Lo que NO se hace es enumerar los estados buenos: son muchos mas, cambian con cada modulo, y
 * una lista incompleta pintaria de rojo lo que simplemente no esta en ella. Se enumeran los dos
 * grupos que piden accion, y lo demas esta conforme.
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

/** Lo que ya ha ido mal: hay que actuar hoy. */
const MAL = /coactiva|observado|vencida|denegado/;
/** Lo que va a ir mal: hay plazo, pero corre. */
const ATENCION = /con deuda|por vencer|en tramite|en trámite/;

export function tonoDe(texto: string): TonoDeInsignia {
  const s = texto.toLowerCase();
  if (MAL.test(s)) return 'mal';
  if (ATENCION.test(s)) return 'atencion';
  return 'ok';
}
