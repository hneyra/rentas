import { ARBOL } from '../pantallas/arbol.ts';
import { PANTALLAS } from '../pantallas/definiciones/index.ts';
import type { Modulo, Pantalla } from '../pantallas/tipos.ts';
import { NADA_SERVIDO, SERVIDO_Y_SIN_PEDIR, SOLO_BASE } from '../porQueNoHayDato.ts';

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
  for (const pantalla of Object.values(PANTALLAS) as readonly Pantalla[]) {
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

/** Las frases con que el sistema explica que no hay dato. */
function deLasAusencias(): readonly string[] {
  return [NADA_SERVIDO, SOLO_BASE, SERVIDO_Y_SIN_PEDIR].flatMap((a) => [
    a.enElCampo,
    a.explicacion,
  ]);
}

/** El catalogo entero, sin repetidos y en orden. */
export function catalogoDeClaves(): readonly string[] {
  const todas = new Set([...deLasPantallas(), ...delArbol(), ...deLasAusencias()]);
  return [...todas].filter((c) => c.trim() !== '').sort((a, b) => a.localeCompare(b, 'es'));
}
