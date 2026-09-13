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
 * **La hoja que el navegador recibe**, con su ruta y no solo con su contenido (#125).
 *
 * La ruta es el dato que faltaba: vive en `estilos/` y no en la raiz del paquete, y de su
 * DIRECTORIO —no del de `package.json`— cuelga todo lo que la hoja importe.
 */
export const HOJA_DE_UI = join(RAIZ_DE_UI, 'estilos', 'estilos.css');

/**
 * El CSS que Tailwind emite para la lista de clases que se le den.
 *
 * `loadStylesheet` hace falta porque la hoja empieza con `@import "tailwindcss"`, y el compilador
 * no sabe resolver ese nombre por si solo: aqui se le dice que es el `index.css` del paquete.
 *
 * <h2>El `base` sale de LA HOJA, y no del paquete (#125)</h2>
 *
 * Un `@import "./temas.css"` escrito dentro de `estilos/estilos.css` es `estilos/temas.css`:
 * **el empaquetador lo resuelve relativo al archivo que lo escribe**, y eso es lo que el
 * navegador recibe. Con `base` en la raiz del paquete se buscaba en `<raiz>/temas.css` — o sea
 * que el arnes compilaba una hoja que no es la que se sirve, y lo hacia en silencio: mientras el
 * unico `@import` fuera `"tailwindcss"`, que se intercepta por nombre, no habia nada que
 * resolver mal.
 *
 * La `hoja` es un parametro por eso mismo: la guarda que lo vigila
 * —`tailwind-resuelve-los-import.test.ts`— necesita compilar una hoja PROPIA con un `@import`
 * relativo de verdad, sin esperar a que la libreria tenga uno.
 */
export async function compilar(
  clases: readonly string[],
  hoja: string = HOJA_DE_UI,
): Promise<string> {
  const compilado = await compile(readFileSync(hoja, 'utf8'), {
    base: dirname(hoja),
    loadStylesheet: (id: string, desde: string) => {
      const ruta = id === 'tailwindcss' ? requerir.resolve('tailwindcss/index.css') : join(desde, id);
      return Promise.resolve({
        path: ruta,
        // El `base` de la importada es SU directorio, que es lo que hace que un `@import`
        // escrito dentro de ella cuelgue de donde ella esta y no de donde esta la primera.
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

/**
 * **Leer el CSS EMITIDO por reglas, y no por `includes` sobre todo el texto** (#139).
 *
 * <h2>De que rojo viene</h2>
 *
 * De uno que no salio. `tailwind-emite-las-clases` preguntaba `plano.includes(c.valor)` —«¿esta
 * este hexadecimal en alguna parte del CSS?»— para comprobar que la utilidad lleva el valor del
 * artboard. Mientras la hoja solo traia el `@theme`, esa pregunta y la buena daban lo mismo. Desde
 * `kamayuk-lib`#23 la hoja arrastra `temas.css`, que reproduce los 38 valores en su bloque
 * `institucional/claro`, y la respuesta paso a ser **siempre** «si». Medido con
 * `--color-tinta-2: #ff00ff` puesto en el `@theme`: cuatro pruebas en verde con el defecto dentro.
 *
 * Y la otra mitad —`plano.includes('.bg-' + nombre)`— tenia su propio filo romo: `includes` es
 * subcadena, asi que `.bg-superficie` responde que si a `.bg-sup`, y `.bg-tinta-2` a `.bg-tinta`.
 * **Siete de los 38 colores del artboard son prefijo de otro** —`sup`, `tinta`, `azul`, `linea`,
 * `esqueleto`, `sobre-barra` y `velo`—, o sea que siete utilidades podian dejar de emitirse sin
 * que nadie lo dijera.
 *
 * <h2>Que se hace en su lugar</h2>
 *
 * Se parte el CSS en reglas —selectores, declaraciones y las at-rules que las envuelven— y se
 * pregunta a la regla. Es la unica forma de que «lleva su valor» tenga por sujeto la utilidad y no
 * el archivo entero.
 */
export interface Regla {
  /** Los selectores TAL CUAL se emitieron, con sus escapes: `.hover\:bg-azul:hover`. */
  readonly selectores: readonly string[];
  /** `background-color` -> `var(--color-azul)`. */
  readonly declaraciones: ReadonlyMap<string, string>;
  /** Los preludios de las at-rules que la envuelven: `['@layer utilities']`. */
  readonly dentroDe: readonly string[];
}

/** Un preludio es de bloque anidado —`@media`, `@layer`, `@supports`— y no de regla. */
const esAtRule = (preludio: string): boolean => preludio.startsWith('@');

/**
 * Parte el CSS emitido en reglas.
 *
 * Es un lector y no un analizador de CSS: le basta con llaves, comas y dos puntos porque lo que
 * lee lo escribio Tailwind y no una persona. Los comentarios se quitan antes —el emitido empieza
 * por su banner— para que un `/* … {` no abra un bloque de mentira.
 */
export function reglasDe(css: string): Regla[] {
  const sinComentarios = css.replace(/\/\*[\s\S]*?\*\//g, ' ');
  const salida: Regla[] = [];
  const pila: { preludio: string; declaraciones: Map<string, string> }[] = [];
  let acumulado = '';

  const anotar = (texto: string): void => {
    const bloque = pila[pila.length - 1];
    const corte = texto.indexOf(':');
    if (bloque === undefined || corte < 0) return;
    bloque.declaraciones.set(texto.slice(0, corte).trim(), texto.slice(corte + 1).trim());
  };

  for (const caracter of sinComentarios) {
    if (caracter === '{') {
      pila.push({ preludio: acumulado.trim(), declaraciones: new Map() });
      acumulado = '';
    } else if (caracter === '}') {
      anotar(acumulado);
      const bloque = pila.pop();
      if (bloque !== undefined && !esAtRule(bloque.preludio)) {
        salida.push({
          selectores: partirEnSelectores(bloque.preludio),
          declaraciones: bloque.declaraciones,
          dentroDe: pila.map((b) => b.preludio),
        });
      }
      acumulado = '';
    } else if (caracter === ';') {
      anotar(acumulado);
      acumulado = '';
    } else {
      acumulado += caracter;
    }
  }
  return salida;
}

/**
 * `button, input:where([type="a"], [type="b"])` -> dos selectores, y no tres.
 *
 * La coma de dentro de un `:where(…)` no separa nada, y Tailwind emite unos cuantos.
 */
function partirEnSelectores(preludio: string): string[] {
  const salida: string[] = [];
  let nivel = 0;
  let actual = '';
  for (const caracter of preludio) {
    if (caracter === '(' || caracter === '[') nivel += 1;
    else if (caracter === ')' || caracter === ']') nivel -= 1;
    if (caracter === ',' && nivel === 0) {
      salida.push(actual.trim());
      actual = '';
    } else {
      actual += caracter;
    }
  }
  if (actual.trim() !== '') salida.push(actual.trim());
  return salida;
}

/**
 * Las clases que un selector emitido NOMBRA, con sus escapes ya resueltos.
 *
 * Tailwind escapa lo que una clase lleva y un selector no admite: `data-[state=checked]:bg-azul`
 * sale como `.data-\[state\=checked\]\:bg-azul`. Se lee caracter a caracter porque es la unica
 * manera de saber donde ACABA la clase: en `.hover\:bg-azul:hover` el primer `:` es parte del
 * nombre y el segundo no, y quitar las barras antes de leer pierde justo esa diferencia.
 */
export function clasesDelSelector(selector: string): string[] {
  const salida: string[] = [];
  for (let i = 0; i < selector.length; i += 1) {
    if (selector[i] !== '.' || selector[i - 1] === '\\') continue;
    let nombre = '';
    let j = i + 1;
    while (j < selector.length) {
      const caracter = selector[j] ?? '';
      if (caracter === '\\') {
        nombre += selector[j + 1] ?? '';
        j += 2;
      } else if (/[A-Za-z0-9_-]/.test(caracter)) {
        nombre += caracter;
        j += 1;
      } else break;
    }
    if (nombre !== '') salida.push(nombre);
  }
  return salida;
}

/** Todas las clases que el CSS emitido llega a nombrar, exactas y no por subcadena. */
export function clasesEmitidas(reglas: readonly Regla[]): Set<string> {
  return new Set(reglas.flatMap((r) => r.selectores.flatMap((s) => clasesDelSelector(s))));
}

/**
 * La regla de la utilidad **desnuda**: la que tiene por selector exactamente `.<clase>`.
 *
 * Desnuda a proposito: `.hover\:bg-azul:hover` tambien nombra una clase, pero lo que se quiere
 * medir es el camino `--color-x` -> `bg-x`, y ese lo dibuja la utilidad sin variante.
 */
export function utilidadDesnuda(reglas: readonly Regla[], clase: string): Regla | undefined {
  return reglas.find(
    (r) => r.selectores.length === 1 && r.selectores[0]?.replace(/\\/g, '') === `.${clase}`,
  );
}

/**
 * **La paleta que Tailwind deriva del `@theme`**, y no cualquier `--color-*` del CSS emitido.
 *
 * El `@theme` sale como `@layer theme { :root, :host { … } }`. Las seis paletas de `temas.css`
 * salen FUERA de toda capa y en otros selectores —`:root, [data-tema='institucional']`,
 * `:root[data-modo='oscuro']`, …—, que es lo que les deja ganar la cascada. Acotar la lectura a la
 * capa es lo que hace que una mutacion del `@theme` no se la tape la paleta de al lado: es el
 * defecto de #139 exactamente.
 */
export function paletaDelTema(reglas: readonly Regla[]): Map<string, string> {
  const salida = new Map<string, string>();
  for (const regla of reglas) {
    if (!regla.dentroDe.some((a) => /^@layer\s+theme$/.test(a))) continue;
    if (!regla.selectores.includes(':root')) continue;
    for (const [propiedad, valor] of regla.declaraciones) {
      if (propiedad.startsWith('--')) salida.set(propiedad, valor);
    }
  }
  return salida;
}

/**
 * El valor de una declaracion con sus `var(--…)` sustituidos por lo que la paleta dice.
 *
 * Una utilidad de color no lleva el hexadecimal: lleva `var(--color-azul)`. Sin resolverlo, «la
 * regla trae su valor» no se puede preguntar. Lo que la paleta no conozca se deja tal cual, para
 * que el rojo diga `var(--color-x)` —o sea, «el token no llego»— en vez de un vacio.
 */
export function resolver(valor: string, paleta: ReadonlyMap<string, string>): string {
  let salida = valor;
  // Cuatro pasadas: un token que apunte a otro token es legitimo, una cadena mas larga que eso
  // en una paleta no lo es, y el tope impide dar vueltas si alguna vez se cierra un ciclo.
  for (let vuelta = 0; vuelta < 4; vuelta += 1) {
    const siguiente = salida.replace(/var\(\s*(--[a-z0-9-]+)\s*\)/gi, (todo, token: string) =>
      paleta.get(token) ?? todo,
    );
    if (siguiente === salida) break;
    salida = siguiente;
  }
  return salida.replace(/\s+/g, ' ').trim().toLowerCase();
}
