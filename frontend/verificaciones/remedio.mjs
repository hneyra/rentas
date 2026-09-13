/**
 * **El rojo que nombra el `git clone`, en `.mjs` para que lo pueda leer tambien ESLint** (#137).
 *
 * Esto vivia entero en `enlace.ts` y sigue saliendo por alli —`enlace.ts` lo reexporta, y las tres
 * pruebas que lo usan no se enteran—. Lo que cambio es que ahora hace falta **antes de que exista
 * TypeScript**: desde #137 `eslint.config.js` carga `eslint.prohibiciones.mjs`, que importa
 * `@kamayuk/verificaciones` del clon hermano, y eso lo ejecuta Node a secas al arrancar ESLint. Un
 * `import` de `./enlace.ts` desde ahi revienta con `ERR_UNKNOWN_FILE_EXTENSION` —Node 22 no quita
 * los tipos sin bandera—, asi que la parte que ESLint necesita vive en un `.mjs` y la unica copia
 * del mensaje sigue siendo una.
 *
 * El motivo de que el mensaje diga el `git clone` y no «Cannot find module» esta escrito en
 * `enlace.ts`, y es el hallazgo de #113: `yarn install --frozen-lockfile` con el hermano ausente
 * sale con **codigo 0** y no enlaza nada, de modo que el primer sintoma aparece dos pasos despues
 * y no se parece a su causa.
 */

/**
 * De un `link:` a la raiz del clon hermano que da por puesta.
 *
 * `../../kamayuk-lib/paquetes/formato` -> `../../kamayuk-lib`. Se deriva de la ruta en vez de
 * escribirse: un mensaje escrito a mano nombra el repositorio de ayer.
 *
 * @param {string} declarada
 * @returns {string | null}
 */
export function raizDelClon(declarada) {
  const partes = declarada.split('/');
  const hasta = partes.findIndex((parte) => parte !== '..' && parte !== '.');
  return hasta === -1 ? null : partes.slice(0, hasta + 1).join('/');
}

/**
 * Y de ahi, el nombre del repositorio: `../../kamayuk-lib` -> `kamayuk-lib`.
 *
 * @param {string} declarada
 * @returns {string | null}
 */
function clonDe(declarada) {
  const raiz = raizDelClon(declarada);
  return raiz === null ? null : (raiz.split('/').at(-1) ?? null);
}

/**
 * Que hacer cuando un `link:` no esta puesto, nombrando el `git clone` que lo haria existir.
 *
 * Se separo de `problemasDelEnlace` porque hace falta en tres sitios y dos no miran el disco:
 * `resolucion.ts` lo necesita cuando `require.resolve` revienta, que es DOS pasos antes de que
 * nadie llegue a preguntar por este directorio (#113), y `eslint.prohibiciones.mjs` cuando el
 * `import` de `@kamayuk/verificaciones` no resuelve, que es el PRIMER paso de `yarn verificar`
 * (#137).
 *
 * @param {string} paquete
 * @param {string} declarada
 * @returns {string}
 */
export function remedioDelEnlace(paquete, declarada) {
  const clon = clonDe(declarada);
  return clon === null
    ? `Revisa la ruta declarada para «${paquete}».`
    : `Este frontend NO funciona sin «${clon}» clonado al lado de «rentas»:\n` +
        `    git clone https://github.com/hneyra/${clon} ${raizDelClon(declarada) ?? ''}\n` +
        '  Y no basta con que yarn haya salido en verde: un `link:` a un directorio que no ' +
        'existe se instala con codigo 0 y sin avisar.';
}
