import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

import { expect, test, type Page } from '@playwright/test';

import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **Los temas llegan al NAVEGADOR, y no solo al archivo** (#111, AC3 y AC4).
 *
 * <h2>Por que esta guarda existe, dicho sin rodeos</h2>
 *
 * `@kamayuk/ui` tenia `estilos/temas.css` con las seis paletas, su generador, su prueba de
 * regeneracion y su contraste medido. Todo verde. Y **no lo importaba nadie**: el `package.json`
 * no lo exportaba y ninguna hoja lo arrastraba. Medido en este repositorio, sobre
 * `dist/assets/*.css`: `data-tema` 0, `data-modo` 0, `prefers-color-scheme` 0. `ProveedorDeTema`
 * estampaba dos atributos sobre un documento sin una sola regla que los leyera.
 *
 * Todas las pruebas que existian median **el archivo**. Un archivo perfectamente escrito y
 * perfectamente inalcanzable las pasa todas. Asi que esta mide el otro extremo del camino: lo que
 * el servidor entrega y lo que el navegador computa.
 *
 * <h2>Las tres cosas que mide, y por que hacen falta las tres</h2>
 *
 *   1. **Lo que se sirve.** La hoja se pide POR SU URL al servidor que sirve el `dist`, no se lee
 *      del disco: es el mismo byte que recibe una ventanilla.
 *   2. **Lo que el `<html>` lleva.** Los dos ejes son dos atributos, y el segundo **se quita** —no
 *      se pone en claro— cuando se devuelve el mando al equipo.
 *   3. **Lo que el navegador PINTA.** Es lo unico que prueba que el selector es el correcto, que
 *      la cascada lo alcanza y que no quedo enterrado en un `@layer` que pierde. Un atributo que
 *      cambia de valor no dice nada de esto; el color computado, si.
 *
 * <h2>Los valores esperados salen de la libreria, no de una tabla de aqui</h2>
 *
 * Las seis paletas son **generadas** —el oscuro se deriva en OKLCH— y copiarlas aqui seria una
 * segunda fuente que se queda vieja sola. Se leen de `@kamayuk/ui/estilos/temas.css`, alcanzado
 * **por el enlace** y no por una ruta al clon hermano.
 *
 * Y para que la comparacion no sea circular —el archivo contra si mismo— hay un ancla: la paleta
 * clara de `institucional` tiene que ser la del artboard, `#f2f6f9`, que es la que `se-ve.spec.ts`
 * clava por su cuenta. Si la libreria regenerara otra cosa, esto sale rojo.
 */

const RAIZ_DE_UI = dirname(createRequire(import.meta.url).resolve('@kamayuk/ui'));
const HOJA_DE_LOS_TEMAS = join(RAIZ_DE_UI, 'estilos', 'temas.css');

/** El fondo que el artboard V8 dibuja. El ancla contra la que se mide la libreria. */
const FONDO_DEL_ARTBOARD = '#f2f6f9';

type Identidad = 'institucional' | 'alto-contraste' | 'sepia';
type Modo = 'claro' | 'oscuro';

interface Paleta {
  readonly identidad: Identidad;
  readonly modo: Modo;
  readonly fondo: string;
  readonly tinta: string;
  readonly azul: string;
  readonly superficie: string;
}

/**
 * Las seis paletas, leidas del archivo generado de la libreria.
 *
 * Cada bloque viene rotulado con un comentario `identidad/modo`, y el oscuro esta escrito dos
 * veces —bajo `prefers-color-scheme` y bajo `[data-modo='oscuro']`— con los mismos valores. El
 * mapa los une: son **seis** combinaciones, no nueve.
 */
function lasSeisDeLaLibreria(): readonly Paleta[] {
  const css = readFileSync(HOJA_DE_LOS_TEMAS, 'utf8');
  const paletas = new Map<string, Paleta>();
  const bloques = css.matchAll(
    /\/\*\s*(institucional|alto-contraste|sepia)\/(claro|oscuro)[^*]*\*\/([\s\S]*?)(?=\/\*|$)/g,
  );
  for (const [, identidad, modo, cuerpo] of bloques) {
    const token = (nombre: string) =>
      new RegExp(`--color-${nombre}:\\s*(#[0-9a-f]{6})`, 'i').exec(cuerpo ?? '')?.[1] ?? '';
    paletas.set(`${identidad}/${modo}`, {
      identidad: identidad as Identidad,
      modo: modo as Modo,
      fondo: token('fondo'),
      tinta: token('tinta'),
      azul: token('azul'),
      superficie: token('superficie'),
    });
  }
  return [...paletas.values()];
}

const SEIS = lasSeisDeLaLibreria();

/** Como se lee cada opcion en el mando. Es el texto del boton, no el valor del atributo. */
const ROTULO: Readonly<Record<Identidad | Modo | 'sistema', string>> = {
  institucional: 'Institucional',
  'alto-contraste': 'Alto contraste',
  sepia: 'Sepia',
  claro: 'Claro',
  oscuro: 'Oscuro',
  sistema: 'El del sistema',
};

/** `#005284` -> `rgb(0, 82, 132)`, que es como el navegador devuelve un color pintado. */
function comoLoDevuelveElNavegador(hex: string): string {
  const n = Number.parseInt(hex.slice(1), 16);
  return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
}

/** El valor computado de un token en el documento. Es lo que la cascada resolvio, no lo escrito. */
async function tokenComputado(pagina: Page, nombre: string): Promise<string> {
  return pagina.evaluate(
    (t) => getComputedStyle(document.body).getPropertyValue(t).trim(),
    `--color-${nombre}`,
  );
}

async function abrirElMando(pagina: Page): Promise<void> {
  await pagina.locator('[data-slot="abrir-la-sesion"]').click();
  await pagina.getByRole('menuitem', { name: 'Preferencias' }).click();
  await expect(pagina.locator('[data-slot="mando-de-tema"]')).toBeVisible();
}

/** Elige una combinacion POR EL MANDO, que es como la elige una persona. */
async function elegir(
  pagina: Page,
  identidad: Identidad,
  modo: Modo | 'sistema',
): Promise<void> {
  await abrirElMando(pagina);
  await pagina.getByRole('radio', { name: ROTULO[identidad], exact: true }).check();
  await pagina.getByRole('radio', { name: ROTULO[modo], exact: true }).check();
  await pagina.keyboard.press('Escape');
  await expect(pagina.locator('[data-slot="mando-de-tema"]')).toBeHidden();
}

/** Todo el CSS que la pagina carga, pedido POR SU URL al servidor que sirve el `dist`. */
async function elCssQueSeSirve(pagina: Page): Promise<string> {
  const hojas = await pagina.evaluate(() =>
    [...document.querySelectorAll('link[rel="stylesheet"]')].map((l) => (l as HTMLLinkElement).href),
  );
  const enLinea = await pagina.evaluate(() =>
    [...document.querySelectorAll('style')].map((s) => s.textContent ?? ''),
  );
  const pedidas = await Promise.all(
    hojas.map(async (url) => (await pagina.request.get(url)).text()),
  );
  return [...pedidas, ...enLinea].join('\n');
}

test.beforeEach(async ({ page }) => {
  await conLaSeguridadContestada(page);
});

test('EL CENTINELA: la libreria publica seis paletas distintas, y la clara es la del artboard', () => {
  // Sin esto, un cambio de forma en `temas.css` dejaria `SEIS` vacio y TODO lo de abajo pasaria en
  // verde sin haber comparado nada. Es como este repositorio ya se quedo sin guarda dos veces.
  expect(SEIS, 'no se leyeron las seis paletas de `@kamayuk/ui`').toHaveLength(6);
  expect(SEIS.every((p) => /^#[0-9a-f]{6}$/i.test(p.fondo))).toBe(true);
  expect(new Set(SEIS.map((p) => p.fondo)).size, 'hay dos paletas con el mismo fondo').toBe(6);

  // El ancla: sin ella, todo lo demas seria el archivo comparado consigo mismo.
  const institucionalClaro = SEIS.find((p) => p.identidad === 'institucional' && p.modo === 'claro');
  expect(institucionalClaro?.fondo).toBe(FONDO_DEL_ARTBOARD);
});

test('el CSS QUE SE SIRVE trae las seis combinaciones, y los dos ejes', async ({ page }) => {
  await abrir(page, 'ini-panel');
  const css = await elCssQueSeSirve(page);

  // Sin esto, una hoja vacia —un `link` que devuelve 404 con codigo 200, por ejemplo— dejaria
  // pasar todas las comprobaciones de abajo.
  expect(css.length, 'no se sirvio ni un byte de CSS').toBeGreaterThan(10_000);

  // Los dos ejes y el tercero que no es un atributo: lo que el equipo tenga puesto.
  //
  // Se compara un booleano y NO con `toContain`, a proposito: el CSS servido son 35 kB en una
  // sola linea, y `toContain` los vuelca enteros en el rojo. Medido al demostrar que esta guarda
  // muerde — el mensaje util quedaba sepultado bajo el `dist` completo.
  const sinSenal = ['data-tema', 'data-modo', 'prefers-color-scheme'].filter((s) => !css.includes(s));
  expect(
    sinSenal,
    `El CSS servido no menciona ${sinSenal.join(', ')}: los temas no salieron del paquete.`,
  ).toEqual([]);

  // Y las seis paletas, cada una por dos de sus valores. Medido antes de #111 y de
  // `kamayuk-lib`#23: de esto llegaba exactamente una, la clara de `institucional`.
  const plano = css.replace(/\s+/g, '').toLowerCase();
  const ausentes = SEIS.flatMap((p) =>
    [
      { que: 'fondo', valor: p.fondo },
      { que: 'tinta', valor: p.tinta },
    ]
      .filter(({ que, valor }) => !plano.includes(`--color-${que}:${valor}`))
      .map(({ que, valor }) => `  ${p.identidad}/${p.modo}: falta --color-${que}: ${valor}`),
  );
  expect(
    ausentes,
    'El CSS que se sirve no trae las seis paletas:\n' +
      `${ausentes.join('\n')}\n\n` +
      '  El proveedor estamparia los atributos sobre un documento sin reglas que los lean, que es\n' +
      '  el defecto del que viene este issue: archivo perfecto, camino roto, todo en verde.',
  ).toEqual([]);
});

test('el `<html>` lleva los dos atributos, y «el del sistema» QUITA el segundo', async ({ page }) => {
  await abrir(page, 'ini-panel');

  // De fabrica: la identidad del servicio puesta, y el modo SIN poner —que no es lo mismo que
  // puesto en claro: ausente significa «lo que diga el equipo».
  await expect(page.locator('html')).toHaveAttribute('data-tema', 'institucional');
  expect(await page.evaluate(() => document.documentElement.hasAttribute('data-modo'))).toBe(false);

  await elegir(page, 'sepia', 'oscuro');
  await expect(page.locator('html')).toHaveAttribute('data-tema', 'sepia');
  await expect(page.locator('html')).toHaveAttribute('data-modo', 'oscuro');

  await elegir(page, 'sepia', 'sistema');
  await expect(page.locator('html')).toHaveAttribute('data-tema', 'sepia');
  expect(
    await page.evaluate(() => document.documentElement.hasAttribute('data-modo')),
    'devolver el mando al equipo dejo el atributo puesto: quien lo hace se queda clavado en el modo que tuviera',
  ).toBe(false);
});

/**
 * **Las seis, una por una, sobre el color COMPUTADO** (AC4).
 *
 * No se mira el atributo: se mira lo que el navegador resolvio. Con el atributo basta que React
 * haga su trabajo; con el color tienen que ser ciertas ademas otras cuatro cosas —que la hoja
 * viaje, que el selector case, que la cascada lo alcance y que no lo tape un `@layer`—, y son
 * exactamente las cuatro que fallaron.
 */
for (const paleta of SEIS) {
  test(`la pagina CAMBIA DE COLOR: ${paleta.identidad}/${paleta.modo}`, async ({ page }) => {
    await abrir(page, 'ini-panel');
    await elegir(page, paleta.identidad, paleta.modo);

    expect(await tokenComputado(page, 'fondo'), 'el lienzo').toBe(paleta.fondo);
    expect(await tokenComputado(page, 'tinta'), 'la tinta').toBe(paleta.tinta);

    // Y en pixeles de verdad, no en tokens: la cabecera azul de la tarjeta y su papel. Es lo que
    // `se-ve.spec.ts` mide para la paleta clara, aqui para las seis.
    const cabecera = page.locator('[data-slot="tarjeta-cabecera"]').first();
    await expect(cabecera).toBeVisible();
    expect(
      await cabecera.evaluate((e) => getComputedStyle(e).backgroundColor),
      'la cabecera de la tarjeta no se pinto con el azul de esta paleta',
    ).toBe(comoLoDevuelveElNavegador(paleta.azul));

    const tarjeta = page.locator('[data-slot="tarjeta"]').first();
    expect(
      await tarjeta.evaluate((e) => getComputedStyle(e).backgroundColor),
      'el papel de la tarjeta no se pinto con la superficie de esta paleta',
    ).toBe(comoLoDevuelveElNavegador(paleta.superficie));
  });
}

test('la eleccion SOBREVIVE a recargar', async ({ page }) => {
  await abrir(page, 'ini-panel');
  await elegir(page, 'alto-contraste', 'oscuro');

  await page.reload();
  await page.locator('[data-slot="barra-global"], header').first().waitFor({ timeout: 15_000 });

  const esperada = SEIS.find((p) => p.identidad === 'alto-contraste' && p.modo === 'oscuro');
  await expect(page.locator('html')).toHaveAttribute('data-tema', 'alto-contraste');
  await expect(page.locator('html')).toHaveAttribute('data-modo', 'oscuro');
  expect(await tokenComputado(page, 'fondo')).toBe(esperada?.fondo);
});

/**
 * **Con el equipo en oscuro y sin elegir modo, manda el equipo — y se puede salir de ahi.**
 *
 * Son las dos mitades de `modo = null`. La primera es lo que #111 ya servia sin mando ninguno: el
 * `@media` de la libreria basta. La segunda es la que el mando aporta, y la que el `:not([data-modo
 * ='claro'])` del archivo generado sostiene — sin ese `:not`, pedir claro en una maquina puesta en
 * oscuro no serviria de nada.
 */
test.describe('con el equipo puesto en oscuro', () => {
  test.use({ colorScheme: 'dark' });

  test('sin elegir modo manda el equipo, y elegir «Claro» saca de ahi', async ({ page }) => {
    await abrir(page, 'ini-panel');

    const oscuro = SEIS.find((p) => p.identidad === 'institucional' && p.modo === 'oscuro');
    expect(
      await tokenComputado(page, 'fondo'),
      'con el equipo en oscuro y sin modo elegido, la interfaz no se puso oscura',
    ).toBe(oscuro?.fondo);

    await elegir(page, 'institucional', 'claro');
    expect(
      await tokenComputado(page, 'fondo'),
      'pedir claro en un equipo puesto en oscuro no saco de oscuro',
    ).toBe(FONDO_DEL_ARTBOARD);
  });
});
