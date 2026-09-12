import { readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

import { compile } from 'tailwindcss';

/**
 * Compilar la hoja de `@kamayuk/ui` **con Tailwind de verdad**, y leer lo que sale.
 *
 * Vive aparte de la prueba por lo de siempre: quien lo usa son dos —la guarda y, el dia que se
 * conecte a la aplicacion, quien lo conecte— y escribirlo dos veces lo desincroniza.
 */

const requerir = createRequire(import.meta.url);

/** La raiz de `@kamayuk/ui`, alcanzada POR EL ENLACE y no por una ruta al clon hermano. */
export const RAIZ_DE_UI = dirname(requerir.resolve('@kamayuk/ui'));

/**
 * El CSS que Tailwind emite para la lista de clases que se le den.
 *
 * `loadStylesheet` hace falta porque la hoja empieza con `@import "tailwindcss"`, y el compilador
 * no sabe resolver ese nombre por si solo: aqui se le dice que es el `index.css` del paquete.
 */
export async function compilar(clases: readonly string[]): Promise<string> {
  const hoja = readFileSync(join(RAIZ_DE_UI, 'estilos', 'estilos.css'), 'utf8');
  const compilado = await compile(hoja, {
    base: RAIZ_DE_UI,
    loadStylesheet: (id: string, desde: string) => {
      const ruta = id === 'tailwindcss' ? requerir.resolve('tailwindcss/index.css') : join(desde, id);
      return Promise.resolve({
        path: ruta,
        base: dirname(ruta),
        content: readFileSync(ruta, 'utf8'),
      });
    },
  });
  return compilado.build([...clases]);
}

/**
 * Las clases que un archivo usa, sacadas de sus `className` **literales**.
 *
 * Solo literales: una expresion —`className={activo ? 'a' : 'b'}`— se lee igual porque las dos
 * ramas son cadenas, pero una interpolacion no se puede resolver sin ejecutar el componente, y
 * adivinarla daria falsos rojos sobre clases que nadie escribio.
 *
 * Se descartan los tokens que no son utilidades: los que llevan un espacio dentro no llegan aqui
 * —se parte por espacios— pero si llegan cosas como `100%` de un `style`, asi que se exige la
 * forma de una utilidad.
 */
export function clasesDe(fuente: string): string[] {
  const salida = new Set<string>();
  // `className="…"`, `className={'…'}`, y las cadenas sueltas dentro de `cn(…)` y `cva(…)`.
  for (const [, cadena] of fuente.matchAll(/className=(?:"([^"]*)"|\{`([^`]*)`\}|\{'([^']*)'\})/g)) {
    for (const t of (cadena ?? '').split(/\s+/)) if (esUtilidad(t)) salida.add(t);
  }
  for (const [, cadena] of fuente.matchAll(/'([^'\n]*)'/g)) {
    const partes = (cadena ?? '').split(/\s+/);
    // Una cadena de una sola palabra puede ser cualquier cosa —un nombre, una ruta—; se exige que
    // parezca una lista de clases o que sea inequivocamente una utilidad con prefijo conocido.
    for (const t of partes) if (esUtilidad(t)) salida.add(t);
  }
  return [...salida];
}

/**
 * Los prefijos que Tailwind genera y que estas piezas usan.
 *
 * La lista existe para NO preguntarle al compilador por cadenas que no son clases —una ruta, un
 * identificador—, porque de esas Tailwind no emite nada y el rojo seria falso. Es una lista de
 * inclusion, asi que peca de corta y no de larga: una utilidad nueva que no este aqui no se
 * comprueba, pero ninguna cadena ajena se cuela.
 */
const PREFIJOS =
  /^(?:-?(?:bg|text|border|rounded|ring|shadow|fill|stroke|outline|from|via|to|accent|caret|divide|placeholder|decoration)-|(?:hover|focus|focus-visible|active|disabled|data-\[[^\]]+\]|aria-\w+|group-hover|peer-focus|print|sm|md|lg|xl):)/;

function esUtilidad(token: string): boolean {
  if (token === '' || token.length > 80) return false;
  // Nada con caracteres que una clase no lleva.
  if (/[^A-Za-z0-9_:\-[\]().,%#/\\&>*+~=@'"]/.test(token)) return false;
  return PREFIJOS.test(token);
}

/** Todos los `.tsx` de un arbol, sin pruebas ni muestras. */
export function fuentesDe(raiz: string): string[] {
  return readdirSync(raiz, { withFileTypes: true }).flatMap((e) => {
    const ruta = join(raiz, e.name);
    if (e.isDirectory()) return e.name === 'node_modules' ? [] : fuentesDe(ruta);
    if (!e.name.endsWith('.tsx') || e.name.includes('.test.')) return [];
    return [ruta];
  });
}
