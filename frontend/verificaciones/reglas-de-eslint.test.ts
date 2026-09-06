import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ESLint } from 'eslint';
import { describe, expect, it } from 'vitest';

import { CLIENTE_DE_API, PROHIBICIONES, REGLAS_EXIGIDAS } from '../eslint.prohibiciones.mjs';

/**
 * Las reglas de `eslint.config.js` muerden.
 *
 * Es el equivalente frontend de `ReglasDeArquitecturaMuerdenTest`: cada prohibicion tiene
 * una muestra que la viola a proposito, y aqui se exige que ESLint la senale. **Una regla
 * que no puede fallar no protege nada** — el mismo argumento por el que la prueba de
 * aislamiento demuestra que el superusuario omite RLS en vez de afirmarlo.
 *
 * Tres cosas hacen que esto no sea una lista mas que alguien olvida actualizar:
 *
 *   1. La lista de prohibiciones **se importa del propio config**. No hay copia.
 *   2. El nombre del archivo de la muestra **se compone** desde el `clave`. Anadir una
 *      prohibicion sin su muestra es un archivo que no existe, y sale rojo aqui mismo.
 *   3. El mensaje esperado **es el del config**. Si alguien reescribe el mensaje y deja la
 *      regla apagada, no hay texto duplicado que lo tape.
 *
 * Lo que ninguna derivacion puede sujetar es que alguien BORRE una prohibicion: con ella
 * se iria su prueba, en verde. Eso lo sujeta `REGLAS_EXIGIDAS`, que es la unica lista
 * escrita a mano y la que nombra las reglas del producto.
 *
 * Las muestras estan en `ignores` de la configuracion para que `yarn lint` no las senale;
 * aqui se lintan como TEXTO, con una ruta sintetica dentro de `src/`, que es donde la
 * regla tiene que aplicar de verdad.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const RAIZ = join(AQUI, '..');
const MUESTRAS = join(AQUI, 'muestras');

const eslint = new ESLint({ cwd: RAIZ });

/**
 * Ruta sintetica: la muestra se juzga como si viviera en una pantalla de la aplicacion.
 *
 * **Conserva la extension del archivo de la muestra**, y no es un detalle: una muestra con
 * JSX tiene que juzgarse como `.tsx`. Juzgada como `.ts`, el analizador de TypeScript no
 * admite JSX y el rojo habla de un error de sintaxis en vez de la regla que se venia a
 * comprobar — o peor, la prohibicion no llega a evaluarse y la muestra pasa en verde.
 */
const enUnaPantalla = (nombre: string) => join(RAIZ, 'src/pantallas', nombre);

/** El archivo de la muestra de esa clave, o `null` si no hay ninguno. */
function archivoDeLaMuestra(clave: string): string | null {
  for (const extension of ['.ts', '.tsx']) {
    const candidato = join(MUESTRAS, `${clave}${extension}`);
    if (existsSync(candidato)) {
      return candidato;
    }
  }
  return null;
}

async function mensajesDe(archivo: string, rutaJuzgada: string): Promise<string[]> {
  const codigo = readFileSync(archivo, 'utf8');
  const [resultado] = await eslint.lintText(codigo, { filePath: rutaJuzgada });
  return (resultado?.messages ?? []).map((m) => m.message);
}

describe('cada prohibicion tiene su muestra, y ESLint la senala', () => {
  it.each(PROHIBICIONES.map((p) => ({ ...p })))('$clave', async ({ clave, message }) => {
    const archivo = archivoDeLaMuestra(clave);

    expect(
      archivo,
      `La prohibicion «${clave}» no tiene muestra que la viole.\n` +
        `Escribe verificaciones/muestras/${clave}.ts con codigo que la incumpla a\n` +
        `proposito. Una regla sin muestra no se ha demostrado que pueda fallar, y una\n` +
        `regla que no puede fallar no protege nada.`,
    ).not.toBeNull();

    const mensajes = await mensajesDe(archivo as string, enUnaPantalla(basename(archivo as string)));

    expect(
      mensajes,
      `ESLint no senalo la muestra de «${clave}».\n` +
        `Se esperaba el mensaje del config:\n  ${message}\n` +
        `Se obtuvo:\n${mensajes.length === 0 ? '  (ninguno)' : mensajes.map((m) => `  · ${m}`).join('\n')}`,
    ).toContain(message);
    // 30 s y no los 5 de Vitest: el PRIMER caso paga el arranque en frio de ESLint y del
    // analizador de TypeScript —2.4 s con el arbol de F-1, mas de 5 con este—, y ese coste
    // crece con los archivos del proyecto, no con lo que la prueba comprueba. Un tiempo
    // agotado ahi no dice «la regla no muerde»: dice «la maquina iba cargada», y es el
    // rojo mas caro que hay, porque no se reproduce.
  }, 30_000);
});

describe('la lista de prohibiciones y la de muestras no se separan', () => {
  it('cada regla del producto tiene al menos una prohibicion que la sirve', () => {
    const servidas = new Set(PROHIBICIONES.map((p) => p.regla));
    const huerfanas = REGLAS_EXIGIDAS.filter((regla) => !servidas.has(regla));

    expect(
      huerfanas,
      'Hay reglas del producto que ninguna prohibicion de ESLint expresa. Una regla que\n' +
        'solo vive en un documento se incumple en seis meses.',
    ).toEqual([]);
  });

  it('ninguna prohibicion sirve a una regla que nadie declaro', () => {
    const noDeclaradas = PROHIBICIONES.filter((p) => !REGLAS_EXIGIDAS.includes(p.regla)).map(
      (p) => `${p.clave} -> ${p.regla}`,
    );

    expect(
      noDeclaradas,
      'Una prohibicion nueva se declara tambien en REGLAS_EXIGIDAS: si no, borrarla se\n' +
        'llevaria su prueba por delante y nadie lo notaria.',
    ).toEqual([]);
  });

  it('no hay muestras sin prohibicion que las reclame', () => {
    const claves = new Set(PROHIBICIONES.map((p) => p.clave));
    const sobrantes = readdirSync(MUESTRAS)
      .map((archivo) => archivo.replace(/\.tsx?$/, ''))
      .filter((clave) => !claves.has(clave));

    expect(
      sobrantes,
      'Sobra una muestra: viola una regla que ya no existe, asi que nadie la lee y nada\n' +
        'la mantiene cierta.',
    ).toEqual([]);
  });
});

describe('la excepcion del cliente de API es exactamente una', () => {
  const conExcepcion = PROHIBICIONES.filter((p) => p.salvo !== undefined);

  it('solo el cliente de API esta exceptuado de algo', () => {
    expect(new Set(conExcepcion.map((p) => p.salvo))).toEqual(new Set([CLIENTE_DE_API]));
  });

  it.each(conExcepcion.map((p) => ({ ...p })))(
    '«$clave» no se senala dentro de $salvo',
    async ({ clave, message, salvo }) => {
      const archivo = archivoDeLaMuestra(clave);
      const mensajes = await mensajesDe(archivo as string, join(RAIZ, salvo as string, 'x.ts'));

      expect(mensajes).not.toContain(message);
    },
  );

  it('pero fuera de el, si', async () => {
    const mensajes = await mensajesDe(
      archivoDeLaMuestra('fetch-fuera-del-cliente') as string,
      enUnaPantalla('cualquiera.ts'),
    );

    expect(mensajes.join('\n')).toMatch(/Las peticiones pasan por «solicitar»/);
  });

  it('y el cliente de API no queda exento de TODO: solo de su excepcion', async () => {
    // Que `src/api/` pueda llamar a `fetch` no lo pone fuera del idioma ni de la regla 2.
    const mensajes = await mensajesDe(
      archivoDeLaMuestra('municipalidad-en-el-cliente') as string,
      join(RAIZ, CLIENTE_DE_API, 'x.ts'),
    );

    expect(mensajes.join('\n')).toMatch(/jamas envia municipalidadId/);
  });
});

describe('las reglas no senalan codigo correcto', () => {
  it('los dos contadores del envoltorio de paginacion se declaran «number», y pasan', async () => {
    // `totalElementos` y `totalPaginas` son cuentas de cosas, no de dinero: el backend los
    // publica como `entero` en las mas de sesenta operaciones paginadas. Si la prohibicion los
    // senalara, toda pantalla con una tabla arrancaria con dos `eslint-disable` — y una regla
    // que se desactiva por costumbre deja de proteger a la tercera vez.
    const envoltorio = `
      export interface Pagina<T> {
        readonly contenido: readonly T[];
        readonly pagina: number;
        readonly tamano: number;
        readonly totalElementos: number;
        readonly totalPaginas: number;
        readonly hayMas: boolean;
      }
    `;

    const [resultado] = await eslint.lintText(envoltorio, {
      filePath: enUnaPantalla('paginacion.ts'),
    });

    expect(resultado?.messages ?? []).toEqual([]);
  });

  it('pero el resto de «total…» sigue prohibido: la excepcion no se derrama', async () => {
    const conDinero = `
      export interface Liquidacion {
        readonly totalAPagar: number;
      }
    `;

    const [resultado] = await eslint.lintText(conDinero, {
      filePath: enUnaPantalla('liquidacion.ts'),
    });

    expect((resultado?.messages ?? []).map((m) => m.message).join('\n')).toMatch(
      /Un importe se declara «string»/,
    );
  });

  it('el codigo que las respeta pasa limpio', async () => {
    const correcto = `
      import { solicitar } from '../api/cliente.ts';

      export interface CuentaCorriente {
        readonly total: string;
        readonly fechaCalculo: string;
      }

      export const alicuotaPredial = '0.006';

      export function cuentaDe(contribuyente: string) {
        // El total llega calculado del backend, con su fecha: aqui solo se pide.
        return solicitar<CuentaCorriente>(\`/contribuyentes/\${contribuyente}/cuenta\`);
      }
    `;

    const [resultado] = await eslint.lintText(correcto, {
      filePath: enUnaPantalla('correcto.ts'),
    });

    expect(resultado?.messages ?? []).toEqual([]);
  });

  it('un «Importe» CON su fecha de calculo pasa limpio', async () => {
    // **Esta es la mitad que faltaba, y hacia falta.** La muestra de
    // `importe-sin-fecha` escribe `<Importe valor="…" />` SIN un solo atributo, asi que
    // un selector que buscara cualquier otro nombre —`fechaDeCalculo` en vez de
    // `fechaCalculo`— la seguiria senalando igual y la prohibicion pasaria en VERDE
    // habiendo dejado de proteger nada. Se comprobo: renombrar el atributo dentro del
    // selector dejaba las 17 pruebas en verde. Lo que lo caza es el caso positivo.
    const correcto = `
      import { Importe } from '../ds/index.ts';

      export function FilaDeCuentaCorriente() {
        return <Importe valor="1842.60" fechaCalculo="2026-09-06" />;
      }
    `;

    const [resultado] = await eslint.lintText(correcto, {
      filePath: enUnaPantalla('correcto.tsx'),
    });

    expect(
      (resultado?.messages ?? []).map((m) => m.message),
      'Un `<Importe>` que SI declara su fecha no puede estar senalado: si lo esta, el\n' +
        'selector ya no busca el atributo que dice buscar, y entonces la prohibicion\n' +
        'senala a todo el mundo — que es indistinguible de no senalar a nadie.',
    ).toEqual([]);
  });
});
