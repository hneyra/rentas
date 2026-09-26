// Viola: `verificaciones/la-moneda-se-quita-solo-en-el-formato.test.ts`. A PROPOSITO, seis formas.
//
// Compila, pasa el lint, y cada una escribe «1,842.60» bajo un rotulo «… S/» igual de bien que
// `formatearImporteEnColumna`. Es la forma exacta del defecto de #389: `LA_MONEDA = /^S\/\s/` mas un
// `.replace`, copiado en `inicio.ts` y en `fiscalizacion.ts`, y la tercera copia esperando al
// siguiente conector. Cada linea que la guarda tiene que senalar lleva la marca `VIOLA` al final.
//
// Debajo van cuatro formas BUENAS, que la guarda no puede senalar: sin ellas, una guarda que marcara
// cualquier «S/» del arbol —un rotulo, un javadoc— pasaria esta muestra igual.
//
// Nada de esto se ejecuta: son funciones exportadas que nadie llama. No vive en `muestras/`, que es
// de las prohibiciones de ESLint y exige que cada archivo tenga una que lo reclame
// (`reglas-de-eslint.test.ts`); es el mismo motivo por el que existe `muestra-de-rutas/`.

import {
  formatearImporte,
  formatearImporteEnColumna,
  formatearImporteSinRedondear,
} from '../../src/dominio/formato.ts';

/** (1) La de `inicio.ts` y `fiscalizacion.ts` hasta #389, letra a letra. */
const LA_MONEDA = /^S\/\s/; // VIOLA

export function lasSeisQueLaViolan(importe: string): readonly string[] {
  return [
    formatearImporte(importe).replace(LA_MONEDA, ''),
    // (2) La misma, sin constante por medio.
    formatearImporte(importe).replace(/^S\/ /, ''), // VIOLA
    // (3) Con una cadena en vez de una expresion regular.
    formatearImporte(importe).replace('S/ ', ''), // VIOLA
    // (4) Construyendo la expresion regular desde el texto.
    formatearImporte(importe).replace(new RegExp('^S/\\s'), ''), // VIOLA
    // (5) Partiendo por el simbolo y quedandose con lo de detras.
    formatearImporte(importe).split('S/ ').join(''), // VIOLA
    // (6) Sin nombrar el simbolo: saltandose sus tres caracteres.
    formatearImporteSinRedondear(importe).slice(3), // VIOLA
  ];
}

/** Las cuatro buenas: ninguna recorta nada. */
export function lasCuatroQueNo(importe: string): readonly string[] {
  const rotulo = { rotulo: 'Importe S/', alineadoDerecha: true };
  return [
    // La politica de la columna, que es de donde tiene que salir.
    formatearImporteEnColumna(importe),
    // El campo suelto, con su simbolo.
    formatearImporte(importe),
    // Un rotulo que DICE la moneda no la recorta de nada.
    rotulo.rotulo,
    // Un `.slice` sobre otra cosa que no es un importe formateado.
    importe.slice(1),
  ];
}
