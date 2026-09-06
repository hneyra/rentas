import { HOJAS, SECCIONES } from './arbol.ts';

/**
 * El enrutado del marco: la seccion abierta vive en el hash (AC4).
 *
 * <h2>Por que en el hash y no en la ruta</h2>
 *
 * `vite.config.ts` sirve la aplicacion bajo `/rentas/` (ADR-0030 §2) y el mismo
 * Traefik sirve las cuatro interfaces. Una ruta de verdad obligaria al servidor
 * a reescribir cualquier profundidad hacia `index.html`; el hash no llega al
 * servidor y hace enlazable la pantalla sin pedirle nada a la infraestructura.
 *
 * <h2>`replaceState`, y jamas `pushState`</h2>
 *
 * Abrir una seccion **no es navegar**: es cambiar de pestana dentro de la misma
 * pantalla, y las pestanas se abren de a decenas en un turno de ventanilla. Con
 * `pushState`, el «atras» del navegador tendria que pulsarse cuarenta veces para
 * salir de la aplicacion, y eso convierte el boton de atras en una trampa.
 *
 * Y hay un segundo motivo, que es el que se nota en pantalla: **asignar
 * `location.hash` provoca un salto de desplazamiento** —el navegador busca el
 * elemento con ese `id` y desplaza hasta el—. `replaceState` escribe la barra de
 * direcciones sin disparar el desplazamiento y sin emitir `hashchange`, que es
 * justo lo que hace falta para que marcar el hash no vuelva a entrar por el
 * oyente que lo escucha.
 */

/**
 * El slug de un destino, o `null` si el destino no existe.
 *
 * Una seccion propia lleva el suyo (`predios` se enlaza como `#contribuyentes`,
 * que es lo que una persona entiende al leer la barra de direcciones); una hoja
 * ajena usa su propia clave, que ya es unica en los cuarenta destinos.
 */
export function slugDe(destino: string): string | null {
  const seccion = SECCIONES.find((candidata) => candidata.clave === destino);
  if (seccion !== undefined) {
    return seccion.slug;
  }
  return HOJAS.has(destino) ? destino : null;
}

/** El destino que abre un slug, o `null` si no abre ninguno. */
export function destinoDeSlug(slug: string): string | null {
  const seccion = SECCIONES.find((candidata) => candidata.slug === slug);
  if (seccion !== undefined) {
    return seccion.clave;
  }
  return HOJAS.has(slug) ? slug : null;
}

/** El destino que pide el hash actual del navegador, o `null`. */
export function destinoDelHash(): string | null {
  return destinoDeSlug(window.location.hash.slice(1));
}

/**
 * Escribe el destino en el hash, con `replaceState`.
 *
 * El `try` no es decorativo: en un contexto sin permiso de historial —un
 * `iframe` de otro origen, un navegador con el historial capado— `replaceState`
 * lanza `SecurityError`, y el hash es una comodidad, no la fuente de verdad del
 * estado. Que no se pueda escribir no puede tumbar la pantalla.
 */
export function marcarHash(destino: string): void {
  const slug = slugDe(destino);
  if (slug === null) {
    return;
  }
  try {
    if (window.location.hash.slice(1) !== slug) {
      window.history.replaceState(null, '', `#${slug}`);
    }
  } catch {
    // Sin permiso de historial: la pantalla sigue funcionando, solo deja de ser
    // enlazable.
  }
}
