import { expect, test as preparar, type Page } from '@playwright/test';

import {
  CUENTAS,
  ENTRADA,
  ORIGEN,
  SE_EXIGE_LA_INSTALACION,
  claveDe,
  comprobarLaInstalacion,
  faltaLaClave,
  type CuentaDeLaInstalacion,
} from './instalacion.ts';

/**
 * El acceso de verdad, por el formulario de Keycloak (AC2), una vez por cuenta.
 *
 * <h2>Que reusa exactamente el `storageState`, y que NO reusa</h2>
 *
 * Lo que se guarda es la **cookie de sesion de Keycloak**, no el token de la aplicacion — y no
 * por descuido: `src/api/identidad.ts` guarda el token en una variable de modulo, nunca en
 * `localStorage` ni en `sessionStorage`, que es lo que la prohibicion `token-en-almacenamiento`
 * exige y lo que el propio AC3 recuerda que no hay que cambiar. Un `storageState` no tiene de
 * donde sacar lo que no esta en el almacenamiento.
 *
 * Asi que lo que el arnes se ahorra en cada camino **es el formulario, no el redirect**: con la
 * sesion del emisor viva, la aplicacion sale a la puerta y vuelve con su codigo sin ensenar
 * nada, que es literalmente lo que el javadoc de `identidad.ts` describe como «la renovacion
 * silenciosa sale gratis de tener SSO». Decir que se ahorra el redirect seria decir que el
 * token se persiste, que es lo contrario de lo que este sistema decidio.
 */

/**
 * Espera a que la cuenta llegue **a donde esa cuenta tiene derecho a llegar**.
 *
 * <h2>Por que no vale «el marco o un aviso», que es lo que habia aqui primero</h2>
 *
 * Medido rompiendo el `redirect_uri` a proposito (AC9, rotura R4): con el canje roto la
 * aplicacion rebota tres veces, se para y ensena el aviso del 401 — y una espera que admitiera
 * «marco o aviso» lo habria dado por bueno, guardando un estado que no sirve para nada y
 * dejando el fallo para el primer camino que lo usara. Un canje fallido y una cuenta sin
 * municipalidad se ven parecido; lo que los separa es que **cada cuenta declara su desenlace**.
 */
async function esperarSuDesenlace(page: Page, cuenta: CuentaDeLaInstalacion): Promise<void> {
  if (cuenta.acaba === 'marco') {
    await page.locator('.kr-marco__barra').waitFor({ state: 'visible', timeout: 40_000 });
    return;
  }
  await expect(page.locator('.kr-aviso__titulo')).toHaveText(
    'Esta cuenta no tiene municipalidad asignada',
    { timeout: 40_000 },
  );
}

/**
 * Entra por el formulario y guarda el estado.
 *
 * `sin-municipalidad` entra bien y se queda en la puerta con su 403, que es justo lo que esa
 * cuenta existe para ensenar: por eso el desenlace se declara y no se supone. Lo que se afirma
 * en las tres es que el canje ocurrio —se vuelve al origen de la aplicacion y la barra de
 * direcciones queda **sin** el codigo—, porque sin eso el estado guardado no valdria para nada.
 */
async function entrarPorElFormulario(page: Page, cuenta: CuentaDeLaInstalacion): Promise<void> {
  const clave = claveDe(cuenta.usuario);
  if (clave === undefined || clave === '') {
    throw new Error(faltaLaClave(cuenta.usuario));
  }

  await page.goto(ENTRADA);

  // La ida a la puerta la hace la aplicacion sola: `arrancar()` ve que no hay token y llama a
  // `entrar()` antes de montar nada. Si esto no llegara, el defecto seria de PKCE o del
  // `redirect_uri`, y el mensaje de Keycloak lo diria en esta misma pagina.
  await page.waitForURL(/\/realms\/sgtm\/protocol\/openid-connect\/auth/, { timeout: 40_000 });

  await page.locator('#username').fill(cuenta.usuario);
  await page.locator('#password').fill(clave);
  await page.locator('#kc-login').click();

  await page.waitForURL((url) => url.origin === ORIGEN, { timeout: 40_000 });
  await esperarSuDesenlace(page, cuenta);

  // El codigo se usa UNA vez y `canjearSiVuelve` limpia la barra con `replaceState`. Que ya no
  // este es la senal de que el canje se completo y no de que la pagina se quedo a medias.
  expect(page.url()).not.toContain('code=');
}

for (const cuenta of Object.values(CUENTAS)) {
  preparar(`entra «${cuenta.usuario}» por el formulario y se guarda su estado`, async ({
    page,
    request,
  }) => {
    const diagnostico = await comprobarLaInstalacion(request);
    if (!diagnostico.sirve) {
      // El salto del AC6. En CI, donde la instalacion TENIA que estar, esto no se salta: falla.
      if (SE_EXIGE_LA_INSTALACION) {
        throw new Error(diagnostico.motivo);
      }
      preparar.skip(true, diagnostico.motivo);
    }

    await entrarPorElFormulario(page, cuenta);
    await page.context().storageState({ path: cuenta.estado });
  });
}
