import { defineConfig, devices } from '@playwright/test';

/**
 * **El arnes que mide lo que jsdom no puede: que la interfaz se VEA** (#107).
 *
 * <h2>Por que este arnes existe, si ya hay 507 pruebas</h2>
 *
 * Porque todas comparan `className` **como texto**. Ninguna dice que Tailwind emita ese CSS, que
 * el navegador lo aplique, que la rejilla se reacomode, que la cabecera azul sea azul, ni que un
 * `overflow` no corte una tabla. #91 mide que una clase **produzca una regla**; nadie media que
 * **la regla pinte**.
 *
 * <h2>Y por que NO es el arnes anterior</h2>
 *
 * El de I-2 (#28) corria contra la instalacion levantada —Keycloak de verdad, PKCE, canje, el
 * volcado de la marcha blanca— y por eso **no corria en CI**: le faltaban cuatro cosas que no
 * estan en este repositorio. Salio en #90 con las pantallas contra las que corria.
 *
 * Este es otro: corre contra **el bundle construido**, en Chromium, sin backend. Mide lo que aquel
 * nunca midio, y **puede correr hoy**. El de la instalacion vuelve cuando la instalacion este.
 *
 * <h2>Contra el BUNDLE y no contra `yarn dev`</h2>
 *
 * `vite preview` sirve lo que `vite build` produjo — el mismo artefacto que la imagen lleva. Con
 * el servidor de desarrollo se mediria un arbol de modulos sin empaquetar, con su CSS inyectado
 * por otra via: verde aqui y roto en produccion es exactamente lo que un arnes tiene que impedir.
 */
export default defineConfig({
  testDir: './e2e',
  // Sin paralelo: son pocos caminos y comparten el mismo servidor. El paralelo aqui compra
  // segundos y paga con rojos que dependen del orden.
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI === undefined ? [['list']] : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:4173/rentas/',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    // `--strictPort`: si 4173 esta ocupado, que falle en vez de servir otro puerto y medir otra
    // cosa. Y `build` delante, porque `preview` sin `dist` sirve un 404 con codigo 200.
    command: 'yarn build && yarn preview --port 4173 --strictPort',
    url: 'http://localhost:4173/rentas/',
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
