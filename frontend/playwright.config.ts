import { defineConfig, devices } from '@playwright/test';

import { PUERTO, URL_DEL_ARNES } from './puerto-del-arnes.mjs';

/**
 * **El arnes que mide lo que jsdom no puede: que la interfaz se VEA** (#107).
 *
 * <h2>Por que este arnes existe, si ya hay pruebas de `vitest`</h2>
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
 *
 * <h2>Y por que el puerto ya NO esta escrito aqui</h2>
 *
 * Porque estaba escrito TRES veces —`baseURL`, `webServer.url` y el `--port`— y siempre era el
 * 4173. Con varios worktrees a la vez, el arnes de una rama media el bundle de otra: paso al
 * cerrar #140 y dio `Received: 0`, un rojo que no nombra el puerto. Ahora sale de
 * `puerto-del-arnes.mjs`, que lo deriva del ARBOL —el mismo siempre para la misma copia de
 * trabajo, distinto para cada una— y en CI, donde solo hay un arbol, sigue siendo el 4173.
 * El porque de cada decision, con lo que se midio, esta en ese archivo.
 */
export default defineConfig({
  testDir: './e2e',
  // Sin paralelo: son pocos caminos y comparten el mismo servidor. El paralelo aqui compra
  // segundos y paga con rojos que dependen del orden.
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI === undefined ? [['list']] : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: URL_DEL_ARNES,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  /**
   * La segunda mitad de #148: que lo servido sea el `dist/` de este arbol y no el de otro.
   * Corre DESPUES del `webServer` —los complementos van delante de los `globalSetup`— y
   * detiene la corrida entera, que es lo que un camino suelto no puede hacer.
   */
  globalSetup: './e2e/el-bundle-servido-es-el-mio.ts',
  webServer: {
    // `build` delante, porque `preview` sin `dist` sirve un 404 con codigo 200. Y
    // `--strictPort` SE QUEDA: sin el, Vite se mueve de puerto en silencio y el `baseURL` se
    // queda donde estaba, que es medir el servidor de otro.
    //
    // La comprobacion de que el puerto esta libre NO esta aqui, y no es por gusto: esta en el
    // script `e2e` de `package.json`, DELANTE de `playwright test`. Medido —#148, con un
    // intruso en el puerto derivado—: si esta en este comando, Playwright ya ha hablado antes
    // con su propio aviso, que dice «http://localhost:5074/rentas/ is already used» y nada
    // mas; quien tiene el puerto, desde que directorio y como matarlo lo dice el nuestro, y
    // para decirlo tiene que ir primero.
    command: `yarn build && yarn preview --port ${PUERTO} --strictPort`,
    url: URL_DEL_ARNES,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
