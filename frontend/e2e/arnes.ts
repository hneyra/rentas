import { test as base, expect, type Page } from '@playwright/test';

import { SE_EXIGE_LA_INSTALACION, comprobarLaInstalacion } from './instalacion.ts';

/**
 * El `test` que usan los caminos: el de Playwright mas la comprobacion previa del AC6.
 *
 * Va como fixture automatica y no como `beforeEach` copiado en cada archivo, porque un
 * `beforeEach` copiado se olvida en el archivo que se anada manana — y el camino que se olvide
 * no dira «no hay instalacion»: dira `net::ERR_CONNECTION_REFUSED`, que es el mensaje que este
 * criterio existe para no volver a ver.
 */
export const test = base.extend<{ instalacionViva: void }>({
  instalacionViva: [
    async ({ request }, usar, informe) => {
      const diagnostico = await comprobarLaInstalacion(request);
      if (!diagnostico.sirve) {
        if (SE_EXIGE_LA_INSTALACION) {
          throw new Error(diagnostico.motivo);
        }
        informe.skip(true, diagnostico.motivo);
      }
      await usar();
    },
    { auto: true },
  ],
});

export { expect };

/**
 * Abre la aplicacion y espera a que se asiente: o el marco, o el aviso que explica por que no.
 *
 * **El redirect ocurre igual con el estado reusado**, y conviene no confundirse: lo que la
 * cookie del emisor ahorra es el formulario. La aplicacion sale a la puerta, Keycloak reconoce
 * la sesion y devuelve un codigo sin ensenar nada, y la aplicacion lo canjea. Por eso esto
 * espera a que la pagina se asiente y no a un `load` a secas: cuando el `load` termina, lo que
 * hay en pantalla puede ser todavia el salto a Keycloak.
 */
export async function abrir(page: Page, destino = '#panel'): Promise<void> {
  await page.goto(`/rentas/${destino}`);
  await page
    .locator('.kr-marco__barra, .kr-puerta .kr-aviso')
    .first()
    .waitFor({ state: 'visible', timeout: 40_000 });
}

/**
 * Lo que el proxy de datos serviria si estuviera encendido, para poder exigir que NO este.
 *
 * Son cadenas del artboard `RentasV6` que la instalacion no tiene: el padron de la
 * municipalidad 9 son 10 603 filas del volcado de la marcha blanca y ninguna se llama asi.
 * Verlas en pantalla significaria que el arnes esta midiendo `src/datos/prototipo.ts` y no la
 * instalacion, que es el estado que el plan §6 llama insuficiente.
 */
export const CIFRAS_DEL_ARTBOARD = [
  'Rufina Medina Medina',
  'Castillo Pascuala',
  '170,616.75',
  '5,350.00',
  '62,418',
  '1,134 pendientes',
] as const;

/**
 * Por cada seccion, una cadena que **solo** puede venir de la instalacion.
 *
 * <h2>Existe porque la guarda del artboard, sola, pasaba sin mirar nada</h2>
 *
 * Medido (AC9, rotura R5): quitando del `webServer` la bandera que apaga el proxy de datos, la
 * comprobacion «ninguna cifra del artboard» seguia **VERDE** en las cuatro secciones. El motivo
 * no era que el proxy estuviera apagado —no lo estaba— sino que `abrir()` vuelve en cuanto se ve
 * el marco, y el proxy anade entre 120 y 320 ms de latencia a proposito: la foto del `innerText`
 * se tomaba **antes** de que llegara ninguna fila. Una guarda que mira una pantalla todavia
 * vacia no puede encontrar nada, y por eso pasaba.
 *
 * Asi que primero se espera a que la seccion haya recibido lo suyo, y solo entonces se mira. Con
 * la espera puesta, la misma rotura pone rojas las cuatro.
 */
export const LO_QUE_SOLO_TIENE_LA_INSTALACION: Readonly<Record<string, string>> = {
  '#panel': 'papeletas sin resolucion de multa emitida',
  '#contribuyentes': 'SULLON VILCHEZ-JOSE RAUL',
  // El 422 de `POST /rentas/predial/calculo-individual`, con las palabras del backend. Se elige
  // este y no una etapa de la corrida porque se ve sin pulsar nada.
  '#determinacion': 'El cuerpo de la peticion no se puede leer: no es JSON valido',
  //
  // **Este NO discrimina el proxy, y se dice en vez de suponerlo.** Sale de que
  // `GET /seguridad/sesion` conteste `ejercicioDeTrabajo: null`, y esa operacion esta en
  // `YA_SERVIDAS` desde I-1: la sirve el backend con el proxy encendido y con el apagado.
  // Medido en la rotura R5: de los cuatro, tres se ponen rojos al encender el proxy y este se
  // queda verde. Para «#valores» quien discrimina es la otra mitad —que no aparezca ninguna
  // cifra del artboard—, y por eso las dos mitades estan en la misma prueba.
  '#valores': 'La sesión no tiene ejercicio de trabajo fijado',
};
