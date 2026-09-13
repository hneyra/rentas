// @vitest-environment node
//
// Lee archivos del disco. No es un DOM lo que necesita.

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

/**
 * **La siembra del catalogo es SOLO de desarrollo, y se puede comprobar sin construir** (#114).
 *
 * <h2>Que vigila, y por que estatico</h2>
 *
 * La medicion de verdad es sobre el `dist/` —`e2e/la-siembra-no-viaja-al-bundle.spec.ts` la hace
 * sobre el bundle construido, y el `Dockerfile` sobre lo servido—. Esta guarda vigila **las dos
 * formas conocidas de romperla**, y lo hace en `yarn verificar`, que es donde se entera quien
 * escribe el cambio:
 *
 *   · **leer la bandera en tiempo de ejecucion.** Esta medido desde F-4 y por eso se repite: con
 *     la condicion detras de una funcion —o de `configuracion()`, o de `globalThis`— Rollup no
 *     puede plegarla, el `import()` dinamico se queda y las capturas viajan enteras. En la V6 eso
 *     eran 227 205 bytes con «Rufina Medina Medina» dentro frente a 193 592 sin ella;
 *   · **importar la siembra estaticamente.** Un `import { sembrarElCatalogo } from …` en
 *     cualquier archivo de `src/` mete el modulo en el paquete pase lo que pase con la bandera,
 *     y no hay condicion que lo salve.
 *
 * Las dos salen en verde en `yarn dev`, que es lo que las hace peligrosas: quien las escribe ve
 * su pantalla funcionando igual.
 *
 * <h2>Y la tercera cosa: que la bandera retirada no quede nombrada como si valiera</h2>
 *
 * `VITE_KAMAYUK_PROXY_DE_DATOS` estuvo en `.env.development` desde #90 sin hacer absolutamente
 * nada, con diez lineas de comentario sobre un mecanismo que ya no existia. Lo dice el propio
 * `arranque.ts`: «una bandera que no hace nada es peor que no tenerla: la proxima persona la
 * enciende esperando algo». Nombrarla en prosa —para contar de donde viene esta— se queda; lo
 * que no puede volver es una **asignacion**, que es lo que la hace parecer viva.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');
const REPOSITORIO = join(FRONTEND, '..');

const leer = (ruta: string) => readFileSync(join(FRONTEND, ruta), 'utf8');

/** La bandera. Escrita una vez aqui: si cambia de nombre, todo lo de abajo lo dice a la vez. */
const BANDERA = 'VITE_KAMAYUK_SIN_PLATAFORMA';

/** La que se retiro con la V6 y sobrevivio diez meses sin hacer nada. */
const RETIRADA = 'VITE_KAMAYUK_PROXY_DE_DATOS';

/** La siembra, y el unico archivo de `src/` que puede alcanzarla. */
const SIEMBRA = 'desarrollo/sembrarElCatalogo.ts';
const ARRANQUE = 'src/arranque.ts';

/** Todos los `.ts`/`.tsx` de produccion bajo `src/`, con su ruta relativa al frontend. */
function fuentesDeProduccion(desde = join(FRONTEND, 'src')): readonly string[] {
  return readdirSync(desde).flatMap((entrada) => {
    const ruta = join(desde, entrada);
    if (statSync(ruta).isDirectory()) return fuentesDeProduccion(ruta);
    return /\.tsx?$/.test(entrada) && !/\.test\.tsx?$/.test(entrada)
      ? [relative(FRONTEND, ruta)]
      : [];
  });
}

describe('AC1 — la siembra existe, y vive fuera de `src/`', () => {
  it('EL CENTINELA: el archivo esta donde se dice, y `src/` se puede leer', () => {
    // Sin esto, un archivo movido dejaria a las comprobaciones de abajo mirando cadenas que no
    // estan en ninguna parte, y las dos saldrian verdes afirmando que no hay nada que reprochar.
    expect(existsSync(join(FRONTEND, SIEMBRA)), `falta «${SIEMBRA}»`).toBe(true);
    expect(fuentesDeProduccion().length).toBeGreaterThan(20);
  });

  it('y NO esta bajo `src/`, que es donde la guarda de las capturas prohibe importarlas', () => {
    // `camino-a-la-api.test.ts` prohibe que un archivo de produccion de `src/` importe
    // `seguridadMedida.ts`. Meter la siembra ahi obligaba a tallarle una excepcion a esa guarda
    // —la que impide que las capturas se conviertan en respaldos de produccion—, y una guarda con
    // una excepcion vale lo que valga la proxima.
    expect(existsSync(join(FRONTEND, 'src/desarrollo'))).toBe(false);
  });
});

describe('AC2 — nada de `src/` alcanza la siembra si no es por el `import()` plegable', () => {
  it('solo `arranque.ts` la nombra, y ningun otro archivo de produccion', () => {
    const culpables = fuentesDeProduccion().filter(
      (ruta) => ruta !== ARRANQUE && /desarrollo\/sembrarElCatalogo/.test(leer(ruta)),
    );

    expect(
      culpables,
      'Estos archivos de produccion alcanzan la siembra de desarrollo:\n' +
        `  ${culpables.join('\n  ')}\n\n` +
        '  Solo `src/arranque.ts` puede, y solo por el `import()` dinamico detras de las dos\n' +
        '  condiciones constantes. Desde cualquier otro sitio, las capturas viajan al paquete.',
    ).toEqual([]);
  });

  it('y la nombra UNA vez, en un `import()` dinamico y no en un `import … from`', () => {
    const arranque = leer(ARRANQUE);

    // Un `import { sembrarElCatalogo } from '../desarrollo/…'` mete el modulo en el paquete pase
    // lo que pase con la bandera: no hay condicion que pliegue un import estatico.
    expect(
      /from\s+'[^']*desarrollo\/sembrarElCatalogo/.test(arranque),
      '`arranque.ts` importa la siembra ESTATICAMENTE: entonces viaja al paquete siempre.',
    ).toBe(false);
    expect(arranque.match(/import\('\.\.\/desarrollo\/sembrarElCatalogo\.ts'\)/g)).toHaveLength(1);
  });

  it('LAS DOS CONDICIONES SE LEEN AL CONSTRUIR, y van delante del `import()`', () => {
    // Es la propiedad entera del AC2, y la unica forma conocida de romperla sin que nada mas se
    // entere. Medido en F-4 con el proxy de V6: leida en tiempo de ejecucion, la bandera deja el
    // modulo dentro —227 205 bytes y las cifras del artboard dentro— y `yarn dev` sigue igual de
    // verde. Por eso se comprueba el TEXTO: lo que importa no es que la condicion sea cierta,
    // sino que el empaquetador pueda evaluarla.
    const esperado = new RegExp(
      String.raw`if \(!import\.meta\.env\.DEV\) return false;` +
        String.raw`[\s\S]{0,200}?` +
        String.raw`if \(import\.meta\.env\.${BANDERA} !== 'true'\) return false;` +
        String.raw`[\s\S]{0,400}?` +
        String.raw`await import\('\.\./desarrollo/sembrarElCatalogo\.ts'\)`,
    );

    expect(
      esperado.test(leer(ARRANQUE)),
      'Las dos condiciones que guardan el `import()` de la siembra ya no se leen al CONSTRUIR.\n' +
        'Se esperaba, en este orden y antes del import:\n' +
        '  if (!import.meta.env.DEV) return false;\n' +
        `  if (import.meta.env.${BANDERA} !== 'true') return false;\n\n` +
        '  Vite sustituye las dos por su literal al construir y Rollup pliega la condicion, que\n' +
        '  es lo que se lleva por delante el `import()` con las capturas dentro. Detras de una\n' +
        '  funcion, de `configuracion()` o de `globalThis`, el modulo VIAJA — y en desarrollo no\n' +
        '  se nota: la pantalla se ve igual.',
    ).toBe(true);
  });

  it('y el `Dockerfile` apaga la bandera antes de construir: la primera de las tres vallas', () => {
    const dockerfile = leer('Dockerfile');

    expect(dockerfile).toContain(`ENV ${BANDERA}=false`);
    expect(dockerfile.indexOf(`ENV ${BANDERA}=false`)).toBeLessThan(
      dockerfile.indexOf('RUN yarn build'),
    );
  });
});

describe('AC3 — la bandera retirada ya no se asigna en ninguna parte', () => {
  it('`.env.development` declara la de hoy y no la de ayer', () => {
    const entorno = leer('.env.development');

    expect(entorno).toContain(`${BANDERA}=true`);
    expect(
      entorno.includes(RETIRADA),
      `\`.env.development\` sigue nombrando ${RETIRADA}, que no hace nada desde #90.`,
    ).toBe(false);
  });

  it('y ningun archivo del frontend le da valor: nombrarla en prosa si, asignarla no', () => {
    // La prosa se queda a proposito —`arranque.ts` y el `Dockerfile` cuentan de donde viene el
    // mecanismo de hoy, y borrarlo dejaria el codigo de al lado sin sujeto—. Lo que no puede
    // volver es una asignacion, que es lo que la hace parecer viva.
    const sospechosos = ['.env.development', '.dockerignore', 'Dockerfile', ARRANQUE, SIEMBRA];
    const culpables = sospechosos.filter((ruta) =>
      new RegExp(`${RETIRADA}\\s*=`).test(leer(ruta)),
    );

    expect(culpables).toEqual([]);
  });
});

describe('AC4 — como se mira la interfaz en local queda escrito, y nombra la bandera', () => {
  it('DEV-01 explica los dos niveles, y el segundo nombra la bandera por su nombre', () => {
    // Atarlo al nombre es lo que impide que un renombrado deje la documentacion huerfana: el dia
    // que la bandera se llame de otra forma, esto se pone rojo en vez de mandar a quien llegue a
    // escribir una variable que ya no existe.
    const dev01 = readFileSync(join(REPOSITORIO, 'docs/D0-desarrollo/entorno-local.md'), 'utf8');

    expect(dev01).toContain(BANDERA);
    expect(dev01).toContain('yarn dev:con-plataforma');
  });

  it('y `dev:con-plataforma` existe de verdad en el manifiesto', () => {
    const manifiesto = JSON.parse(leer('package.json')) as {
      scripts: Record<string, string | undefined>;
    };

    expect(manifiesto.scripts['dev:con-plataforma']).toBe(`${BANDERA}=false vite`);
  });
});
