// @vitest-environment node
//
// Lee las fuentes de los conectores del disco y ademas ejercita `RUTAS`. No hay DOM que necesitar.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { CONECTORES } from '../src/datos/conectores.ts';
import { RUTAS } from '../src/datos/lecturas.ts';
import { YA_SERVIDAS } from '../src/datos/servidas.ts';
import { hojaDe, type ClaveDeHoja } from '../src/pantallas/arbol.ts';

/**
 * **Una hoja DECLARA la ruta que su conector pide** (#215).
 *
 * <h2>El hueco que lo trae, medido con una rotura que salio VERDE</h2>
 *
 * `porQueNoHayDato.test.ts` barre desde #173 que toda hoja con conector declare **alguna**
 * operacion servida de lectura, y su propio javadoc dice que eso es la condicion **necesaria** y no
 * la igualdad: «no puede comparar ruta con ruta: `Conector.pedir` es una funcion, y la ruta que
 * pide vive dentro de ella». Medido en #215: quitandole a `fis-res` la declaracion de
 * `GET /fiscalizacion/resoluciones` **en el artboard y en el arbol a la vez** —con su conector
 * pidiendola igual— las **871** pruebas siguieron pasando. La hoja seguia declarando la otra ruta
 * servida, asi que el barrido de #173 no veia nada, y `pantallas-del-artboard` compara las dos
 * fuentes **entre si**, que es justo lo que una rotura en las dos no mueve.
 *
 * El sintoma de ese hueco no es una pantalla rota: es que la declaracion de una hoja —que es lo que
 * `porQueNoHayDato` usa para redactar por que no hay dato, y lo que una revision lee para saber que
 * pide una pantalla— **puede quedarse vieja sin que nada lo diga**. Fue exactamente el defecto de
 * `aut-tram` hasta #173, y el de `fis-prog` hasta #179.
 *
 * <h2>Como se cruza, ahora que si se puede</h2>
 *
 * `RUTAS` es **dato exportado** desde que #186 saco la ventana a parametro, asi que una ruta se
 * puede resolver aqui: se buscan las `RUTAS.<clave>` que menciona el cuerpo del conector de cada
 * hoja, se evalua cada una —las que son funcion, con un relleno—, se le quita la cadena de consulta
 * y se compara contra las operaciones que la hoja declara, con el mismo comparador de `servidas.ts`.
 *
 * Lo que NO se puede: decir que una hoja declara rutas que su conector **no** pide. Eso no es un
 * defecto —el artboard declara lo que la pantalla usaria entera, no lo que hoy se conecta— y
 * exigirlo obligaria a borrar del artboard lo que todavia no se sirve.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const CONECTORES_DIR = join(AQUI, '../src/datos/conectores');

/** `'fis-res'` → `'FIS_RES'`, que es como se llama su constante. Ver el centinela. */
const constanteDe = (hoja: string): string => hoja.toUpperCase().replace(/-/g, '_');

/**
 * El cuerpo del `const <CONSTANTE>: Conector = { … };` de esa hoja, buscado en los archivos de
 * conector y en el registro.
 *
 * Revienta si no lo encuentra, y eso es parte de la guarda: un conector que dejara de llamarse como
 * su hoja haria que esto **recorriera la nada**, que es como una barrera se apaga sin que nadie la
 * borre (#78, #80).
 */
function cuerpoDelConector(hoja: ClaveDeHoja): string {
  const constante = constanteDe(hoja);
  const archivos = [
    join(AQUI, '../src/datos/conectores.ts'),
    ...['coactiva', 'consultas', 'fiscalizacion', 'inicio', 'licencias', 'seguridad', 'transito'].map(
      (modulo) => join(CONECTORES_DIR, `${modulo}.ts`),
    ),
  ];
  for (const archivo of archivos) {
    const texto = readFileSync(archivo, 'utf8');
    // Del `const X: Conector = {` hasta el `\n};` que lo cierra. Los conectores se escriben asi,
    // uno por constante y sin anidar otro dentro.
    const desde = texto.indexOf(`const ${constante}: Conector = {`);
    if (desde === -1) continue;
    const hasta = texto.indexOf('\n};', desde);
    if (hasta === -1) break;
    return texto.slice(desde, hasta);
  }
  throw new Error(
    `No se encontro «const ${constante}: Conector» para la hoja «${hoja}».\n` +
      '  O el conector cambio de nombre, o cambio de forma. Las dos hay que mirarlas: sin su\n' +
      '  cuerpo esta guarda no puede saber que rutas pide, y pasaria en verde sobre la nada.',
  );
}

/** Las claves de `RUTAS` que ese cuerpo menciona. */
function rutasQuePide(cuerpo: string): readonly string[] {
  return [...new Set([...cuerpo.matchAll(/\bRUTAS\.(\w+)/g)].map((uno) => uno[1] ?? ''))];
}

/**
 * La ruta concreta de una clave de `RUTAS`, sin su cadena de consulta.
 *
 * Las que son funcion se llaman con un relleno por parametro: lo que se compara es la **forma** de
 * la ruta, y un `{id}` del contrato casa con cualquier trozo. El relleno es texto porque toda ruta
 * lo interpola —`String(id)`, `encodeURIComponent(numero)`— y ninguna hace aritmetica con el.
 */
function caminoDe(clave: string): string {
  const entrada = (RUTAS as unknown as Record<string, unknown>)[clave];
  if (typeof entrada === 'string') return entrada.split('?')[0] ?? entrada;
  if (typeof entrada !== 'function') {
    throw new Error(`«RUTAS.${clave}» no es ni una ruta ni una funcion que la componga`);
  }
  const relleno: unknown[] = Array.from({ length: entrada.length }, () => 'X');
  const compuesta = String((entrada as (...args: unknown[]) => string)(...relleno));
  return compuesta.split('?')[0] ?? compuesta;
}

/** `/rentas/vehiculos/{placa}` → `^/rentas/vehiculos/[^/]+$`, como lo hace `servidas.ts`. */
function compilar(ruta: string): RegExp {
  const escapado = ruta
    .split(/(\{\w+\})/)
    .map((trozo) =>
      /^\{\w+\}$/.test(trozo) ? '[^/]+' : trozo.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
    )
    .join('');
  return new RegExp(`^${escapado}$`);
}

const SERVIDAS = new Set(YA_SERVIDAS.map((operacion) => operacion.ruta));

describe('toda ruta que un conector pide la declara su hoja (#215)', () => {
  const conectadas = Object.keys(CONECTORES) as readonly ClaveDeHoja[];

  it('EL CENTINELA: cada hoja conectada tiene su conector, y cada conector pide alguna ruta', () => {
    // Sin esto, un nombre que dejara de casar dejaria la comprobacion de abajo recorriendo listas
    // vacias y pasando en verde sobre la nada — que es como este repositorio se quedo sin guarda
    // dos veces (#78, #80).
    expect(conectadas.length, 'no hay ni un conector que barrer').toBeGreaterThan(10);
    const mudos = conectadas.filter((hoja) => rutasQuePide(cuerpoDelConector(hoja)).length === 0);
    expect(mudos, 'estos conectores no mencionan ni una `RUTAS.…`').toEqual([]);
  });

  it('y cada `RUTAS.…` se resuelve a un camino de verdad', () => {
    // La otra mitad del centinela: una clave que no resolviera dejaria su hoja sin comprobar.
    for (const hoja of conectadas) {
      for (const clave of rutasQuePide(cuerpoDelConector(hoja))) {
        expect(caminoDe(clave), `${hoja} · RUTAS.${clave}`).toMatch(/^\/[a-z]/);
      }
    }
  });

  it('LA IGUALDAD: ninguna hoja pide una ruta que su arbol no declare', () => {
    const sinDeclarar: string[] = [];
    for (const hoja of conectadas) {
      const declaradas = hojaDe(hoja)
        .operaciones.filter((operacion) => SERVIDAS.has(operacion.ruta))
        .map((operacion) => compilar(operacion.ruta));
      for (const clave of rutasQuePide(cuerpoDelConector(hoja))) {
        const camino = caminoDe(clave);
        if (!declaradas.some((patron) => patron.test(camino))) {
          sinDeclarar.push(`  ${hoja} pide «${camino}» (RUTAS.${clave}) y su arbol no la declara`);
        }
      }
    }
    expect(
      sinDeclarar,
      'Un conector pide una ruta que la declaracion de su hoja no nombra:\n' +
        `${sinDeclarar.join('\n')}\n\n` +
        '  La declaracion de una hoja es lo que `porQueNoHayDato` usa para redactar por que no hay\n' +
        '  dato, y lo que una revision lee para saber que pide una pantalla. Desincronizada, no\n' +
        '  rompe nada y miente: fue el defecto de `aut-tram` hasta #173 y el de `fis-prog` hasta\n' +
        '  #179. Se corrige en el ARTBOARD y en `arbol.ts` a la vez, nunca aflojando esto.',
    ).toEqual([]);
  });
});
