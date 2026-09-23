import type { Page } from '@playwright/test';

import type { Paginado } from '../src/datos/lecturas.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
} from '../src/datos/seguridadMedida.ts';

/**
 * **Lo que el navegador contesta en vez del backend.**
 *
 * <h2>Por que se intercepta y no se levanta la instalacion</h2>
 *
 * Porque este arnes mide **que la interfaz se vea**, y para eso no hace falta un backend: hace
 * falta un navegador. Levantar PostgreSQL, Keycloak, Traefik y las cinco aplicaciones para
 * comprobar que una cabecera es azul seria pagar diez minutos por una medicion que no depende de
 * ninguno de los cinco.
 *
 * El arnes que SI necesita la instalacion —entrar por el formulario de Keycloak, con PKCE y
 * canje— es otro, y sigue sin poder correr: le faltan el compose de la plataforma, el realm con
 * sus tres cuentas, el volcado de la marcha blanca y el secreto. Queda declarado.
 *
 * <h2>Lo que se contesta son RESPUESTAS MEDIDAS</h2>
 *
 * `seguridadMedida.ts` son respuestas de `curl` a la instalacion de verdad: doce modulos, 134
 * accesos y la matriz entera. Inventarlas aqui haria que el arnes midiera contra una fantasia — y
 * la primera vez que el backend cambiara de forma, seguiria en verde.
 *
 * <h2>Y lo que NO se contesta devuelve 404 a proposito</h2>
 *
 * Las pantallas que piden datos —`panel`, `coa-panel`— tienen que verse **en su estado de error**,
 * que es un estado de verdad y hay que poder mirarlo. Contestarles algo inventado seria ensenar
 * cifras que no existen, que es justo lo que #97 saco del paquete.
 */

/**
 * Como se pasa la puerta sin hacer identidad.
 *
 * **El token no se puede sembrar**: vive EN MEMORIA a proposito —es una credencial, y la
 * prohibicion `token-en-almacenamiento` lo vigila—, asi que no hay clave de `sessionStorage` que
 * poner. Y sin token, `arrancar()` manda a Keycloak y no monta nada, que es lo correcto.
 *
 * Lo que si hay es **un camino declarado por el que el arranque monta sin token**: el tope de idas.
 * `TOPE_DE_IDAS` es 3, y existe porque un canje que falla siempre —un `redirect_uri` mal
 * declarado— convertiria el arranque en un rebote infinito. Con el tope agotado la aplicacion
 * monta y deja que la pantalla explique el 401.
 *
 * Usarlo aqui no es un truco: es **exactamente el estado que este arnes quiere medir** — la
 * interfaz montada sin identidad, que es lo que hay que poder mirar para comprobar que se ve.
 */
const IDAS = 'kamayuk.pkce.idas';
const TOPE_DE_IDAS = 3;

/**
 * El envoltorio con que el backend pagina, **con su tipo** (#314): lo que el arnes sirve es la
 * forma de una operacion, y una forma sin tipo se queda corta en silencio —que es como se rompieron
 * dos caminos en la ola 8—. Lo exige `verificaciones/los-fixtures-del-arnes-llevan-tipo.test.ts`.
 */
function paginaDe<T>(contenido: readonly T[]): Paginado<T> {
  return {
    contenido,
    pagina: 0,
    tamano: 200,
    totalElementos: contenido.length,
    totalPaginas: 1,
    hayMas: false,
  };
}

export async function conLaSeguridadContestada(pagina: Page): Promise<void> {
  await pagina.addInitScript(
    ([clave, tope]: readonly [string, string]) => {
      window.sessionStorage.setItem(clave, tope);
    },
    [IDAS, String(TOPE_DE_IDAS)] as const,
  );

  await pagina.route('**/rentas/api/v1/**', async (ruta) => {
    const url = ruta.request().url();
    // El cuerpo llega ya serializado, y se serializa donde el valor todavia tiene su tipo: un
    // `(cuerpo: unknown) => JSON.stringify(cuerpo)` lo borraria, y la guarda de los fixtures lo
    // leeria como un fixture sin tipo — que es lo que seria.
    const json = (cuerpo: string) =>
      ruta.fulfill({ status: 200, contentType: 'application/json', body: cuerpo });

    if (url.includes('/seguridad/modulos')) return json(JSON.stringify(paginaDe(MODULOS_MEDIDOS)));
    if (url.includes('/seguridad/accesos')) return json(JSON.stringify(paginaDe(ACCESOS_MEDIDOS)));
    if (url.includes('/seguridad/sesion/permisos')) return json(JSON.stringify(PERMISOS_MEDIDOS));
    // Ver el javadoc: lo demas no se inventa.
    return ruta.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
  });
}

/** Abre un destino por su hash y espera a que el armazon exista. */
export async function abrir(pagina: Page, clave: string): Promise<void> {
  await pagina.goto(`./#/${clave}`);
  // `rentas` no monta el armazon hasta saber que puede abrir la cuenta: antes de eso no hay barra.
  await pagina.locator('[data-slot="barra-global"], header').first().waitFor({ timeout: 15_000 });
}
