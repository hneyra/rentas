import { existsSync, readFileSync } from 'node:fs';

import { ARTBOARDS, rutaDe, type Artboard } from './artboards.ts';

/**
 * **Lo que `RentasV8.dc.html` declara, leido como dato.**
 *
 * Lo usa `pantallas-del-artboard.test.ts` para comparar el codigo contra el artboard. Vive
 * aparte de la prueba por la misma razon por la que vive aparte `tokens.ts`: dos analizadores
 * del mismo archivo pueden divergir, y el que divergiera compararia contra otra cosa sin
 * decirlo.
 *
 * <h2>Aqui NO se lee ningun archivo al importar. Es el AC6 del issue</h2>
 *
 * Cuatro barreras de este directorio leen su artboard **en el cuerpo del modulo**, y el dia que
 * el archivo no este mueren durante la RECOLECCION con un `ENOENT`: sus `it` no llegan a
 * existir, el build sale rojo hablando de un fichero, y nadie puede saber cuantas afirmaciones
 * se perdieron. Lo cuenta entero `los-artboards-estan.test.ts`, que se escribio por eso.
 *
 * Asi que este modulo solo declara funciones. El disco se toca cuando alguien llama a
 * {@link artboardV8}, o sea **dentro de un `it`**, y si el archivo falta el rojo lo nombra —con
 * su procedencia, que es la pregunta que uno se hace justo despues de leer el mensaje—.
 *
 * <h2>Y no se interpreta con `eval`, ni con `JSON.parse` sobre una sustitucion</h2>
 *
 * El analizador de `tokens.ts` cambia `'` por `"` y entrecomilla las claves. Sirve para los
 * literales de V6 y **no sirve para estos**: `PANTALLAS` lleva comentarios dentro
 * —`/* ═══ Inicio ═══ *\/`— que `JSON.parse` no admite, y `ARBOL` nombra la constante `PROPIO`
 * en vez de repetir su texto. Un lector de verdad —cadenas, comentarios, identificadores— es
 * mas corto que las excepciones que harian falta, y falla diciendo en que posicion se perdio.
 */

/** El artboard V8, tal como lo declara la lista de artboards vendorizados. */
const DECLARADO: Artboard = (() => {
  const encontrado = ARTBOARDS.find((a) => a.archivo.endsWith('RentasV8.dc.html'));
  if (encontrado === undefined) {
    throw new Error(
      'El artboard V8 no esta declarado en `verificaciones/artboards.ts`. Sin esa entrada esta ' +
        'guarda no sabe contra que comparar, y `los-artboards-estan` no comprueba que exista.',
    );
  }
  return encontrado;
})();

/* ── El lector de literales de JavaScript ──────────────────────────────────────────────── */

/** Lo que un literal del artboard puede ser. */
export type ValorDelArtboard =
  | string
  | number
  | boolean
  | null
  | readonly ValorDelArtboard[]
  | { readonly [clave: string]: ValorDelArtboard };

/**
 * Lee el literal que empieza en `posicion`, y devuelve tambien donde acaba.
 *
 * Cuenta cadenas y comentarios en vez de contar corchetes a ciegas, que es lo que hace falta
 * aqui: las rutas del arbol llevan llaves —`/rentas/contribuyentes/{id}`— y un contador ingenuo
 * las tomaria por el principio de un objeto.
 */
function leerValor(
  texto: string,
  posicion: number,
  constantes: Readonly<Record<string, ValorDelArtboard>>,
): { valor: ValorDelArtboard; fin: number } {
  let i = posicion;

  const saltarHueco = (): void => {
    for (;;) {
      while (i < texto.length && /\s/.test(texto.charAt(i))) i += 1;
      if (texto.startsWith('/*', i)) {
        const cierre = texto.indexOf('*/', i + 2);
        i = cierre === -1 ? texto.length : cierre + 2;
        continue;
      }
      if (texto.startsWith('//', i)) {
        const salto = texto.indexOf('\n', i);
        i = salto === -1 ? texto.length : salto + 1;
        continue;
      }
      return;
    }
  };

  const leerCadena = (): string => {
    const comilla = texto.charAt(i);
    i += 1;
    let salida = '';
    while (i < texto.length && texto.charAt(i) !== comilla) {
      if (texto.charAt(i) === '\\') {
        salida += texto.charAt(i + 1);
        i += 2;
      } else {
        salida += texto.charAt(i);
        i += 1;
      }
    }
    if (i >= texto.length) throw new Error(`una cadena abierta en ${posicion} no se cierra`);
    i += 1;
    return salida;
  };

  const donde = (): string => JSON.stringify(texto.slice(i, i + 40));

  const valor = (): ValorDelArtboard => {
    saltarHueco();
    const caracter = texto.charAt(i);

    if (caracter === '[') {
      i += 1;
      const salida: ValorDelArtboard[] = [];
      for (;;) {
        saltarHueco();
        if (texto.charAt(i) === ']') {
          i += 1;
          return salida;
        }
        salida.push(valor());
        saltarHueco();
        if (texto.charAt(i) === ',') i += 1;
      }
    }

    if (caracter === '{') {
      i += 1;
      const salida: Record<string, ValorDelArtboard> = {};
      for (;;) {
        saltarHueco();
        if (texto.charAt(i) === '}') {
          i += 1;
          return salida;
        }
        let clave: string;
        if (texto.charAt(i) === "'" || texto.charAt(i) === '"') {
          clave = leerCadena();
        } else {
          const nombre = /^[A-Za-z_$][A-Za-z0-9_$]*/.exec(texto.slice(i));
          if (nombre === null) throw new Error(`clave ilegible en ${i}: ${donde()}`);
          clave = nombre[0];
          i += clave.length;
        }
        saltarHueco();
        if (texto.charAt(i) !== ':') throw new Error(`falta «:» tras «${clave}» en ${i}`);
        i += 1;
        salida[clave] = valor();
        saltarHueco();
        if (texto.charAt(i) === ',') i += 1;
      }
    }

    if (caracter === "'" || caracter === '"') return leerCadena();

    const numero = /^-?\d+(\.\d+)?/.exec(texto.slice(i));
    if (numero !== null) {
      i += numero[0].length;
      return Number(numero[0]);
    }

    const identificador = /^[A-Za-z_$][A-Za-z0-9_$]*/.exec(texto.slice(i));
    if (identificador !== null) {
      const nombre = identificador[0];
      i += nombre.length;
      if (nombre === 'true') return true;
      if (nombre === 'false') return false;
      if (nombre === 'null') return null;
      const resuelta = constantes[nombre];
      if (resuelta === undefined) {
        throw new Error(
          `el artboard nombra la constante «${nombre}» y esta guarda no sabe su valor. ` +
            'Anadela a las constantes que se resuelven antes de leer el literal.',
        );
      }
      return resuelta;
    }

    throw new Error(`no se puede leer el literal en ${i}: ${donde()}`);
  };

  const leido = valor();
  return { valor: leido, fin: i };
}

/** El valor de `const <nombre> = …` del artboard. */
function constanteDelArtboard(
  html: string,
  nombre: string,
  constantes: Readonly<Record<string, ValorDelArtboard>> = {},
): ValorDelArtboard {
  const marca = `const ${nombre} = `;
  const inicio = html.indexOf(marca);
  if (inicio === -1) {
    throw new Error(
      `El artboard ya no declara «const ${nombre}»: la referencia cambio. ` +
        'Esta guarda compara contra esa constante, asi que sin ella no compara nada.',
    );
  }
  return leerValor(html, inicio + marca.length, constantes).valor;
}

/* ── La forma posicional del artboard ──────────────────────────────────────────────────── */

/** `[verbo, ruta, nota]`. */
export type OperacionDelArtboard = readonly [string, string, string];
/** `[pieza, uso]`. */
export type PiezaDelArtboard = readonly [string, string];
/** `[clave, rotulo, operaciones, piezas]`. */
export type HojaDelArtboard = readonly [
  string,
  string,
  readonly OperacionDelArtboard[],
  readonly PiezaDelArtboard[],
];
/** `[rotulo, nota, clave, codigo, trazos, submodulos]`. */
export type ModuloDelArtboard = readonly [
  string,
  string,
  string,
  string,
  readonly string[],
  readonly HojaDelArtboard[],
];

/** `[etiqueta, tipo]` o `[etiqueta, tipo, opciones | ayuda]`. */
export type CampoDelArtboard =
  | readonly [string, string]
  | readonly [string, string, string | readonly string[]];

/** `{ t, c, f, n?, i?, a?, cn? }`. */
export interface TablaDelArtboard {
  readonly t: string;
  readonly c: readonly (readonly [string, number])[];
  readonly f: readonly (readonly string[])[];
  readonly n?: string;
  readonly i?: number;
  readonly a?: string;
  readonly cn?: string;
}

/** `[titulo, nota, campos]` o `[titulo, nota, campos, tabla]`. */
export type BloqueDelArtboard =
  | readonly [string, string, readonly CampoDelArtboard[]]
  | readonly [string, string, readonly CampoDelArtboard[], TablaDelArtboard];

/** Las tres constantes que esta guarda compara. */
export interface ArtboardV8 {
  readonly arbol: readonly ModuloDelArtboard[];
  readonly pantallas: Readonly<Record<string, readonly BloqueDelArtboard[]>>;
  readonly instrucciones: Readonly<Record<string, string>>;
}

let memoria: ArtboardV8 | undefined;

/**
 * El artboard V8, leido y memorizado.
 *
 * **Llamala desde dentro de un `it`, nunca desde el cuerpo del modulo.** Es lo unico que separa
 * «la guarda se pone roja diciendo que falta el artboard» de «las pruebas de la guarda dejan de
 * existir» (AC6).
 */
export function artboardV8(): ArtboardV8 {
  if (memoria !== undefined) return memoria;

  const ruta = rutaDe(DECLARADO);
  if (!existsSync(ruta)) {
    throw new Error(
      `FALTA EL ARTBOARD VENDORIZADO: ${DECLARADO.archivo}\n\n` +
        `  Que dibuja: ${DECLARADO.que}\n` +
        `  De donde se trae: ${DECLARADO.deDonde}\n\n` +
        '  Sin el no hay contra que comparar las cuarenta pantallas: esta guarda se pone roja\n' +
        '  NOMBRANDO el archivo en vez de morir durante la recoleccion con un ENOENT.',
    );
  }

  const html = readFileSync(ruta, 'utf8');
  const propio = constanteDelArtboard(html, 'PROPIO');
  memoria = {
    arbol: constanteDelArtboard(html, 'ARBOL', { PROPIO: propio }) as readonly ModuloDelArtboard[],
    pantallas: constanteDelArtboard(html, 'PANTALLAS') as Readonly<
      Record<string, readonly BloqueDelArtboard[]>
    >,
    instrucciones: constanteDelArtboard(html, 'INSTRUCCIONES') as Readonly<Record<string, string>>,
  };
  return memoria;
}

/** Las hojas del artboard, en el orden del arbol, aplanadas. */
export function hojasDelArtboard(): readonly HojaDelArtboard[] {
  return artboardV8().arbol.flatMap((modulo) => modulo[5]);
}
