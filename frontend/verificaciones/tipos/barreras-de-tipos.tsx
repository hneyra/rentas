import { formatearImporte } from '../../src/dominio/formato.ts';
import { Importe, Insignia } from '../../src/ds/index.ts';
import type { ClaveDeHoja } from '../../src/pantallas/arbol.ts';
import type { Campo, Operacion, Pantalla } from '../../src/pantallas/tipos.ts';

/**
 * Las barreras que pone el COMPILADOR, y la prueba de que muerden.
 *
 * Este archivo no es una prueba de vitest: es una prueba de `tsc`, y se apoya en
 * una propiedad de `@ts-expect-error` que ninguna asercion tiene —
 * **`@ts-expect-error` falla cuando NO hay error**:
 *
 *     error TS2578: Unused '@ts-expect-error' directive.
 *
 * O sea que cada bloque de aqui abajo dice «esto tiene que no compilar», y si un
 * dia compila, `yarn typecheck` se pone rojo por eso mismo. Una prueba que
 * comprobara lo mismo con un `expect` no podria: para escribirla habria que
 * escribir primero el codigo que no compila, y entonces no compilaria la prueba.
 *
 * Va aparte de `src/` a proposito: aqui vive codigo que **esta mal escrito
 * queriendo**, y no tiene nada que hacer en el arbol que se empaqueta.
 */

/* eslint-disable no-restricted-syntax -- este archivo VIOLA las reglas a proposito; es lo que verifica */

/**
 * AC4, primera barrera: `<Importe>` sin `fechaCalculo` no compila.
 *
 * La segunda barrera es la prohibicion `importe-sin-fecha` de ESLint, con su
 * muestra en `verificaciones/muestras/importe-sin-fecha.tsx`. Las dos hacen
 * falta: esta sobrevive a que alguien apague la regla de ESLint, y aquella
 * sobrevive a que alguien le ponga un valor por omision al tipo.
 */
export const importeSinFecha = (
  // @ts-expect-error — falta `fechaCalculo`, que es obligatoria: no existe «la deuda», existe la deuda a una fecha (regla 9, RNF-075).
  <Importe valor="1842.60" />
);

/** Y no vale ponersela a `undefined` para callar al compilador. */
export const importeConFechaIndefinida = (
  // @ts-expect-error — `undefined` no es una `Fecha`.
  <Importe valor="1842.60" fechaCalculo={undefined} />
);

/**
 * Regla 1: un importe es texto, jamas `number`. En coma flotante `1842.6` ya ha
 * perdido la forma con la que llego, y `0.1 + 0.2` no es `0.30`.
 */
export const importeComoNumero = (
  // @ts-expect-error — `number` no es un `Importe`.
  <Importe valor={1842.6} fechaCalculo="2026-09-06" />
);

/** Lo mismo, una capa mas abajo: el formateador tampoco acepta un `number`. */
export function formatearUnNumero(): string {
  // @ts-expect-error — `formatearImporte` recibe texto decimal, no `number`.
  return formatearImporte(1842.6);
}

/**
 * AC5: una insignia sin texto no compila.
 *
 * Un estado que se comunica solo por color no se comunica a quien no distingue
 * ese color. `children` es obligatorio y no hay variante que pinte solo un punto.
 */
export const insigniaSinTexto = (
  // @ts-expect-error — falta el texto del estado; el color no es el unico canal.
  <Insignia tono="ok" />
);

/** Y el tono es uno de los cuatro del artboard, no una cadena cualquiera. */
export const insigniaConTonoInventado = (
  // @ts-expect-error — «verde» no es un `Tono`.
  <Insignia tono="verde">Vigente</Insignia>
);

/* ── UI-5 (#85), AC1: los tipos de las cuarenta pantallas son ESTRECHOS ─────────────────── */

/**
 * AC1: el tipo de un campo es la union de los siete —con y sin ancho completo—, no `string`.
 *
 * Sin la union, `['Ejercicio', 'select', […]]` compilaria y el interprete lo dibujaria como una
 * caja de texto vacia: no sabe hacer nada con un octavo tipo, y no se queja. Se descubriria
 * mirando la pantalla, que es justo lo que una definicion tipada existe para evitar.
 */
export const campoConTipoInventado: Campo = {
  etiqueta: 'Ejercicio',
  // @ts-expect-error — «select» no es uno de los siete tipos del artboard.
  tipo: 'select',
  opciones: ['2026'],
};

/** Y la marca de ancho completo es un `1`, no cualquier sufijo. */
export const campoConAnchoInventado: Campo = {
  etiqueta: 'Observaciones',
  // @ts-expect-error — «a2» no existe: el ancho completo se marca con un `1`.
  tipo: 'a2',
};

/**
 * AC1, la parte que no es la union: el **tercer elemento** significa una cosa distinta segun el
 * tipo, y cada rama lo exige por su nombre.
 *
 * Un desplegable sin opciones no es un desplegable: se dibuja vacio y no se puede elegir nada.
 */
// @ts-expect-error — falta `opciones`, que es lo unico que un desplegable dibuja.
export const desplegableSinOpciones: Campo = {
  etiqueta: 'Tipo de persona',
  tipo: 's',
};

/** Y al reves: un campo de texto no tiene opciones que ofrecer. */
export const textoConOpciones: Campo = {
  etiqueta: 'Número de documento',
  tipo: '',
  // @ts-expect-error — «opciones» es de los desplegables; un texto no las tiene.
  opciones: ['DNI', 'RUC'],
};

/** Un campo de solo lectura siempre muestra algo: sin `valor` no hay nada que leer. */
// @ts-expect-error — falta `valor`: es lo que el campo muestra.
export const soloLecturaSinValor: Campo = {
  etiqueta: 'Total',
  tipo: 'r',
};

/**
 * AC2: una pantalla cuya clave no sea una hoja del arbol no compila.
 *
 * Es la mitad que la guarda anti-deriva no tiene que comprobar con un `expect`: «cero pantallas
 * sin hoja» lo sostiene `Record<ClaveDeHoja, Pantalla>`, y `ClaveDeHoja` sale del propio `ARBOL`.
 */
export const pantallaHuerfana: Partial<Record<ClaveDeHoja, Pantalla>> = {
  // @ts-expect-error — «ini-panelito» no es ninguna de las cuarenta hojas del arbol.
  'ini-panelito': { instruccion: 'no lleva a ninguna parte', bloques: [] },
};

/** Y un verbo de operacion es uno de los cinco del arbol, `BASE` incluido. */
export const operacionConVerboInventado: Operacion = {
  // @ts-expect-error — «DELETE» no es un verbo del arbol; y no lo es por la regla 4: no se borra.
  verbo: 'DELETE',
  ruta: '/rentas/contribuyentes/{id}',
  nota: 'ContribuyenteController',
};
