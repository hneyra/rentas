import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { expect, test } from '@playwright/test';

import type { IndicadorDeRecaudacion } from '../src/datos/lecturas.ts';
import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **El grafico de `ini-flujo` no lo pagan las otras treinta y nueve pantallas** (#298).
 *
 * <h2>De que defecto viene</h2>
 *
 * #288 anadio el grafico de barras de `ini-flujo`, y con el `recharts` y lo que arrastra
 * —`@reduxjs/toolkit`, `react-redux`, `immer`, `victory-vendor` con sus nueve `d3-*`…—. Medido en
 * #298: **+324,74 kB de JS (+35,6 %)**, y todo en el trozo de **entrada**, o sea peso que se
 * descarga antes de que nadie haya abierto la unica pantalla que lo usa. El total no es lo que
 * importa; lo que importa es **que baje el trozo de entrada**, y eso es lo que se mide aqui.
 *
 * <h2>Por que aqui y no en `vitest`</h2>
 *
 * Por lo mismo que `la-siembra-no-viaja-al-bundle.spec.ts`: lo que hay que medir es el `dist/`, y
 * este arnes ya lo construye antes de levantar nada. Y la segunda mitad —que el trozo aparte SE
 * PIDA y SE RESUELVA al abrir `ini-flujo`— solo la puede decir un navegador pidiendo el bundle de
 * verdad: jsdom resuelve el `import()` contra el arbol de fuentes, no contra lo construido.
 *
 * <h2>Que es «lo que se baja al entrar»</h2>
 *
 * El guion de modulo de `index.html`, los `modulepreload` que Vite le ponga delante, y **todo lo
 * que esos importen estaticamente**, recorrido hasta el final. Mirar solo el trozo de entrada daria
 * verde con recharts partido a un trozo que la entrada importa estaticamente: otro archivo, y el
 * mismo peso antes de abrir nada. Un `import("./x.js")` —que es como sale un `lazy()`— no casa con
 * el patron, y es justo lo que se quiere dejar fuera.
 *
 * <h2>Y como se reconoce recharts en un archivo minificado</h2>
 *
 * Por `recharts-surface`, la clase que pone al `<svg>` de todo grafico. Es una cadena literal, asi
 * que sobrevive al minificador, y no la escribe nadie mas: ni la libreria de componentes ni este
 * arbol. Solo se miran los `.js`: los `.map` llevan el codigo fuente entero de todo lo que entro, y
 * un mapa no se descarga al entrar.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const DIST = join(AQUI, '../dist');
const ASSETS = join(DIST, 'assets');

/** La marca de recharts dentro de un `.js` construido. Ver el javadoc. */
const MARCA_DE_RECHARTS = 'recharts-surface';

/** Los `.js` de `dist/assets`, por su nombre. */
function losJs(): readonly string[] {
  return readdirSync(ASSETS).filter((nombre) => nombre.endsWith('.js') && statSync(join(ASSETS, nombre)).isFile());
}

function leer(nombre: string): string {
  return readFileSync(join(ASSETS, nombre), 'utf8');
}

/** Lo que `index.html` manda bajar: su guion de modulo y sus `modulepreload`. */
function loQueNombraElHtml(): readonly string[] {
  const html = readFileSync(join(DIST, 'index.html'), 'utf8');
  const patrones = [
    /<script type="module"[^>]*\ssrc="\/rentas\/assets\/([^"]+\.js)"/g,
    /<link rel="modulepreload"[^>]*\shref="\/rentas\/assets\/([^"]+\.js)"/g,
  ];
  return patrones.flatMap((patron) => [...html.matchAll(patron)].map((casa) => casa[1] ?? ''));
}

/**
 * Lo que el navegador baja al entrar, en cualquier destino: lo que nombra el HTML y lo que eso
 * importa ESTATICAMENTE, hasta el final. `from"./x.js"` y `import"./x.js"` son estaticos;
 * `import("./x.js")` no casa, a proposito.
 */
function loQueSeBajaAlEntrar(): readonly string[] {
  const vistos = new Set<string>();
  const pendientes = [...loQueNombraElHtml()];
  for (let nombre = pendientes.pop(); nombre !== undefined; nombre = pendientes.pop()) {
    if (vistos.has(nombre)) continue;
    vistos.add(nombre);
    for (const casa of leer(nombre).matchAll(/(?:from|import)\s*"\.\/([\w.-]+\.js)"/g)) {
      pendientes.push(casa[1] ?? '');
    }
  }
  return [...vistos];
}

/** Los kilobytes de un archivo, como los dice Vite. */
function kB(nombre: string): string {
  return (statSync(join(ASSETS, nombre)).size / 1000).toFixed(2);
}

test('EL CENTINELA: recharts esta en el paquete, y la entrada se encuentra', () => {
  // Sin esto, una marca que dejara de casar —recharts renombra su clase, o el grafico se va— o un
  // `index.html` cuyo patron no se reconociera dejarian la comprobacion de abajo buscando nada en
  // ninguna parte, y saldria verde sin haber medido.
  expect(loQueNombraElHtml().length, 'no se reconoce el guion de entrada de `dist/index.html`').toBeGreaterThan(0);
  const conRecharts = losJs().filter((nombre) => leer(nombre).includes(MARCA_DE_RECHARTS));
  expect(conRecharts, `ningun .js de \`dist/assets\` lleva «${MARCA_DE_RECHARTS}»`).not.toEqual([]);
});

test('recharts NO esta en lo que se baja al entrar: va en un trozo aparte', () => {
  const alEntrar = loQueSeBajaAlEntrar();
  const culpables = alEntrar.filter((nombre) => leer(nombre).includes(MARCA_DE_RECHARTS));

  expect(
    culpables,
    'recharts viaja en lo que se baja AL ENTRAR, en cualquier pantalla:\n' +
      `  ${culpables.map((nombre) => `${nombre} (${kB(nombre)} kB)`).join('\n  ')}\n\n` +
      '  El grafico dibuja UNA de las cuarenta pantallas (#298). Tiene que llegar por el `lazy()` de\n' +
      '  `src/piezas/GraficoDeRecaudacion.tsx`, que es un `import()` dinamico: si alguien lo importa\n' +
      '  estaticamente —aunque sea un tipo que no se borre, o una constante del lienzo—, Rollup lo\n' +
      '  devuelve a la entrada y las treinta y nueve pagan sus ~325 kB sin usarlos.',
  ).toEqual([]);
});

/**
 * La respuesta de `GET /indicadores/recaudacion` que se sirve en el navegador.
 *
 * **Sin una sola cifra de dinero**, como `la-insignia-no-pinta-verde-lo-parado.spec.ts`: lo que se
 * mide son barras, y una barra mide `pct`, que es un avance y no un importe. Los importes de las
 * filas van a `null` —la forma con que la operacion dice «sin cifrar»— y `cargado`, que el tipo
 * exige y el conector de `ini-flujo` no lee, a cero. Los dos avances son los de
 * `verificaciones/el-grafico-de-ini-flujo-se-dibuja.test.tsx`, distintos a proposito: con dos
 * iguales no se podria decir que cada barra mide la suya.
 */
const RECAUDACION: IndicadorDeRecaudacion = {
  ejercicio: 2026,
  fechaCalculo: '2026-09-16',
  calculadoEn: '2026-09-16T05:00:00-05:00',
  cargado: { importe: '0.00', actualizadoA: '2026-09-16' },
  kpis: [],
  paneles: [
    {
      title: 'Recaudacion por tributo',
      note: '',
      rows: [
        {
          label: 'Impuesto predial',
          sub: '',
          value: '',
          pct: 89,
          avanceConocido: true,
          importe: null,
          cargado: null,
          pendiente: null,
        },
        {
          label: 'Arbitrios municipales',
          sub: '',
          value: '',
          pct: 41,
          avanceConocido: true,
          importe: null,
          cargado: null,
          pendiente: null,
        },
      ],
    },
  ],
};

test('otra hoja NO pide el trozo del grafico; `ini-flujo` si, y dibuja sus barras', async ({ page }) => {
  await conLaSeguridadContestada(page);
  // Registrada DESPUES: en Playwright gana la ruta registrada la ultima, y la de la instalacion
  // contesta 404 a todo lo que no es seguridad.
  await page.route('**/rentas/api/v1/indicadores/recaudacion**', (ruta) =>
    ruta.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(RECAUDACION) }),
  );

  const trozosDelGrafico = losJs().filter((nombre) => leer(nombre).includes(MARCA_DE_RECHARTS));
  expect(trozosDelGrafico, 'no hay trozo con recharts que esperar').not.toEqual([]);
  const pedidos: string[] = [];
  page.on('request', (peticion) => {
    pedidos.push(peticion.url());
  });
  const pidioElGrafico = () =>
    pedidos.filter((url) => trozosDelGrafico.some((nombre) => url.endsWith(`/assets/${nombre}`)));

  // Primero una hoja cualquiera, que es lo que abre quien entra: el trozo no se pide.
  await abrir(page, 'ini-panel');
  await expect(page.getByRole('heading', { level: 1, name: 'Panel', exact: true })).toBeVisible();
  expect(pidioElGrafico(), 'abrir `ini-panel` pidio el trozo de recharts').toEqual([]);

  // Y ahora la del grafico. Si la pieza perezosa no llega a resolverse —un `import()` que nunca
  // contesta, un trozo que no se sirve—, la tarjeta se queda con su esqueleto y aqui no hay barras.
  await abrir(page, 'ini-flujo');
  const grafico = page.locator('[data-grafico="grafico-de-recaudacion"]');
  const barras = grafico.locator('.recharts-bar-rectangle rect');
  await expect(barras, 'el grafico de `ini-flujo` no llego a dibujar sus barras').toHaveCount(2);
  expect(pidioElGrafico(), 'las barras salieron sin pedir el trozo de recharts').not.toEqual([]);

  // Y miden lo que llego: 89 es mas larga que 41. Que haya dos rectangulos no dice que se dibujen.
  const anchos = await barras.evaluateAll((rects) => rects.map((rect) => Number(rect.getAttribute('width'))));
  expect(anchos[0] ?? 0).toBeGreaterThan(anchos[1] ?? 0);
  expect(anchos[1] ?? 0).toBeGreaterThan(0);
});
