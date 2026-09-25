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
 * Cuando uno de los dos frena, se monta igual: la aplicacion pide el catalogo, recibe su 401 y
 * lo explica con un boton «Volver a identificarse», que es el que levanta los dos frenos
 * (`olvidarLaParada`) y va a la puerta. Que es mejor que una pagina en blanco con un motivo
 * escrito solo en la consola.
 *
 * **Ese boton faltaba entre #90 y #355**, y la frase de arriba lo prometia igual: #90 se llevo
 * `Puerta.tsx` con la V6, y lo que quedo en su sitio fue un parrafo suelto que decia «Vuelva a
 * entrar.» sin nada que pulsar. Con la marca de salida puesta, F5 repetia lo mismo —la marca vive
 * lo que la pestana—, asi que tras «Cerrar sesion» la pestana ya no podia volver a entrar. Hoy el
 * remedio viaja con el estado del 401, en `datos/useCatalogoPermitido.ts`, como el `reintentar`
 * del 403 (#311).
 *
 * **Y si lo que freno fue un canje fallido, se dice por que** (#355): la `Vuelta` de
 * `canjearSiVuelve` se guarda como se guarda la falla de la puerta, y la aplicacion ensena su
 * `motivo` y su `detalle` en vez de la frase generica del 401. Tirarla —como hacia la V6 y siguio
 * haciendo esto hasta #355— perdia el unico diagnostico de un `redirect_uri` mal declarado.
 *
 * <h2>Y hay un TERCER caso en que se monta: cuando la ida no llega a ocurrir (#112)</h2>
 *
 * No montar es correcto **cuando la puerta contesta**. Cuando no —el emisor apagado, un DNS que
 * no resuelve, una espera agotada— la navegacion se rechaza y no queda ni documento nuevo ni
 * aplicacion: la pagina de antes, vacia. Medido con `yarn dev` y nada mas levantado,
 * `body.innerText` vacio y la consola con dos lineas de Vite y ni un error. O sea el mismo modo
 * de fallo que el parrafo de arriba dice evitar, y ni siquiera con el motivo en la consola.
 *
 * Asi que `entrar()` pregunta primero si el emisor esta, y devuelve la falla cuando no. Con ella
 * se monta y se explica **quien** no contesto y **en que URL** — que es lo que hace falta para
 * arreglarlo. El camino bueno no cambia: si el emisor contesta, sigue sin montarse nada.
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
 *
 * <h2>Y lo que ENTRA en su sitio es otra cosa, que si tiene algo que contestar (#114)</h2>
 *
 * La bandera retirada se quedo diez meses en `.env.development` sin hacer nada —exactamente lo
 * que el parrafo de arriba dice que no hay que dejar—, y mientras tanto **no habia forma de
 * mirar las cuarenta pantallas sin levantar la plataforma entera**: el arbol de modulos llega de
 * la red, y sin las tres lecturas de seguridad no hay ni un destino que abrir.
 *
 * Lo que se siembra es **el catalogo y nada mas** —que pantallas existen—, no datos de pantalla:
 * esos ya viven en las definiciones. Ver `desarrollo/sembrarElCatalogo.ts`, que ademas explica
 * por que vive fuera de `src/`.
 *
 * <h2>Las dos condiciones son CONSTANTES AL CONSTRUIR, y de eso depende que no viaje nada</h2>
 *
 * Vite sustituye `import.meta.env.DEV` por `false` y cada `import.meta.env.VITE_*` por su
 * literal **al construir**, asi que Rollup pliega la condicion y se lleva por delante el
 * `import()` dinamico entero, capturas incluidas. Es el mecanismo del proxy de V6, repetido a
 * proposito porque esta medido por los dos lados: leer la bandera en tiempo de EJECUCION —tras
 * una funcion, desde `globalThis`, desde `configuracion()`— deja el modulo dentro del paquete.
 *
 * **`import.meta.env.DEV` va primero y no sobra.** La bandera sola dependeria de que nadie
 * encienda la variable al construir; con esta delante, `yarn build` sale limpio **haga lo que
 * haga el entorno**. Las otras dos vallas siguen donde estaban: el `ENV` explicito del
 * `Dockerfile` y el `.env*` del `.dockerignore`.
 */


import type { FallaDeLaPuerta, VueltaFallida } from './api/identidad.ts';
import {
  canjearSiVuelve,
  entrar,
  hayPuerta,
  puedeIrALaPuerta,
  token,
  vieneDeSalir,
} from './api/identidad.ts';

/**
 * La falla de la ultima pasada de `arrancar()`, o `null` si no la hubo.
 *
 * **Variable de modulo y no un argumento de `montar`** porque el montaje es una funcion sin
 * argumentos a proposito —ver la cabecera: lo que importa es que sea LO ULTIMO que pasa— y porque
 * quien tiene que leerla no es `main.tsx` sino la aplicacion, tres capas mas abajo.
 *
 * Cada pasada la vuelve a fijar, asi que no hay estado viejo que arrastrar de una a otra.
 */
let laFalla: FallaDeLaPuerta | null = null;

/**
 * Por que no se mando a nadie a identificarse, si es que no se pudo.
 *
 * `null` en todo lo demas, **incluido el caso normal de ir a la puerta** — ese no monta nada, asi
 * que nadie llega a preguntar.
 */
export function fallaDeLaPuerta(): FallaDeLaPuerta | null {
  return laFalla;
}

/**
 * La vuelta del emisor que no se pudo canjear en la ultima pasada, o `null` (#355).
 *
 * Por lo mismo que `laFalla`: el montaje no lleva argumentos, y quien la lee es la aplicacion.
 * Solo se guarda la que FALLO: `canjeado` y `sin-vuelta` no tienen nada que contar.
 */
let laVuelta: VueltaFallida | null = null;

/**
 * Por que el emisor no dejo terminar la entrada, si volvimos de el con un fallo.
 *
 * `canjearSiVuelve` devuelve el motivo para que quien la llama **decida** con el (su javadoc lo
 * dice), y el arranque decide dos cosas: volver a la puerta mientras el tope lo admita, y, cuando
 * ya no, que la aplicacion monte diciendo esto en vez de un 401 sin causa.
 */
export function vueltaFallida(): VueltaFallida | null {
  return laVuelta;
}

/**
 * **Siembra el catalogo y esquiva la puerta, si y solo si se pidio en desarrollo** (#114).
 *
 * Devuelve si se sembro, que es lo que decide si hay que ir a la puerta. Las dos condiciones son
 * constantes al construir a proposito: ver la cabecera, y `verificaciones/` lo vigila.
 */
async function seSembroElCatalogo(): Promise<boolean> {
  if (!import.meta.env.DEV) return false;
  if (import.meta.env.VITE_KAMAYUK_SIN_PLATAFORMA !== 'true') return false;

  const { sembrarElCatalogo } = await import('../desarrollo/sembrarElCatalogo.ts');
  sembrarElCatalogo();
  return true;
}

/**
 * Canjea si volvemos del emisor, y solo entonces monta.
 *
 * Devuelve sin montar cuando manda a la puerta: `entrar()` navega fuera de la pagina, asi que
 * dibujar algo despues seria dibujar sobre un documento que el navegador esta a punto de tirar.
 *
 * **Y monta cuando la ida no llega a ocurrir** (#112): ahi no hay documento que se vaya, asi que
 * no montar deja la pagina en blanco y sin una linea que leer.
 */
export async function arrancar(montar: () => void): Promise<void> {
  laFalla = null;
  const vuelta = await canjearSiVuelve();
  laVuelta = vuelta.estado === 'fallo' ? vuelta : null;

  // La siembra va DESPUES del canje y ANTES de la puerta, y las dos cosas importan. Despues,
  // porque quien vuelve de Keycloak con un `?code=` en la barra tiene que ver su URL limpia
  // aunque la bandera este encendida; antes, porque esquivar la puerta es la mitad de lo que la
  // bandera hace — sin eso, `yarn dev` sin Keycloak sigue sin dibujar nada.
  if (await seSembroElCatalogo()) {
    montar();
    return;
  }

  if (token() === null && hayPuerta() && puedeIrALaPuerta() && !vieneDeSalir()) {
    laFalla = await entrar();
    // Solo se deja de montar cuando la navegacion SI ocurrio. La condicion se lee al reves de lo
    // que parece: `null` es que todo fue bien y la pagina se va.
    if (laFalla === null) return;
  }

  montar();
}
