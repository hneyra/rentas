import { CUENTAS, ENTRADA, ORIGEN, claveDe, faltaLaClave } from './instalacion.ts';
import { abrir, expect, test } from './arnes.ts';

/**
 * Los peldanos de la escalera de identidad, cada uno con la cuenta que lo alcanza (AC5).
 *
 * <h2>Cuatro peldanos, y los otros tres se dicen en vez de fingirse</h2>
 *
 * `src/api/escalera.ts` distingue siete: `sin-identidad`, `sin-municipalidad`,
 * `sin-privilegio`, `no-encontrado`, `no-valido`, `no-permitido` y `averia`. Contra ESTA
 * instalacion se alcanzan cuatro de verdad, y de los otros tres se sabe por que no:
 *
 * <ul>
 *   <li><b>`sin-privilegio`</b> no lo alcanza ninguna cuenta. Medido, no supuesto:
 *       `GET /seguridad/sesion/permisos` devuelve el MISMO mapa para `administrador` y para
 *       `jperez` —134 opciones, las siete concesiones en cada una, cero opciones sin
 *       «especial»—, asi que no hay nada que a alguna de las dos se le pueda negar. Fabricar el
 *       peldano recortando permisos en la base seria cambiar la instalacion para que la prueba
 *       pase, que es al reves de lo que una prueba sirve.</li>
 *   <li><b>`no-encontrado`</b> pide una cuenta valida en el emisor y no dada de alta en la
 *       municipalidad. Las tres que hay estan dadas de alta o no tienen municipalidad.</li>
 *   <li><b>`no-permitido`</b> —un 403 sin codigo— no lo produce ninguna de las operaciones que
 *       esta interfaz pide hoy.</li>
 * </ul>
 */

test.describe('la entrada, con la cuenta de la escala', () => {
  test.use({ storageState: CUENTAS.escala.estado });

  test('el marco se dibuja con la sesion y la municipalidad de la INSTALACION', async ({
    page,
  }) => {
    await abrir(page);

    // Las tres cadenas salen de `GET /seguridad/sesion` y `GET /seguridad/sesion/municipalidad`
    // contra el backend de verdad. Ninguna esta escrita en el codigo de la interfaz: F-3 las
    // trajo del artboard como constantes y I-3 las sustituyo por la lectura, de modo que verlas
    // aqui es la prueba de que la lectura ocurrio.
    await expect(page.locator('.kr-marco__entidad-nombre')).toHaveText(
      'Municipalidad Distrital de Catacaos',
    );
    await expect(page.locator('.kr-marco__sesion-nombre')).toHaveText(
      'Administrador del Sistema',
    );
    await expect(page.locator('.kr-marco__sesion-papel')).toHaveText('administrador');
  });

  test('el canje se completo: la barra de direcciones queda SIN el codigo', async ({ page }) => {
    await abrir(page);

    // `canjearSiVuelve` limpia la URL con `replaceState` saliera bien o mal, asi que esto no
    // demuestra por si solo que salio bien — lo demuestra junto con el marco dibujado, que
    // exige un token que solo existe si hubo canje.
    expect(page.url()).not.toContain('code=');
    expect(page.url()).not.toContain('state=');
    await expect(page.locator('.kr-marco__barra')).toBeVisible();
  });

  test('el token NO esta en el almacenamiento del navegador, y aqui se mide de verdad', async ({
    page,
  }) => {
    await abrir(page);

    // `verificaciones/camino-a-la-api.test.ts` sujeta esto por el codigo: que la prohibicion
    // exista, que no gane excepciones y que solo la puerta toque el almacenamiento. Lo que
    // ninguna prueba de `jsdom` puede hacer es mirar el almacenamiento de un navegador de
    // verdad DESPUES de un canje de verdad, que es lo unico que demuestra que el token que se
    // canjeo no acabo escrito en ninguna parte.
    const guardado = await page.evaluate(() => {
      const leer = (almacen: Storage) =>
        Object.keys(almacen).map((clave) => `${clave}=${almacen.getItem(clave) ?? ''}`);
      return [...leer(window.localStorage), ...leer(window.sessionStorage)];
    });

    // Lo que SI puede quedar es la contabilidad de la puerta —el verificador PKCE es de un solo
    // uso y se borra al canjear—, y nada de eso es una credencial reutilizable.
    for (const entrada of guardado) {
      expect(entrada).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}/);
    }
    expect(guardado.join('\n')).not.toContain('Bearer');
  });

  test('el arbol se compone: la instalacion publica CATASTRO y TESORERIA, y no se dibujan', async ({
    page,
  }) => {
    await abrir(page);

    // `GET /seguridad/modulos` de esta instalacion trae los doce del catalogo, CATASTRO y
    // TESORERIA incluidos —medido con curl—. Que el arbol ensene diez y NO esos dos es la
    // composicion de I-3 aplicada a datos reales: son de `../catastro` y de `../caja`
    // (ADR-0029), y este sistema no los sirve. Dibujarlos daria un menu que lleva a ningun
    // sitio.
    const arbol = page.locator('.kr-marco__panel, .kr-marco__arbol').first();
    await expect(arbol.getByText('Rentas · Registro', { exact: true })).toBeVisible();
    await expect(arbol.getByText('Catastro', { exact: true })).toHaveCount(0);
    await expect(arbol.getByText('Tesorería', { exact: true })).toHaveCount(0);
  });
});

test.describe('el peldano sin-municipalidad: 403 SIN_MUNICIPALIDAD', () => {
  test.use({ storageState: CUENTAS.sinMunicipalidad.estado });

  test('ve el mensaje que nombra lo que le falta, y no una pantalla en blanco', async ({
    page,
  }) => {
    await abrir(page);

    await expect(page.locator('.kr-aviso__titulo')).toHaveText(
      'Esta cuenta no tiene municipalidad asignada',
    );
    await expect(page.locator('.kr-aviso__detalle')).toHaveText(
      'El token no identifica una municipalidad',
    );
    // El detalle es el que MANDA EL BACKEND, palabra por palabra, y no un texto de respaldo de
    // la interfaz: `escalera.ts` solo usa el suyo si el backend no dijo nada.
    await expect(page.locator('.kr-puerta__remedio')).toContainText(
      'Lo asigna el administrador del sistema en el emisor de identidad',
    );

    // Y NO se ofrece volver a entrar. Es la decision de `escalera.ts` —`pideIdentidad: false`—
    // y en pantalla es lo que separa un consejo util de uno que no lleva a ningun sitio: entrar
    // otra vez con esta cuenta trae el mismo token y el mismo 403.
    await expect(page.getByRole('button', { name: 'Volver a identificarse' })).toHaveCount(0);
  });

  test('y el marco no llega a dibujarse: sin municipalidad no hay padron que ensenar', async ({
    page,
  }) => {
    await abrir(page);
    await expect(page.locator('.kr-marco__barra')).toHaveCount(0);
  });
});

test.describe('el peldano sin-identidad: el 401 de verdad, despues de cerrar sesion', () => {
  /**
   * Contexto limpio a proposito, y **el motivo importa**.
   *
   * Cerrar sesion termina la sesion del emisor, no solo la de esta pestana. Si este camino
   * usara el `storageState` guardado, mataria la sesion que los demas caminos reusan y los
   * dejaria entrando por el formulario —o fallando— segun el orden en que corrieran. Asi que
   * entra por su cuenta, con su propia sesion, y la que se lleva por delante es la suya.
   */
  test.use({ storageState: { cookies: [], origins: [] } });

  test('tras salir, la aplicacion pide identificarse porque el backend contesto 401', async ({
    page,
  }) => {
    const cuenta = CUENTAS.movimiento;
    const clave = claveDe(cuenta.usuario);
    if (clave === undefined || clave === '') {
      throw new Error(faltaLaClave(cuenta.usuario));
    }

    await page.goto(ENTRADA);
    await page.waitForURL(/\/realms\/kamayuk\/protocol\/openid-connect\/auth/, { timeout: 40_000 });
    await page.locator('#username').fill(cuenta.usuario);
    await page.locator('#password').fill(clave);
    await page.locator('#kc-login').click();
    await page.waitForURL((url) => url.origin === ORIGEN, { timeout: 40_000 });
    await expect(page.locator('.kr-marco__barra')).toBeVisible({ timeout: 40_000 });

    await page.getByRole('button', { name: /^Sesión de/ }).click();
    await page.getByRole('menuitem', { name: 'Cerrar sesión' }).click();

    // Se vuelve sin token. `arrancar()` ve la marca de salida y NO va a la puerta —si fuera,
    // con la sesion del emisor viva el usuario acabaria dentro otra vez sin haber hecho nada—,
    // asi que monta, pide `GET /seguridad/sesion` sin cabecera y el backend contesta 401.
    await expect(page.locator('.kr-aviso__titulo')).toHaveText(
      'Hay que volver a identificarse',
      { timeout: 40_000 },
    );
    await expect(page.locator('.kr-puerta__remedio')).toContainText('La sesion caduco');

    // Aqui SI se ofrece volver: es el unico peldano donde volver a la puerta arregla algo.
    await expect(page.getByRole('button', { name: 'Volver a identificarse' })).toBeVisible();
  });
});

test.describe('el peldano no-valido: el 422 con las palabras del backend', () => {
  test.use({ storageState: CUENTAS.escala.estado });

  test('una observacion corta la rechaza el backend, y la pantalla dice su regla', async ({
    page,
  }) => {
    await abrir(page);

    await page.locator('.kr-marco__ejercicio-boton').click();
    const dialogo = page.getByRole('dialog', { name: 'Cambiar el ejercicio de trabajo' });
    await expect(dialogo).toBeVisible();

    await dialogo.getByLabel('Ejercicio').fill('2026');
    // Tres caracteres. El backend pide cinco, y **es el unico que lo sabe**: la interfaz no
    // copia el 5 a proposito (el javadoc de `cambiarElEjercicio` lo razona), asi que este
    // camino solo puede ponerse verde si la peticion salio, cruzo el `server.proxy`, llego al
    // backend y volvio con su mensaje.
    await dialogo.getByLabel('Observación').fill('abc');
    await dialogo.getByRole('button', { name: 'Cambiar el ejercicio' }).click();

    await expect(dialogo.locator('.kr-aviso__titulo')).toHaveText(
      'Lo que se mandó no cumple una regla',
    );
    await expect(dialogo.locator('.kr-aviso__detalle')).toContainText(
      'al menos 5 caracteres',
    );
    await expect(dialogo.locator('.kr-aviso__detalle')).toContainText('ADR-0008');

    // El dialogo se queda ABIERTO con lo tecleado dentro: cerrarlo obligaria a escribirlo otra
    // vez. Y la instalacion no cambia — un 422 no fija ningun ejercicio, que es lo que permite
    // que este camino corra tantas veces como haga falta.
    await expect(dialogo.getByLabel('Observación')).toHaveValue('abc');
    await expect(page.locator('.kr-marco__ejercicio-boton')).toHaveText('Sin fijar');
  });
});
