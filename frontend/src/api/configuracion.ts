/**
 * Lo que la interfaz NO puede saber cuando se construye.
 *
 * <h2>El problema, medido y no supuesto</h2>
 *
 * `vite build` sustituye cada `import.meta.env.VITE_*` por su valor **al construir** y despues
 * pliega lo que dependa de el: lo que queda en el paquete es una constante, no una lectura. Es
 * la propiedad de la que vive `arranque.ts` para sacar el proxy de datos del bundle, y ahi es
 * justo lo que se quiere. Aqui es el defecto: la URL del emisor OIDC no es la misma en el puesto
 * de quien desarrolla (`localhost:8181`), en la marcha blanca y en la municipalidad, y una URL
 * horneada convierte la imagen en la imagen **de un ambiente**.
 *
 * <h2>Lo que el monolito hizo, y por que aqui no cabe</h2>
 *
 * `sgtm` construyo una imagen por ambiente —`sgtm-interfaz:${environment}-${version}`—. Aqui eso
 * choca de frente con la etiqueta que #44 AC-1 pide y que `publicar-imagenes.yml` ya usa para las
 * otras dos imagenes: **el `sha` de este repositorio**. Una imagen por ambiente obliga a meter el
 * nombre del ambiente en la etiqueta, y entonces se pierden dos cosas a la vez: que la etiqueta
 * resuelva contra un `git log` —«que corre en la municipalidad» deja de tener respuesta— y que lo
 * verificado en la marcha blanca sea **el mismo artefacto** que se promueve a produccion. Con una
 * imagen por ambiente no se promueve nada: se vuelve a construir, y lo que se despliega no es lo
 * que se probo.
 *
 * <h2>La salida: un archivo que se sirve, no un valor que se hornea</h2>
 *
 * El ambiente entra **al arrancar el contenedor** y no al construir la imagen. `index.html` carga
 * `configuracion.js` —un guion clasico, y por tanto antes que el modulo, que va diferido— y ese
 * archivo deja un objeto en `window`. En el cluster lo entrega un `ConfigMap` montado sobre el
 * que la imagen trae; en `yarn dev` y en la imagen sin montar, el que viaja en `public/` esta
 * VACIO a proposito y la cadena cae al escalon siguiente.
 *
 * <h2>Los tres escalones, y por que el ultimo no es «fallar»</h2>
 *
 * `servida` -> `de la construccion` -> `por omision`. El ultimo son las senias de la instalacion
 * local, que es donde corre `yarn dev` y donde corren las pruebas: fallar ahi obligaria a que
 * todo arnes montara un `window.__KAMAYUK_RENTAS__` para dibujar una pantalla que no entra a
 * ninguna puerta. Lo que **no** hace la cadena es tratar la cadena vacia como un valor: un
 * `ConfigMap` con la llave puesta y el valor en blanco es un error de despliegue, y heredar de
 * el una URL vacia daria un rebote a `"/protocol/openid-connect/auth"` —una ruta de la propia
 * interfaz— que contesta 200 con el `index.html` dentro. Es el «200 que miente» de #44 aplicado
 * a la puerta de identidad: por eso una cadena en blanco cuenta como ausencia.
 */

/** Las senias que se resuelven al arrancar y no al construir. */
export type ClaveDeConfiguracion = 'oidcRealm' | 'oidcCliente' | 'oidcAlcance';

declare global {
  interface Window {
    /**
     * Lo que deja `configuracion.js`. Opcional en el tipo porque de verdad puede no estar: el
     * arnes de pruebas monta la aplicacion sin cargar ningun guion clasico.
     */
    __KAMAYUK_RENTAS__?: Partial<Record<ClaveDeConfiguracion, string>>;
  }
}

/**
 * El tercer escalon: la instalacion local.
 *
 * `kamayuk-backoffice` y `localhost:8181` son los que hacen que `yarn dev` entre por la puerta sin
 * configurar nada, y los mismos que `identidad.ts` traia escritos antes de que existiera este
 * archivo.
 */
const POR_OMISION: Record<ClaveDeConfiguracion, string> = {
  oidcRealm: 'http://localhost:8181/realms/kamayuk',
  oidcCliente: 'kamayuk-backoffice',
  oidcAlcance: 'openid profile',
};

/**
 * El segundo escalon: lo que Vite horneo al construir.
 *
 * Se escriben las tres lecturas **literales**, una por linea, y no con un indice calculado:
 * Vite sustituye `import.meta.env.VITE_ALGO` reconociendolo en el texto, asi que
 * `import.meta.env[clave]` no se sustituiria y las tres saldrian `undefined` en el paquete
 * —en silencio, porque la cadena tiene un escalon mas debajo—.
 */
const DE_LA_CONSTRUCCION: Record<ClaveDeConfiguracion, string | undefined> = {
  oidcRealm: import.meta.env.VITE_KAMAYUK_OIDC_REALM,
  oidcCliente: import.meta.env.VITE_KAMAYUK_OIDC_CLIENTE,
  oidcAlcance: import.meta.env.VITE_KAMAYUK_OIDC_ALCANCE,
};

/** Una cadena en blanco no es un valor: es una llave puesta sin rellenar. Ver la cabecera. */
function siTieneAlgo(valor: string | undefined): string | undefined {
  const limpio = valor?.trim();
  return limpio === undefined || limpio === '' ? undefined : limpio;
}

/** Lo que sirve el contenedor, si es que sirve algo. */
function servida(clave: ClaveDeConfiguracion): string | undefined {
  if (typeof window === 'undefined') return undefined;
  return siTieneAlgo(window.__KAMAYUK_RENTAS__?.[clave]);
}

/**
 * El valor de una senia, resuelto por los tres escalones.
 *
 * Se lee **en tiempo de ejecucion** a proposito: si esto se resolviera en una constante de
 * modulo, quien la importara la congelaria en el orden de carga de los modulos, que es
 * exactamente el defecto contra el que existe este archivo.
 */
export function configuracion(clave: ClaveDeConfiguracion): string {
  return servida(clave) ?? siTieneAlgo(DE_LA_CONSTRUCCION[clave]) ?? POR_OMISION[clave];
}

/** De donde salio el valor. Existe para que la prueba pueda distinguir escalon de escalon. */
export function procedencia(clave: ClaveDeConfiguracion): 'servida' | 'construccion' | 'omision' {
  if (servida(clave) !== undefined) return 'servida';
  if (siTieneAlgo(DE_LA_CONSTRUCCION[clave]) !== undefined) return 'construccion';
  return 'omision';
}
