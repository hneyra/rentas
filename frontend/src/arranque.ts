/**
 * El arranque de `rentas-web`: primero quien pregunta, y solo entonces quien dibuja.
 *
 * <h2>El montaje entra como ARGUMENTO, y eso se queda</h2>
 *
 * `arrancar(montar)` recibe el montaje en vez de que el montaje venga en la linea de abajo,
 * porque hay cosas que tienen que pasar **antes de que React monte** y la unica forma de que no
 * puedan colarse despues es que el montaje sea lo ultimo que esta funcion hace. Hasta #90 la
 * cosa era instalar el proxy de datos; hoy es el canje del codigo de autorizacion, y manana sera
 * otra. La forma aguanta el cambio; una linea suelta debajo, no.
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
 * <h2>El PROXY DE DATOS se retiro con la V6 (#90), y aqui queda dicho por que</h2>
 *
 * Hasta el cambio de guardia, este arranque instalaba —detras de `VITE_KAMAYUK_PROXY_DE_DATOS`—
 * un proxy que sustituia `globalThis.fetch` y contestaba dieciocho operaciones con las cifras
 * capturadas del artboard V6. Su mecanismo era bueno y esta medido: con la bandera apagada,
 * Rollup plegaba la condicion y **se llevaba por delante el `import()` dinamico entero**, datos
 * incluidos — 193 592 bytes y cero cifras del artboard, frente a 227 205 y «Rufina Medina Medina»
 * dentro si la bandera se leia en tiempo de EJECUCION.
 *
 * **Sale porque se quedo sin nada que contestar.** Las pantallas de V8 dibujan las cifras de su
 * propia definicion —que son las del artboard, atadas a el campo por campo (#86)—, asi que no
 * piden datos a nadie mientras no se conecten de verdad. Un proxy que no contesta nada es codigo
 * muerto con una bandera delante, y una bandera que no hace nada es peor que no tenerla: la
 * proxima persona la enciende esperando algo.
 *
 * Lo que NO sale es lo de arriba: el canje, el tope de idas y la marca de salida se quedan.
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
 * Canjea si volvemos del emisor, y solo entonces monta.
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

  montar();
}
