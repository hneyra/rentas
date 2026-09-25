// Viola: `verificaciones/ninguna-ruta-se-resuelve-contra-el-cwd.test.ts`. A PROPOSITO, las nueve formas.
//
// Compila, pasa el lint, y cada una de estas lecturas funciona mientras `yarn test` se lance desde
// `frontend/`. Es la forma exacta del defecto de #295: `fuentesDe('src/pantallas')` y
// `readFileSync('package.json')` leian contra el directorio de trabajo, y desde un `cwd` que tuviera
// un `src/pantallas` —o un `package.json`— habrian medido el arbol equivocado en verde.
//
// Debajo de las nueve van cinco formas BUENAS, que la guarda no puede senalar: sin ellas, una
// guarda que marcara toda llamada a `readFileSync` pasaria esta muestra igual.
//
// Nada de esto se ejecuta: son funciones exportadas que nadie llama. No vive en `muestras/`, que es
// de las prohibiciones de ESLint y exige que cada archivo tenga una que lo reclame
// (`reglas-de-eslint.test.ts`); es el mismo motivo por el que existe `muestra-del-arnes/`.

import * as fs from 'node:fs';
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const RAIZ = fileURLToPath(new URL('../..', import.meta.url));

/** Un envoltorio que reenvia su argumento a `readdirSync`, como `fuentesDe` de `tailwind.ts`. */
export function entradasDe(raiz: string): string[] {
  return readdirSync(raiz);
}

/**
 * (9) Un envoltorio cuyo parametro trae una ruta relativa POR OMISION: `entradasPorOmision()` no
 * lleva ningun literal en la llamada, y `readdirSync(raiz)` tampoco. Es como quedaria el
 * `fuentesDeProduccion(desde = join(RAIZ, 'src'))` de varias guardas de aqui si perdiera el `join`.
 */
export function entradasPorOmision(raiz = 'src/preferencias'): string[] {
  return readdirSync(raiz);
}

export function lasOchoQueLaViolan(nombre: string): unknown[] {
  return [
    // (1) La muestra que pide el issue: una ruta suelta, que se resuelve contra el `cwd`.
    readdirSync('src/pantallas'),
    // (2) La de `las-peerdependencies-estan` hasta #295.
    readFileSync('package.json', 'utf8'),
    // (3) Una plantilla sin sustituciones sigue siendo un literal.
    existsSync(`diseno/RentasV8.dc.html`),
    // (4) Y una con sustituciones que ARRANCA en texto relativo.
    statSync(`src/${nombre}`),
    // (5) La concatenacion: lo que decide es por donde empieza.
    writeFileSync('dist/' + nombre, ''),
    // (6) `join` no absolutiza nada: `join('src', …)` sigue siendo relativa.
    readFileSync(join('src', 'estilos.css'), 'utf8'),
    // (7) Por el espacio de nombres, y no por la importacion con nombre.
    fs.readFileSync('vite.config.ts', 'utf8'),
    // (8) La de `tailwind-emite-las-clases` hasta #295: la ruta llega a `readdirSync` por un
    //     envoltorio, y en la llamada a `readdirSync` no hay ningun literal que ver.
    entradasDe('src/piezas'),
  ];
}

export function lasCincoQueNo(ruta: string): unknown[] {
  return [
    // Contra la raiz del arbol, derivada de `import.meta.url`: es lo que hacen las guardas de aqui.
    readFileSync(join(RAIZ, 'package.json'), 'utf8'),
    // Un `URL` es absoluto por construccion.
    readdirSync(new URL('../../src', import.meta.url)),
    // Un literal absoluto no depende del `cwd`.
    existsSync('/etc/hostname'),
    // `resolve` desde una raiz absoluta, y el envoltorio con una ruta que ya lo es.
    statSync(resolve(RAIZ, 'src')),
    entradasDe(ruta),
  ];
}
