import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Lo que las dos pruebas de tokens necesitan leer, leido UNA vez.
 *
 * `tokens-del-artboard.test.ts` compara los valores contra el artboard y
 * `contraste.test.ts` calcula sus ratios WCAG. Las dos parten del mismo archivo
 * de CSS, y si cada una lo interpretara a su manera podrian estar de acuerdo con
 * dos paletas distintas.
 *
 * No es un modulo de `src/`: no se empaqueta, no se importa desde la aplicacion
 * y existe solo para que las barreras midan lo mismo.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));

/** La raiz de `frontend/`. */
export const RAIZ = join(AQUI, '..');

/** El directorio de los cinco archivos de tokens. */
export const TOKENS = join(RAIZ, 'src/estilos/tokens');

/**
 * El artboard, vendorizado.
 *
 * Es la copia bajada del proyecto de Claude Design `SGTM Redesign`
 * (`c562dcb9-e2d5-4c46-b77d-7897b0f95989`), `sha256`
 * `ecc22b4b369b24ba7f2b7db6a64a39ef61e5f374ac013a57c06f5a3312817cb6`. Vive en el
 * repositorio y no en un directorio de trabajo porque **la comparacion tiene que
 * poder correr en la CI**: una prueba que solo pasa en la maquina donde alguien
 * bajo el archivo no verifica nada en un PR.
 *
 * Las once constantes que declara son **identicas en los doce artboards V6** —se
 * comprobo con `grep` sobre los doce antes de escribir los tokens—, asi que
 * comparar contra uno vale por los doce. Vendorizar los doce serian 1.6 MB para
 * repetir doce veces la misma afirmacion.
 */
export const ARTBOARD = join(RAIZ, 'diseno/RentasV6.dc.html');

export const leer = (ruta: string): string => readFileSync(ruta, 'utf8');

/**
 * El CSS sin sus comentarios.
 *
 * Los comentarios de este proyecto NOMBRAN tokens —«`--tinta-4` es la unica
 * que…»—, asi que un analizador que no los quite acabaria leyendo la prosa como
 * si fueran declaraciones. Se quitan antes de mirar nada.
 */
export const sinComentarios = (css: string): string => css.replace(/\/\*[\s\S]*?\*\//g, '');

/**
 * El contenido de un bloque `{ … }` que empieza en el selector dado.
 *
 * Cuenta llaves en vez de buscar el primer `}`, porque un bloque de tema envuelve
 * a otro: `@media (…) { :root:not(…) { … } }`.
 */
export function bloque(cssConComentarios: string, selector: string): string {
  const css = sinComentarios(cssConComentarios);
  const inicio = css.indexOf(selector);
  if (inicio === -1) {
    throw new Error(`No hay ningun bloque «${selector}» en el CSS.`);
  }

  let profundidad = 0;
  for (let i = css.indexOf('{', inicio); i < css.length; i += 1) {
    if (css[i] === '{') {
      profundidad += 1;
    } else if (css[i] === '}') {
      profundidad -= 1;
      if (profundidad === 0) {
        return css.slice(css.indexOf('{', inicio) + 1, i);
      }
    }
  }
  throw new Error(`El bloque «${selector}» no se cierra.`);
}

/** Las custom properties declaradas en un trozo de CSS, en orden de aparicion. */
export function propiedades(cssConComentarios: string): Map<string, string> {
  const css = sinComentarios(cssConComentarios);
  const declaradas = new Map<string, string>();
  const patron = /(--[a-z0-9-]+)\s*:\s*([^;]+);/gi;
  let encontrada = patron.exec(css);
  while (encontrada !== null) {
    const [, nombre, valor] = encontrada;
    if (nombre !== undefined && valor !== undefined) {
      declaradas.set(nombre, valor.trim());
    }
    encontrada = patron.exec(css);
  }
  return declaradas;
}

/** El `#rgb` de tres digitos, expandido a seis, y todo en minusculas. */
export function normalizar(hex: string): string {
  const limpio = hex.trim().toLowerCase();
  if (/^#[0-9a-f]{3}$/.test(limpio)) {
    const [, r, g, b] = limpio;
    return `#${r}${r}${g}${g}${b}${b}`;
  }
  return limpio;
}

/** Las tres paletas de `colors.css`: la clara y las dos declaraciones de la oscura. */
export function paletas(): {
  claro: Map<string, string>;
  oscuroPorPreferencia: Map<string, string>;
  oscuroPorAtributo: Map<string, string>;
} {
  const css = leer(join(TOKENS, 'colors.css'));
  return {
    claro: propiedades(bloque(css, ':root {')),
    oscuroPorPreferencia: propiedades(bloque(css, "@media (prefers-color-scheme: dark) {")),
    oscuroPorAtributo: propiedades(bloque(css, ":root[data-tema='oscuro'] {")),
  };
}

/** Las constantes con nombre del artboard: `const AZUL = '#005284';`. */
export function constantesDelArtboard(): Map<string, string> {
  const html = leer(ARTBOARD);
  const declaradas = new Map<string, string>();
  // Sin `$` al final: un artboard guardado con fin de linea de Windows dejaria un `\r`
  // delante y el ancla no casaria, de modo que la prueba no encontraria NINGUNA constante
  // y —si no fuera por la comprobacion de que son once— pasaria en verde sin comparar nada.
  const patron = /^const ([A-Z][A-Z0-9_]*) = '(#[0-9A-Fa-f]{3,6})';/gm;
  let encontrada = patron.exec(html);
  while (encontrada !== null) {
    const [, nombre, valor] = encontrada;
    if (nombre !== undefined && valor !== undefined) {
      declaradas.set(nombre, normalizar(valor));
    }
    encontrada = patron.exec(html);
  }
  return declaradas;
}

/**
 * El literal que sigue a `<marca>` en el artboard, con los corchetes contados.
 *
 * Sirve igual para `const PASOS = [` que para `cifras: [`, que es lo que hace falta: la mitad de
 * los datos del panel no son declaraciones con nombre — viven dentro de `renderVals()`, que es
 * donde el prototipo compone lo que dibuja.
 *
 * **Vivia dentro de `secciones-del-artboard.test.ts`** y subio aqui en F-6, cuando una segunda
 * prueba necesito leer el mismo artboard: dos analizadores del mismo archivo pueden divergir, y
 * el que divergiera compararia contra otra cosa sin decirlo.
 */
export function literalTras(html: string, marca: string): string {
  const inicio = html.indexOf(marca);
  if (inicio === -1) {
    throw new Error(`El artboard ya no declara «${marca}»: la referencia cambio.`);
  }
  const desde = inicio + marca.length;
  const abre = html[desde];
  if (abre !== '[' && abre !== '{') {
    throw new Error(`«${marca}» ya no abre un literal: abre con «${String(abre)}».`);
  }
  const cierra = abre === '[' ? ']' : '}';

  let profundidad = 0;
  for (let i = desde; i < html.length; i += 1) {
    if (html[i] === abre) {
      profundidad += 1;
    } else if (html[i] === cierra) {
      profundidad -= 1;
      if (profundidad === 0) {
        return html.slice(desde, i + 1);
      }
    }
  }
  throw new Error(`El literal «${marca}» no se cierra.`);
}

/** El literal de JavaScript del artboard, leido como dato. */
export function comoDato(literal: string): unknown {
  return JSON.parse(
    literal.replace(/'/g, '"').replace(/([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:/g, '$1"$2":'),
  );
}

/** El texto entre comillas simples que sigue a la marca: `colaTotal: '1,134 pendientes'`. */
export function textoTras(html: string, marca: string): string {
  const inicio = html.indexOf(marca);
  if (inicio === -1) {
    throw new Error(`El artboard ya no declara «${marca}».`);
  }
  const resto = html.slice(inicio + marca.length);
  const comillas = /^\s*'([^']*)'/.exec(resto);
  if (comillas === null) {
    throw new Error(`«${marca}» ya no va seguido de un texto entre comillas simples.`);
  }
  return comillas[1] ?? '';
}

/** Todos los colores hexadecimales que aparecen en el artboard, normalizados. */
export function coloresDelArtboard(): Set<string> {
  const html = leer(ARTBOARD);
  const encontrados = new Set<string>();
  for (const hex of html.match(/#[0-9A-Fa-f]{6}\b|#[0-9A-Fa-f]{3}\b/g) ?? []) {
    encontrados.add(normalizar(hex));
  }
  return encontrados;
}
