import { expect, test } from '@playwright/test';

/**
 * **Sin emisor levantado, la pagina NO se queda en blanco** (#112).
 *
 * <h2>Lo que se medio antes de escribir esto</h2>
 *
 * `yarn dev` y nada mas levantado, Chromium sin cabeza, seis segundos tras la carga:
 *
 * ```
 * body.innerText  -> ""
 * body.innerHTML  -> ""
 * peticion fallida: http://localhost:8181/realms/kamayuk/protocol/openid-connect/auth?...
 *                   net::ERR_CONNECTION_REFUSED
 * consola: [vite] connected  .  React DevTools        <- ni un error
 * ```
 *
 * Pagina en blanco y consola limpia: nada que leer en ninguna parte. Y el marco principal
 * navegando a `chrome-error://chromewebdata/`, o sea que el documento de antes ya no manda.
 *
 * <h2>Por que las DOS ramas, y por que aqui</h2>
 *
 * No montar es CORRECTO cuando la puerta contesta: `entrar()` navega fuera y dibujar despues seria
 * dibujar sobre un documento que el navegador esta a punto de tirar. Un arreglo que montara
 * siempre —un `setTimeout`, por ejemplo— cerraria este issue y abriria un parpadeo en el camino
 * bueno, en verde. Asi que se miden las dos, y en un navegador de verdad: es el unico sitio donde
 * «la navegacion ocurrio» y «la navegacion se rechazo» son dos cosas distintas. En jsdom,
 * `location.assign` es un espia y las dos son la misma.
 *
 * <h2>El emisor se apaga DESDE EL ARNES, no se da por apagado</h2>
 *
 * `route(...).abort('connectionrefused')` produce el mismo `net::ERR_CONNECTION_REFUSED` que se
 * midio, y lo produce **haya o no** un Keycloak escuchando en el puesto de quien ejecuta esto. Dar
 * por hecho que el 8181 esta libre haria una prueba que se pone roja por tener la plataforma
 * levantada, que es justo el estado en que todo va bien.
 */

/**
 * **La paleta contra la que se mide el aviso es `institucional/claro`** (#111).
 *
 * Desde `kamayuk-lib`#23 el CSS servido trae las seis combinaciones, y desde #111 el proveedor de
 * tema envuelve tambien esta pantalla —a proposito: quien eligio sepia u oscuro no debe encontrarse
 * el aviso de averia con la paleta de otro—. Los dos colores que este archivo clava son los del
 * artboard, o sea la combinacion clara, asi que el eje se declara en vez de heredarse. El motivo
 * largo, con la medicion que lo sostiene, en `se-ve.spec.ts`.
 */
test.use({ colorScheme: 'light' });

/** Lo que la sonda pide para saber si el emisor esta. */
const DESCUBRIMIENTO = '**/realms/*/.well-known/openid-configuration';

/** Y la puerta de verdad, a la que se manda el navegador entero. */
const AUTORIZACION = '**/realms/*/protocol/openid-connect/auth*';

test('la puerta que NO contesta: la aplicacion monta y dice quien fallo y en que URL', async ({
  page,
}) => {
  await page.route(DESCUBRIMIENTO, (ruta) => ruta.abort('connectionrefused'));

  await page.goto('./');

  const texto = await page
    .locator('#raiz')
    .innerText({ timeout: 15_000 });

  // Las tres cosas que hacen falta para arreglarlo, y que antes no estaban en ninguna parte.
  expect(texto).toContain('No se pudo llegar al emisor de identidad');
  expect(texto).toContain('http://localhost:8181/realms/kamayuk');
  expect(texto).toContain('/.well-known/openid-configuration');

  // Y no se quedo en la pagina de antes: seguimos en la nuestra.
  expect(page.url()).toContain('/rentas/');
});

test('y el aviso SE VE: pintado con el rojo del artboard, no sin estilo', async ({ page }) => {
  await page.route(DESCUBRIMIENTO, (ruta) => ruta.abort('connectionrefused'));

  await page.goto('./');
  const aviso = page.locator('#raiz p').first();
  await expect(aviso).toBeVisible({ timeout: 15_000 });

  // `bg-mal-fondo` y `text-mal-tinta` son clases del artboard, y una clase escrita no es una regla
  // emitida: la guarda de #91 solo barre `src/pantallas` y la libreria, asi que esto lo mide aqui.
  const tinta = await aviso.evaluate((e) => getComputedStyle(e).color);
  expect(tinta, 'el aviso no esta pintado con la tinta de error del artboard').toBe(
    'rgb(143, 42, 23)',
  );
  const fondo = await aviso.evaluate((e) =>
    getComputedStyle(e.parentElement as HTMLElement).backgroundColor,
  );
  expect(fondo, 'el recuadro del aviso no tiene fondo').toBe('rgb(251, 228, 224)');
});

test('la puerta que SI contesta: se va a ella y NO monta nada', async ({ page }) => {
  await page.route(DESCUBRIMIENTO, (ruta) =>
    ruta.fulfill({ status: 200, contentType: 'application/json', body: '{}' }),
  );

  let ido = false;
  await page.route(AUTORIZACION, (ruta) => {
    ido = true;
    // 204: el navegador HIZO la navegacion y se queda donde estaba, asi que el documento de antes
    // sigue vivo y se le puede preguntar si alguien dibujo algo en el. Con una pagina de verdad
    // se llevaria el documento por delante y no habria a quien preguntar.
    return ruta.fulfill({ status: 204 });
  });

  await page.goto('./');
  await expect.poll(() => ido, { timeout: 15_000 }).toBe(true);
  // Margen de sobra para que cualquier montaje tardio se hubiera visto.
  await page.waitForTimeout(2_000);

  const dibujado = await page.evaluate(
    () => document.getElementById('raiz')?.childElementCount ?? -1,
  );
  expect(
    dibujado,
    'se dibujo algo despues de mandar el navegador a la puerta: es el parpadeo que #112 no puede traer',
  ).toBe(0);
});
