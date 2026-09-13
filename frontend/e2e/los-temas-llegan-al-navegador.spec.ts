import { readFileSync } from 'node:fs';

import { expect, test, type Page } from '@playwright/test';

import { UMBRAL_DE_TEXTO, conDosDecimales, contraste } from '../verificaciones/contraste.ts';
import { hermanaDe, hojaDeUi } from '../verificaciones/especificadores.ts';
import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **Los temas llegan al NAVEGADOR, y no solo al archivo** (#111, AC3 y AC4; #134).
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
 * <h2>Las cuatro cosas que mide, y por que hacen falta las cuatro</h2>
 *
 *   1. **Lo que se sirve.** La hoja se pide POR SU URL al servidor que sirve el `dist`, no se lee
 *      del disco: es el mismo byte que recibe una ventanilla.
 *   2. **Lo que el `<html>` lleva.** Los dos ejes son dos atributos, y el segundo **se quita** —no
 *      se pone en claro— cuando se devuelve el mando al equipo.
 *   3. **Lo que el navegador PINTA.** Es lo unico que prueba que el selector es el correcto, que
 *      la cascada lo alcanza y que no quedo enterrado en un `@layer` que pierde. Un atributo que
 *      cambia de valor no dice nada de esto; el color computado, si.
 *   4. **Lo que pinta el navegador POR SU CUENTA** (#134): `color-scheme` y, en pixeles, el canal
 *      de la barra de desplazamiento. Las tres de arriba se repartieron el trabajo por paleta y
 *      dejaron fuera una tercera capa que no es ni un token ni un pixel de la hoja — y por eso
 *      `kamayuk-lib`#33, que era exactamente eso, entro, vivio y se arreglo sin mover un rojo aqui.
 *      Ver el docblock del ultimo camino para por que se mide la barra y no una casilla.
 *
 * <h2>Los valores esperados salen de la libreria, no de una tabla de aqui</h2>
 *
 * Las seis paletas son **generadas** —el oscuro se deriva en OKLCH— y copiarlas aqui seria una
 * segunda fuente que se queda vieja sola. Se leen del `temas.css` de la libreria, alcanzado **por
 * el especificador de la hoja que lo arrastra** y no por una ruta al clon hermano (#138: ver
 * `HOJA_DE_LOS_TEMAS`, mas abajo).
 *
 * Y para que la comparacion no sea circular —el archivo contra si mismo— hay un ancla: la paleta
 * clara de `institucional` tiene que ser la del artboard, `#f2f6f9`, que es la que `se-ve.spec.ts`
 * clava por su cuenta. Si la libreria regenerara otra cosa, esto sale rojo.
 */

/**
 * **`temas.css`, alcanzado COMO LO ALCANZA EL NAVEGADOR** (#138).
 *
 * No tiene entrada propia en el `exports` de `@kamayuk/ui`, y es deliberado (`kamayuk-lib`#23): se
 * ARRASTRA desde la hoja publicada, porque con una entrada propia el consumidor tendria que
 * escribir dos `import` y quien se olvidara del segundo se quedaria sin paletas y sin que nada se
 * lo dijera.
 *
 * Asi que el camino aqui es el mismo que el del navegador: resolver `@kamayuk/ui/estilos.css` por
 * su especificador —que SI pasa por el `exports`— y de ahi seguir el `@import` relativo que esa
 * hoja escribe. Con `join(raiz, 'estilos', 'temas.css')` se leia el archivo aunque el paquete
 * hubiera dejado de publicar la hoja que lo arrastra, o sea aunque el navegador no recibiera ni
 * una de las seis paletas.
 */
const HOJA_DE_LOS_TEMAS = hermanaDe(hojaDeUi(), './temas.css');

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

/** La senal que el `<html>` le da al navegador: `light` o `dark`. */
async function elEsquemaDelDocumento(pagina: Page): Promise<string> {
  return pagina.evaluate(() => getComputedStyle(document.documentElement).colorScheme);
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
  // Y el velo del cajon, DESAPARECIDO. No es celo: mientras siga montado intercepta el puntero, y
  // la segunda llamada seguida a `elegir()` se queda esperando al boton de la sesion hasta agotar
  // el tiempo. Medido al escribir la comprobacion de #134, que cambia de tema seis veces sin
  // recargar: «<div data-slot="velo-del-cajon"> intercepts pointer events», 30 s.
  await expect(pagina.locator('[data-slot="velo-del-cajon"]')).toHaveCount(0);
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

/**
 * **Con las barras de desplazamiento VISIBLES, y hace falta decirlo** (#134).
 *
 * Playwright arranca Chromium sin cabeza con `--hide-scrollbars`, y con esa bandera el canal no
 * ocupa sitio ni se pinta: medido en este arbol, `offsetWidth - clientWidth` daba **0** en los dos
 * contenedores que desbordan, y la franja de la derecha devolvia el fondo de la pagina y nada mas.
 * O sea que la comprobacion de mas abajo no habria medido la barra: habria medido el lienzo, que es
 * lo que la hoja pinta, y habria pasado en verde con el defecto puesto.
 *
 * Va a nivel de archivo porque `launchOptions` es una opcion del *worker*: dentro de un `describe`
 * Playwright la rechaza. No molesta a lo demas de aqui —el resto mide colores computados y
 * atributos— y no alcanza a `se-ve.spec.ts`, que corre en otro archivo y sigue midiendo que ninguna
 * tabla desplace la pagina con el navegador que siempre tuvo.
 */
test.use({ launchOptions: { ignoreDefaultArgs: ['--hide-scrollbars'] } });

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

    // Y LA SENAL AL NAVEGADOR (#134). No es un token mas: los `--color-*` pintan lo que pinta la
    // hoja, y `color-scheme` pinta lo que dibuja el navegador por su cuenta —controles nativos,
    // barra de desplazamiento, lienzo—. Sin ella la pantalla se oscurece entera menos eso, que es
    // exactamente el defecto de `kamayuk-lib`#33: entro, vivio y se arreglo sin mover un rojo aqui.
    expect(
      await elEsquemaDelDocumento(page),
      'el `<html>` no le dice al navegador en que modo dibujar LO SUYO',
    ).toBe(paleta.modo === 'oscuro' ? 'dark' : 'light');

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

/** Lo que el navegador computo sobre una de las dos frases del cajon. */
interface Medida {
  readonly frase: string;
  /** El color del texto, tal como `getComputedStyle` lo devuelve. */
  readonly tinta: string;
  /** El papel de verdad: el primer ancestro con fondo que no sea transparente. */
  readonly papel: string;
}

/**
 * Las dos frases que explican los ejes, medidas **con el cajon abierto**.
 *
 * El papel NO se da por sabido: un `<p>` no tiene fondo propio, asi que se sube por los ancestros
 * hasta el primero que pinte uno. Escribir «el panel del cajon es `bg-fondo`» seria copiar aqui un
 * dato de la libreria, y el dia que el panel cambie de papel esto mediria el contraste de ayer.
 */
async function medidaDeLasNotas(pagina: Page): Promise<readonly Medida[]> {
  const notas = pagina.locator('[data-slot="mando-de-tema"] [data-slot="nota-del-eje"]');
  await expect(notas).toHaveCount(2);
  return notas.evaluateAll((elementos) =>
    elementos.map((elemento) => {
      let papel = '';
      for (let nodo: Element | null = elemento; nodo !== null; nodo = nodo.parentElement) {
        const fondo = getComputedStyle(nodo).backgroundColor;
        if (fondo !== '' && fondo !== 'transparent' && fondo !== 'rgba(0, 0, 0, 0)') {
          papel = fondo;
          break;
        }
      }
      return {
        frase: (elemento.textContent ?? '').trim(),
        tinta: getComputedStyle(elemento).color,
        papel,
      };
    }),
  );
}

/**
 * **Las dos frases del cajon se LEEN, y en las seis combinaciones** (#140, AC1).
 *
 * <h2>Por que en el navegador y no sobre la hoja</h2>
 *
 * Porque el contraste que importa no es el de dos tokens escritos: es el del color que el elemento
 * acaba teniendo sobre el papel que acaba teniendo debajo. Entre una cosa y la otra hay una
 * cascada, seis paletas, un `@media` y un panel que pinta su propio fondo — y son exactamente las
 * piezas que ya fallaron una vez en silencio (#111). Leer `text-tinta-3` en el `className` no dice
 * nada de ninguna de ellas.
 *
 * <h2>Y en las SEIS, porque las seis derivan distinto</h2>
 *
 * El oscuro se deriva en OKLCH y el sepia parte de otro papel: `--tinta-3` sobre `--fondo` va de
 * 4,88:1 en `sepia/claro` a 16,71:1 en `alto-contraste/claro`, medido sobre la hoja. La de por
 * omision —`institucional/claro`, 5,07:1— es de las holgadas, asi que comprobar solo esa es
 * exactamente comprobar la que no aprieta.
 *
 * <h2>Una prueba y no seis</h2>
 *
 * El arnes corre con un solo trabajador y sin paralelo: seis caminos serian seis arranques de la
 * aplicacion para medir doce frases. Aqui se arranca una vez y se recorren las seis combinaciones
 * por el mando, que es ademas como las recorre una persona.
 */
test('las dos frases del cajon se LEEN: WCAG 1.4.3 en las seis combinaciones', async ({ page }) => {
  await abrir(page, 'ini-panel');

  const medido: string[] = [];
  const flojas: string[] = [];
  for (const paleta of SEIS) {
    await elegir(page, paleta.identidad, paleta.modo);
    await abrirElMando(page);

    for (const nota of await medidaDeLasNotas(page)) {
      const razon = contraste(nota.tinta, nota.papel);
      const linea =
        `  ${paleta.identidad}/${paleta.modo} · «${nota.frase}»: ${nota.tinta} sobre ` +
        `${nota.papel} = ${conDosDecimales(razon)}:1`;
      medido.push(linea);
      if (razon < UMBRAL_DE_TEXTO) flojas.push(linea);
    }

    await page.keyboard.press('Escape');
    await expect(page.locator('[data-slot="mando-de-tema"]')).toBeHidden();
  }

  // EL CENTINELA. Sin esto, un `data-slot` renombrado o un cajon que no abriera dejarian la lista
  // vacia, y una lista vacia de frases flojas es exactamente lo que esta prueba da por bueno.
  expect(medido.length, 'no se midieron las doce frases: dos por cada una de las seis').toBe(12);

  expect(
    flojas,
    'Hay texto del cajon de Preferencias por debajo del umbral de WCAG 1.4.3 ' +
      `(${UMBRAL_DE_TEXTO}:1 para texto normal, y estas frases van a 12 px):\n` +
      `${flojas.join('\n')}\n\n` +
      '  Es el cajon donde vive el mando de accesibilidad visual, y la segunda frase es la unica\n' +
      '  que dice que hace la opcion «El del sistema».',
  ).toEqual([]);
});

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
    // La senal al navegador va por el OTRO bloque de la hoja —el del `@media`—, que es una regla
    // distinta de la del atributo y puede quedarse sin ella por su cuenta (#134).
    expect(
      await elEsquemaDelDocumento(page),
      'con el equipo en oscuro, el `<html>` no le dijo al navegador que dibujara en oscuro',
    ).toBe('dark');

    await elegir(page, 'institucional', 'claro');
    expect(
      await tokenComputado(page, 'fondo'),
      'pedir claro en un equipo puesto en oscuro no saco de oscuro',
    ).toBe(FONDO_DEL_ARTBOARD);
    expect(
      await elEsquemaDelDocumento(page),
      'pedir claro dejo al navegador dibujando lo suyo en oscuro',
    ).toBe('light');
  });
});

/* ── Lo que pinta el NAVEGADOR, y no la hoja ───────────────────────────────────────────── */

/**
 * El canal de la barra de desplazamiento, leido EN PIXELES.
 *
 * Se busca subiendo desde la primera tarjeta hasta el primer antepasado que desplace de verdad
 * —`overflow-y` resuelto y contenido mas alto que el hueco—, que es el contenedor del cuerpo de la
 * pantalla. Por su clase no se puede buscar: no lleva `data-slot`, y la clase es de `@kamayuk/shell`.
 *
 * Se lleva el desplazamiento ARRIBA y se recorta el tramo inferior: con el pulgar en lo alto, lo de
 * abajo es canal y solo canal. Que el recorte salga de un solo color es parte de la comprobacion —si
 * saliera de varios, se estaria midiendo contenido y no la barra—.
 */
async function elCanalDeLaBarra(
  pagina: Page,
): Promise<{ readonly ancho: number; readonly colores: readonly string[] }> {
  const donde = await pagina.evaluate(() => {
    let caja: HTMLElement | null = document.querySelector('[data-slot="tarjeta"]');
    while (caja !== null) {
      const estilo = getComputedStyle(caja);
      const desplaza = estilo.overflowY === 'auto' || estilo.overflowY === 'scroll';
      if (desplaza && caja.scrollHeight > caja.clientHeight) break;
      caja = caja.parentElement;
    }
    if (caja === null) return null;
    caja.scrollTop = 0;
    const ancho = caja.offsetWidth - caja.clientWidth;
    const marco = caja.getBoundingClientRect();
    return {
      ancho,
      x: marco.right - ancho,
      y: marco.top + marco.height * 0.8,
      alto: Math.round(marco.height * 0.15),
    };
  });
  expect(donde, 'ninguna pantalla desbordo: no hay barra que medir').not.toBeNull();
  if (donde === null) return { ancho: 0, colores: [] };
  // El centinela de la bandera: con `--hide-scrollbars` puesta el canal no ocupa sitio, y recortar
  // cero pixeles de ancho ni siquiera es una captura valida.
  expect(
    donde.ancho,
    'el canal no ocupa sitio: la barra esta oculta y aqui no se estaria midiendo nada',
  ).toBeGreaterThan(0);

  const captura = await pagina.screenshot({
    clip: { x: donde.x, y: donde.y, width: donde.ancho, height: donde.alto },
  });
  // El PNG lo descifra el propio navegador sobre un `<canvas>`: ya esta abierto, y traerse un
  // descodificador de imagenes para leer quince pixeles de ancho seria una dependencia nueva.
  const colores = await pagina.evaluate(async (base64) => {
    const imagen = new Image();
    imagen.src = `data:image/png;base64,${base64}`;
    await imagen.decode();
    const lienzo = document.createElement('canvas');
    lienzo.width = imagen.width;
    lienzo.height = imagen.height;
    const pincel = lienzo.getContext('2d');
    if (pincel === null) return [];
    pincel.drawImage(imagen, 0, 0);
    const datos = pincel.getImageData(0, 0, lienzo.width, lienzo.height).data;
    const vistos = new Set<string>();
    for (let i = 0; i < datos.length; i += 4) {
      vistos.add(`${datos[i]},${datos[i + 1]},${datos[i + 2]}`);
    }
    return [...vistos];
  }, captura.toString('base64'));
  return { ancho: donde.ancho, colores };
}

/** La luminancia de un `r,g,b`, para poder decir «claro» y «oscuro» sin nombrar un color. */
function luminancia(rgb: string): number {
  const [r = 0, v = 0, a = 0] = rgb.split(',').map(Number);
  return 0.2126 * r + 0.7152 * v + 0.0722 * a;
}

/**
 * **Un control que pinta EL NAVEGADOR, en claro y en oscuro** (#134, AC2).
 *
 * <h2>Por que la barra de desplazamiento y no una casilla o un campo</h2>
 *
 * Porque estaba medido, y es el modo de fallo que este issue vino a cerrar: **un control al que la
 * hoja ya le pinta todo mide la hoja otra vez**. Se volcaron las propiedades computadas del
 * `<input>` de verdad de una pantalla en las seis combinaciones, y la unica que cambia con el modo
 * sin cambiar con la identidad es `color-scheme`: el fondo, la tinta, el filo y el cursor los pone
 * `CONTROL` de `@kamayuk/ui`. La casilla ni siquiera es nativa —Radix dibuja la marca en un `<svg>`
 * nuestro— y el tirador del `<textarea>` salio identico con la hoja rota y con la hoja sana, porque
 * el navegador lo deriva del fondo del propio control y no de `color-scheme`.
 *
 * El canal de la barra, en cambio, no lo toca ni una regla de este producto: no hay un solo
 * `scrollbar-color` ni un `::-webkit-scrollbar` en el CSS servido.
 *
 * <h2>Las dos mitades, y por que van en la misma prueba</h2>
 *
 *   1. **Cambia con el modo**: el canal es casi blanco en las tres claras y gris oscuro en las tres
 *      oscuras. Eso es la capa del navegador moviendose.
 *   2. **NO cambia con la identidad**: las tres claras dan el MISMO pixel, y las tres oscuras otro.
 *      Si cambiara con la identidad seria un token, o sea la hoja, y no probaria nada nuevo.
 *
 * Separadas, la primera sola no distingue «mide el navegador» de «mide la hoja». Juntas, si.
 */
test('la BARRA DE DESPLAZAMIENTO —que pinta el navegador— cambia con el modo y no con la identidad', async ({
  page,
}) => {
  // Hueco corto a proposito: el cuerpo de `fis-actas` tiene que desbordar para que haya barra. Y es
  // la pantalla con los cinco controles del interprete —texto, desplegable, fecha, casilla y area—,
  // que son los que se midieron para descartarlos.
  await page.setViewportSize({ width: 1280, height: 500 });
  await abrir(page, 'fis-actas');

  const medido = new Map<string, string>();
  for (const paleta of SEIS) {
    await elegir(page, paleta.identidad, paleta.modo);
    const { colores } = await elCanalDeLaBarra(page);
    expect(
      colores,
      `${paleta.identidad}/${paleta.modo}: el recorte no salio de un solo color, o sea que no es el canal`,
    ).toHaveLength(1);

    const color = colores[0] ?? '';
    const claridad = luminancia(color);
    if (paleta.modo === 'oscuro') {
      expect(
        claridad,
        `${paleta.identidad}/${paleta.modo}: la pagina se oscurecio y el navegador siguio dibujando ` +
          `la barra en claro (${color}). Es lo que pasa cuando la paleta oscura no declara ` +
          '`color-scheme: dark`: la pantalla se oscurece entera menos lo que pinta el navegador.',
      ).toBeLessThan(100);
    } else {
      expect(
        claridad,
        `${paleta.identidad}/${paleta.modo}: la barra no se pinto en claro (${color})`,
      ).toBeGreaterThan(200);
    }
    medido.set(`${paleta.identidad}/${paleta.modo}`, color);
  }

  // La otra mitad: mismo modo, otra identidad, MISMO pixel. Es lo que separa «mide el navegador»
  // de «mide la hoja»: un token cambia entre las tres identidades, y esto no.
  for (const modo of ['claro', 'oscuro'] as const) {
    const suyos = SEIS.filter((p) => p.modo === modo).map(
      (p) => medido.get(`${p.identidad}/${modo}`) ?? '',
    );
    expect(
      new Set(suyos).size,
      `las tres identidades en ${modo} dieron barras distintas (${suyos.join(' | ')}): ` +
        'eso es un token, o sea la hoja, y no la capa que esta prueba existe para medir',
    ).toBe(1);
  }
});
