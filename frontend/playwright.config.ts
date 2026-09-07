import { defineConfig, devices } from '@playwright/test';

import { ENTRADA, ORIGEN, PUERTO } from './e2e/instalacion.ts';

/**
 * El arnes de extremo a extremo de `rentas-web` (I-2, #28).
 *
 * <h2>Que anade sobre las 967 pruebas de `vitest`, y por que hacia falta</h2>
 *
 * `vitest` corre sobre `jsdom`, en un solo proceso y con `fetch` sustituido. Ahi no existen —y
 * por tanto no se pueden equivocar— el redirect de Keycloak, el `code_challenge` de PKCE, el
 * canje del codigo, la politica de mismo origen, y sobre todo el `server.proxy` de Vite, que es
 * el unico camino por el que una peticion de la interfaz llega al backend. Todo eso solo
 * existe en un navegador de verdad contra la instalacion levantada.
 *
 * <h2>Lo que este arnes NO hace, y se dice en vez de descubrirse</h2>
 *
 * No levanta la instalacion: la da por levantada y lo comprueba antes de abrir una pagina
 * (AC6). No corre en `yarn verificar` (AC8): `vitest.config.ts` solo recoge
 * `{src,verificaciones}/**&#47;*.test.{ts,tsx}` y estos archivos son `.spec.ts` en `e2e/`, asi
 * que la orden rapida sigue siendo rapida por construccion y no por acuerdo.
 */
export default defineConfig({
  testDir: './e2e',

  /** Deja escrito un estado vacio por cuenta, para que la falta de instalacion pueda SALTARSE. */
  globalSetup: './e2e/preparar-el-estado.ts',

  /**
   * En serie, y **no es por comodidad**.
   *
   * Los caminos comparten UNA instalacion, y uno de ellos —el peldano del 422— manda una
   * escritura de verdad contra `PUT /seguridad/sesion/ejercicio`. Con trabajadores en paralelo,
   * dos caminos que leen el ejercicio de la sesion mientras un tercero lo toca dan un fallo que
   * depende del orden y que no se reproduce. La instalacion es compartida: se trata como tal.
   */
  workers: 1,
  fullyParallel: false,

  /**
   * Sin reintentos, tampoco en CI.
   *
   * Un reintento convierte «esto falla una vez de cada tres» en verde, y la unica senal de que
   * algo va mal pasa a ser una linea de aviso que nadie lee. Si un camino es inestable, el
   * arnes tiene que decirlo.
   */
  retries: 0,

  /**
   * Prohibido `test.only` en CI: se cuela en un commit y deja de correr todo lo demas, en verde.
   */
  forbidOnly: Boolean(process.env.CI),

  reporter: process.env.CI === undefined ? [['list']] : [['list'], ['github']],

  timeout: 60_000,
  expect: { timeout: 15_000 },

  use: {
    baseURL: ORIGEN,
    /** La traza solo del primer reintento no sirve si no hay reintentos: se guarda del fallo. */
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },

  projects: [
    {
      /**
       * El acceso de verdad, por el formulario de Keycloak (AC2). Corre una vez y deja el
       * estado en un archivo; los caminos lo reusan (AC3).
       */
      name: 'identidad',
      testMatch: /.*\.setup\.ts/,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'caminos',
      testMatch: /.*\.spec\.ts/,
      dependencies: ['identidad'],
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  /**
   * El servidor de Vite lo levanta el arnes (AC1), y con **dos** decisiones que no son de
   * comodidad.
   *
   * <h2>1. El proxy de datos, APAGADO</h2>
   *
   * `.env.development` enciende `VITE_KAMAYUK_PROXY_DE_DATOS`, asi que un `yarn dev` a secas
   * contesta las secciones desde `src/datos/prototipo.ts` —las cifras del artboard— sin salir a
   * la red. Un arnes que corriera asi mediria el proxy, que es **exactamente** el estado que
   * `docs/00-gobierno/plan-de-marcha-blanca.md` §6 llama insuficiente. Medido: pasar la
   * variable en linea gana a la del archivo —Vite prioriza las `VITE_*` del proceso—, y el
   * modulo servido llega con `"VITE_KAMAYUK_PROXY_DE_DATOS": "false"` dentro.
   *
   * <h2>2. No se reusa un servidor ajeno</h2>
   *
   * `reuseExistingServer: false` y `--strictPort`. Un servidor que este arnes no levanto pudo
   * arrancarse con el proxy de datos encendido, y desde fuera **no hay forma de saberlo**: la
   * bandera se resuelve al construir el modulo, no se publica en ninguna cabecera. Reusarlo
   * cambiaria en silencio lo que el arnes mide. Que el puerto este ocupado es un error ruidoso;
   * medir el proxy creyendo que se mide la instalacion, no.
   */
  webServer: {
    command: `yarn dev --port ${String(PUERTO)} --strictPort`,
    url: ENTRADA,
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
    env: { VITE_KAMAYUK_PROXY_DE_DATOS: 'false' },
  },
});
