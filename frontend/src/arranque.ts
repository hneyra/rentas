/**
 * El arranque de `rentas-web`: primero quien pregunta, despues quien contesta, y solo entonces
 * quien dibuja.
 *
 * <h2>El orden es el criterio, no un detalle</h2>
 *
 * React monta y las pantallas piden datos en su primer efecto. Si el proxy se instalara
 * despues de `createRoot(...).render(...)`, la primera peticion de la primera pantalla saldria
 * al `fetch` de verdad —y en desarrollo la atenderia el servidor de Vite, que devuelve el
 * `index.html` con un `200`—: no un error, una pagina HTML donde la pantalla espera JSON.
 *
 * Por eso el montaje entra aqui como argumento y no como una linea de mas abajo: `arrancar()`
 * no puede montar antes de instalar, y `arranque.test.ts` lo comprueba mirando el orden en que
 * ocurren las dos cosas.
 *
 * **Y desde I-1 hay un tercer paso, y va el PRIMERO de los tres.** Si volvemos de Keycloak, la
 * URL trae un `?code=` que hay que canjear antes de montar: la primera peticion de la primera
 * pantalla es `GET /seguridad/sesion`, y si sale sin token contesta 401. El canje es una ida a
 * la red, asi que `arrancar()` es `async` desde F-4 y aqui se le anade una espera mas.
 *
 * <h2>La ida a la puerta la decide el arranque, no la pantalla</h2>
 *
 * Sin token no hay nada que ensenar, asi que se va a la puerta directamente en vez de montar
 * la aplicacion para que ella descubra el 401 y lo ensene. La diferencia se ve: con la sesion
 * de Keycloak viva, ir a la puerta va y vuelve sin dibujar nada; montar primero enseñaria un
 * error de identidad **a alguien que si esta identificado**, durante el tiempo que tarda la ida.
 *
 * Con dos frenos, y los dos hacen falta:
 *
 *   · **el tope de tres idas** (`puedeIrALaPuerta`), porque un canje que falla siempre —un
 *     `redirect_uri` mal declarado— convierte esto en un rebote infinito: pagina en blanco
 *     parpadeando, ninguna traza, y el emisor recibiendo la rafaga;
 *   · **la marca de salida** (`vieneDeSalir`), porque `post_logout_redirect_uri` trae de vuelta
 *     sin token y sin ella el arranque volveria a entrar solo — con la sesion del emisor viva,
 *     quien acaba de cerrar sesion se encuentra DENTRO OTRA VEZ con la misma cuenta.
 *
 * Cuando uno de los dos frena, se monta igual: la aplicacion pide la sesion, recibe su 401 y
 * `Puerta` lo explica con su boton. Que es mejor que una pagina en blanco con un motivo escrito
 * solo en la consola.
 *
 * <h2>La bandera del proxy se lee de `import.meta.env`, y de ahi y no de otro sitio</h2>
 *
 * Vite sustituye `import.meta.env.VITE_*` por su valor **al construir**, asi que con la bandera
 * apagada la condicion queda en `"false" === "true"`, Rollup la pliega y **se lleva por delante
 * el `import()` dinamico entero** — el proxy, las dieciocho operaciones y todas las cifras del
 * artboard. Ese es todo el mecanismo de AC3 de F-4.
 *
 * **Lo que importa es de DONDE sale el valor, no como esta escrita la condicion**, y se midio
 * en vez de suponerlo. Envolver la comparacion en una funcion —`laBanderaLoPide(import.meta.env)`—
 * da **exactamente el mismo bundle**: 193 592 bytes en un solo trozo, cero apariciones de
 * cualquier cifra del artboard. Rollup la inlinea y pliega igual. Lo que si mete los datos falsos
 * en produccion es leer la bandera en **tiempo de ejecucion** —de la URL, de una configuracion
 * pedida al arrancar—: entonces no hay nada que plegar, y `VITE_KAMAYUK_PROXY_DE_DATOS=false yarn
 * build` sale con **227 205 bytes**, su trozo `proxy-*.js` aparte y «Rufina Medina Medina»
 * dentro. Medido las tres veces.
 *
 * Es opt-in y no opt-out a proposito: lo que decide si un despliegue lleva datos inventados no
 * puede ser que alguien se acuerde de apagarlos. `.env.development` la enciende para `yarn
 * dev`, que es donde hace falta.
 */

import {
  canjearSiVuelve,
  entrar,
  hayPuerta,
  puedeIrALaPuerta,
  token,
  vieneDeSalir,
} from './api/identidad.ts';

/**
 * Canjea si volvemos del emisor, instala el proxy si la bandera lo pide, y solo entonces monta.
 *
 * Devuelve sin montar cuando manda a la puerta: `entrar()` navega fuera de la pagina, asi que
 * dibujar algo despues seria dibujar sobre un documento que el navegador esta a punto de tirar.
 */
export async function arrancar(montar: () => void): Promise<void> {
  await canjearSiVuelve();

  if (token() === null && hayPuerta() && puedeIrALaPuerta() && !vieneDeSalir()) {
    await entrar();
    return;
  }

  if (import.meta.env.VITE_KAMAYUK_PROXY_DE_DATOS === 'true') {
    const { instalarProxyDeDatos } = await import('./api/proxy.ts');
    instalarProxyDeDatos({ latencia: true });
  }
  montar();
}
