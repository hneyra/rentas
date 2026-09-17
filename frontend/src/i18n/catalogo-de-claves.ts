import type { DefinicionDePantalla } from '@kamayuk/ui';

import { ARBOL } from '../pantallas/arbol.ts';
import { PANTALLAS } from '../pantallas/definiciones/index.ts';
import type { Modulo } from '../pantallas/tipos.ts';
import { NO_PUBLICADO } from '../datos/conectores.ts';
import {
  CARGANDO,
  NO_PUBLICADO_EN_PANTALLA,
  SIN_EJERCICIO,
  SIN_SUJETO,
  VACIO,
} from '../datos/useDatosDeLaHoja.ts';
import { SIN_CIFRAR } from '../datos/conectores/fiscalizacion.ts';
import { SIN_PLACA } from '../datos/conectores/transito.ts';
import { NADA_SERVIDO, SERVIDO_Y_SIN_PEDIR, SOLO_BASE, SOLO_ESCRIBE } from '../porQueNoHayDato.ts';
import { clavesDelMarco } from './textosDelMarco.ts';

/**
 * **Todas las cadenas traducibles del sistema, sacadas de donde estan** (#103).
 *
 * <h2>Por que esto existe, y por que `i18next-cli` no basta</h2>
 *
 * `i18next-cli` extrae lo que encuentra escrito como una llamada con la frase dentro. Medido:
 * encuentra **12**. Las otras **747 no estan escritas asi y no pueden estarlo**: viven en las
 * definiciones de las 40 pantallas y en el arbol, y el interprete las traduce con
 * `t(campo.etiqueta)` — una variable, que ninguna extraccion estatica puede seguir.
 *
 * **Y ojo con los ejemplos en los comentarios.** Este parrafo decia la llamada con una frase
 * literal dentro, a modo de ejemplo, y el extractor **la cogio como clave de verdad**: `status`
 * salio rojo con «✗ una frase (absent)» sobre un codigo perfecto. No distingue un ejemplo de una
 * llamada, asi que aqui no se escriben ejemplos con la frase dentro.
 *
 * No es un defecto de la herramienta ni de la forma: es la consecuencia de que **las pantallas
 * sean dato**, que es justo lo que hace posible la guarda anti-deriva. Se paga aqui.
 *
 * Asi que el catalogo se DERIVA del dato en vez de extraerse del codigo. La ventaja es que no
 * puede quedarse corto: una pantalla nueva trae sus cadenas sin que nadie se acuerde de nada.
 *
 * <h2>Y desde #133 tambien lo que dice el MARCO</h2>
 *
 * Las treinta y dos palabras de `@kamayuk/shell` y la marca de opcional de `@kamayuk/ui` entran
 * por `textosDelMarco.ts`, y entran **derivadas** por el mismo motivo que las 747: escritas dentro
 * de cada `t()` habria que acordarse de listarlas a mano en el inventario del locale, y un olvido
 * ahi no produce ningun rojo — nadie echa de menos lo que nadie listo.
 *
 * <h2>Lo que NO entra</h2>
 *
 * **Los valores de los campos de solo lectura y las filas de las tablas** — ya no existen aqui
 * (#97), y no se traducirian aunque existieran: un importe no tiene traduccion.
 */

/** Todo lo que las 40 pantallas dicen. */
function deLasPantallas(): readonly string[] {
  const salida: string[] = [];
  // Anotado: `PANTALLAS` es un `as const satisfies` de cuarenta formas distintas, y sin la
  // anotacion el compilador intenta unificar cuarenta y se rinde. La forma comun la da el
  // `satisfies`, que es lo que garantiza que la anotacion no miente.
  for (const pantalla of Object.values(PANTALLAS) as readonly DefinicionDePantalla[]) {
    salida.push(pantalla.instruccion);
    for (const bloque of pantalla.bloques) {
      salida.push(bloque.titulo);
      if (bloque.nota !== '') salida.push(bloque.nota);
      for (const campo of bloque.campos) {
        salida.push(campo.etiqueta);
        if ('opciones' in campo) salida.push(...campo.opciones);
        if ('casilla' in campo) salida.push(campo.casilla);
        if ('ayuda' in campo && campo.ayuda !== undefined) salida.push(campo.ayuda);
      }
      const tabla = bloque.tabla;
      if (tabla === undefined) continue;
      salida.push(tabla.titulo, ...tabla.columnas.map((c) => c.rotulo));
      if (tabla.nota !== undefined) salida.push(tabla.nota);
      if (tabla.accion !== undefined) salida.push(tabla.accion);
      // La palabra de una celda sin dato y su motivo son TEXTO de la definicion —el interprete los
      // pasa por `traducir`—, asi que entran en el inventario como el titulo o la nota
      // (`kamayuk-lib`#87, #180). Sin esta linea se irian al DOM en castellano en cualquier idioma,
      // y el locale no lo echaria de menos: lo que nadie lista, nadie lo reclama.
      if (tabla.sinDato !== undefined) {
        salida.push(tabla.sinDato.texto);
        if (tabla.sinDato.nota !== undefined) salida.push(tabla.sinDato.nota);
      }
      // Los rotulos de los campos por los que se ordena son FRASES —el interprete los pasa por
      // `resolverTexto`, o sea por `traducir`— y salen en el desplegable de la barra de la tabla
      // (#186). Lo que NO entra es `campo.valor`: eso viaja al servidor, y cambiar de idioma no
      // puede cambiar lo que se pide.
      if (tabla.orden !== undefined) {
        salida.push(...tabla.orden.campos.map((campo) => campo.rotulo));
      }
    }
  }
  return salida;
}

/** Los rotulos del arbol: diez modulos con su nota, y cuarenta hojas. */
function delArbol(): readonly string[] {
  return (ARBOL as readonly Modulo[]).flatMap((modulo) => [
    modulo.rotulo,
    modulo.nota,
    ...modulo.hojas.map((hoja) => hoja.rotulo),
  ]);
}

/**
 * **Las frases con que el sistema explica que no hay dato.**
 *
 * <h2>Son de DOS sitios, y el segundo faltaba (#215)</h2>
 *
 * Las cuatro de `porQueNoHayDato.ts` son de una pantalla **sin conector**. Las otras cinco son de
 * `useDatosDeLaHoja.ts` y son las de una pantalla **que si pide**: cargando, fallo, vacio, falta el
 * sujeto, falta el ejercicio y «no publicado». El interprete las pasa por `traducir` igual que a
 * las primeras, o sea que son claves — y no estaban listadas, asi que en un segundo idioma **la
 * mitad conectada de la interfaz salia en castellano**. El defecto no tenia rojo porque lo que
 * nadie lista tampoco nadie lo echa de menos, que es justo lo que este archivo existe para impedir.
 *
 * Y con ellas las dos palabras que un conector pone **en el hueco de un campo** —`NO_PUBLICADO` y
 * `SIN_CIFRAR`—, que viajan por `ausenciaPorCampo` y el interprete tambien traduce. **No entran las
 * de una CELDA de tabla**: esas son dato de la fila y no pasan por `traducir`.
 */
function deLasAusencias(): readonly string[] {
  return [
    ...[NADA_SERVIDO, SOLO_BASE, SOLO_ESCRIBE, SERVIDO_Y_SIN_PEDIR].flatMap((a) => [
      a.enElCampo,
      a.explicacion,
    ]),
    ...[CARGANDO, VACIO, SIN_SUJETO, SIN_EJERCICIO, NO_PUBLICADO_EN_PANTALLA, SIN_PLACA].flatMap(
      (a) => [a.enElCampo, a.explicacion],
    ),
    NO_PUBLICADO,
    SIN_CIFRAR,
  ];
}

/** El catalogo entero, sin repetidos y en orden. */
export function catalogoDeClaves(): readonly string[] {
  const todas = new Set([
    ...deLasPantallas(),
    ...delArbol(),
    ...deLasAusencias(),
    ...clavesDelMarco(),
  ]);
  return [...todas].filter((c) => c.trim() !== '').sort((a, b) => a.localeCompare(b, 'es'));
}
