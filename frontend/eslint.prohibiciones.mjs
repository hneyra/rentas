/**
 * Las prohibiciones de ESTE frontend: **las del producto, con las rutas de ESTE arbol**.
 *
 * <h2>Aqui habia una copia, y ya habia divergido (#137)</h2>
 *
 * Las nueve nacieron en este archivo y las mudo `kamayuk-lib`#4 a
 * `paquetes/verificaciones/prohibiciones.mjs`, cuya cabecera dice desde entonces que «las consumen
 * el `eslint.config.js` de este repositorio **y el de cada sistema**». No era cierto: aqui quedo la
 * copia, sin enlace y sin nadie que las comparara. **Medido antes de tocar nada**, importando los
 * dos modulos y comparandolos campo a campo:
 *
 *   · nueve claves a los dos lados, en el mismo orden;
 *   · **ocho de nueve identicas** en `clave`, `regla`, `selector` y `message`;
 *   · `REGLAS_EXIGIDAS`, identica;
 *   · y **una sola** diferencia, en `fetch-fuera-del-cliente`: el `salvo` y el `message` que lo
 *     nombra. Alli `['paquetes/api/', 'paquetes/sesion/']`; aqui `'src/api/'`, y ademas **como
 *     cadena y no como lista**, que es la diferencia semantica: las dos listas ya no se podian
 *     intercambiar.
 *
 * O sea que lo comun eran 1 755 bytes de selector y 1 070 de mensaje, y lo propio **una ruta**.
 * Con ese reparto, mantener dos copias sincronizadas cuesta mas que enlazar la buena: este archivo
 * pasa a **derivar** la lista de `@kamayuk/verificaciones` —sexto `link:` del frontend— y a poner
 * lo unico que es suyo.
 *
 * <h2>Por que la ruta es un parametro, y por que es una LISTA</h2>
 *
 * Porque la misma regla del producto necesita **una** ruta aqui y **dos** alla, y las dos veces con
 * razon. Medido en este arbol: los tres `fetch` viven en `src/api/cliente.ts` y
 * `src/api/identidad.ts`, o sea el cliente HTTP y la puerta PKCE en el MISMO directorio. En la
 * libreria esas dos piezas son dos paquetes —`paquetes/api/` y `paquetes/sesion/`—, asi que alli
 * hacen falta dos prefijos. Unificar las rutas seria falsificar uno de los dos arboles; lo que se
 * comparte es la lista de reglas, no donde cae cada una.
 *
 * <h2>Que sigue siendo verdad de este archivo</h2>
 *
 * Que no esta escrito dentro de `eslint.config.js` a proposito. Lo leen dos consumidores y tienen
 * que leer lo mismo:
 *
 *   1. `eslint.config.js`, que las convierte en opciones de `no-restricted-syntax`, y
 *   2. `verificaciones/reglas-de-eslint.test.ts`, que exige de cada una su muestra.
 *
 * Si la prueba tuviera su propia lista, seria una copia: se anade una regla al config, la lista de
 * la prueba no se toca, y la regla nueva queda sin muestra **en verde**. Derivadas de aqui las dos,
 * una prohibicion sin muestra sale roja sola.
 *
 * El `clave` no es decorativo: **es el nombre de su muestra**. La prueba no tiene un mapa de
 * «regla -> archivo» que alguien pueda dejar desactualizado; compone la ruta.
 *
 * Y que esta derivacion siga siendo una derivacion —y no vuelva a ser un fork— lo vigila
 * `verificaciones/las-prohibiciones-son-las-de-la-libreria.test.ts`.
 */

import { remedioDelEnlace } from './verificaciones/remedio.mjs';

/**
 * La lista del producto, o un rojo que nombra el `git clone`.
 *
 * **El `import` va dinamico y envuelto, y es el hallazgo de #113 otra vez.** Este archivo lo carga
 * `eslint.config.js`, o sea el PRIMER paso de `yarn verificar`, antes que `tsc` y antes que
 * `enlace-con-kamayuk-lib.test.ts` —que es la guarda que sabe explicar que falta el clon hermano y
 * que vive dos pasos mas tarde—. Con un `import` estatico, lo que se lee al clonar `rentas` a secas
 * es
 *
 *     Error: Cannot find package '@kamayuk/verificaciones' imported from …/eslint.prohibiciones.mjs
 *
 * que habla de un modulo y no de un repositorio que falta. Envuelto, dice el `git clone`.
 */
async function delProducto() {
  const declarada = '../../kamayuk-lib/paquetes/verificaciones';
  try {
    return await import('@kamayuk/verificaciones/prohibiciones');
  } catch (causa) {
    throw new Error(
      'No se pudo cargar «@kamayuk/verificaciones/prohibiciones», de donde salen las nueve\n' +
        `prohibiciones de ESLint de todo el producto (kamayuk-lib#4, rentas#137).\n  ${remedioDelEnlace('@kamayuk/verificaciones', declarada)}`,
      { cause: causa },
    );
  }
}

const { PROHIBICIONES: DEL_PRODUCTO, REGLAS_EXIGIDAS: EXIGIDAS } = await delProducto();

/**
 * El unico directorio de ESTE arbol que puede llamar a `fetch`.
 *
 * Es la excepcion que da sentido a la regla: mientras toda peticion pase por `solicitar()`,
 * enchufar el token, la clave de idempotencia y el formato de error se hace en un sitio.
 * Un `fetch` suelto en una pantalla no se salta una convencion: se salta las tres.
 */
export const CLIENTE_DE_API = 'src/api/';

/**
 * Donde `fetch` es legitimo AQUI, y en ningun otro sitio.
 *
 * **Es uno, y en la libreria son dos.** Alli el canje PKCE vive en `paquetes/sesion/`, separado del
 * cliente HTTP; aqui las dos piezas estan en `src/api/` —`cliente.ts` y `identidad.ts`—, asi que un
 * solo prefijo las cubre. Es una lista igualmente: el dia que este arbol separe la puerta de
 * identidad, lo que cambia es este dato y no la prohibicion.
 */
export const DONDE_SE_LLAMA_A_FETCH = [CLIENTE_DE_API];

/**
 * Lo UNICO que este arbol pone de su parte: donde cae cada excepcion.
 *
 * Va por **clave de prohibicion** y no por ruta de la libreria. Traducir `paquetes/api/` a
 * `src/api/` seria un mapa de directorios de otro repositorio, que se queda viejo el dia que alla
 * muevan uno; la clave, en cambio, es el identificador estable de la regla y es lo que la libreria
 * promete no cambiar.
 *
 * Una prohibicion con `salvo` que no este aqui **para el proceso**: dejarla pasar tendria dos
 * salidas y las dos malas —aplicarle la ruta de otra, o quitarle la excepcion y llenar de falsos
 * positivos un directorio entero—.
 *
 * @type {Readonly<Record<string, readonly string[]>>}
 */
export const SALVO_EN_ESTE_ARBOL = {
  'fetch-fuera-del-cliente': DONDE_SE_LLAMA_A_FETCH,
};

const sinTraducir = DEL_PRODUCTO.filter(
  (p) => p.salvo !== undefined && SALVO_EN_ESTE_ARBOL[p.clave] === undefined,
).map((p) => `  · ${p.clave}, exceptuada en la libreria de: ${[...(p.salvo ?? [])].join(', ')}`);

if (sinTraducir.length > 0) {
  throw new Error(
    '`@kamayuk/verificaciones` trae prohibiciones con excepcion que este arbol no ha situado:\n' +
      `${sinTraducir.join('\n')}\n` +
      'Anade su entrada a SALVO_EN_ESTE_ARBOL en `frontend/eslint.prohibiciones.mjs`, diciendo\n' +
      'que directorio de ESTE arbol hace lo que alli hace el suyo — o la lista vacia, si aqui no\n' +
      'hay ninguno.',
  );
}

const huerfanas = Object.keys(SALVO_EN_ESTE_ARBOL).filter(
  (clave) => !DEL_PRODUCTO.some((p) => p.clave === clave && p.salvo !== undefined),
);

if (huerfanas.length > 0) {
  throw new Error(
    `SALVO_EN_ESTE_ARBOL situa excepciones que ya nadie pide: ${huerfanas.join(', ')}.\n` +
      'O la prohibicion dejo de exceptuar nada, o cambio de clave. Una excepcion que no cuelga de\n' +
      'ninguna regla no exceptua: solo se queda ahi pareciendo que si.',
  );
}

/**
 * Las nueve del producto, cada una con la ruta que le toca en este arbol.
 *
 * @type {readonly {
 *   clave: string;
 *   regla: string;
 *   selector: string;
 *   message: string;
 *   salvo?: readonly string[];
 * }[]}
 */
export const PROHIBICIONES = DEL_PRODUCTO.map((prohibicion) =>
  prohibicion.salvo === undefined
    ? prohibicion
    : { ...prohibicion, salvo: SALVO_EN_ESTE_ARBOL[prohibicion.clave] },
);

/**
 * Las reglas del producto que el frontend expresa como verificacion. **Tal cual**: son las del
 * producto, no las de este sistema, y por eso se reexportan sin tocarlas.
 *
 * ES LA LISTA ESCRITA A MANO —alla—, y es deliberado que sea la unica. `PROHIBICIONES` se deriva
 * hacia la prueba, asi que **borrar una prohibicion borraria tambien su prueba**, en silencio.
 * Esta lista es lo que se pone rojo cuando eso pasa.
 *
 * @type {readonly string[]}
 */
export const REGLAS_EXIGIDAS = EXIGIDAS;
