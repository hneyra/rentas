/**
 * **Los tipos de `@kamayuk/verificaciones/prohibiciones`, hasta que los publique la libreria** (#137).
 *
 * <h2>Por que hace falta, medido</h2>
 *
 * El paquete publica las nueve prohibiciones como `prohibiciones.mjs` —JavaScript con JSDoc, que
 * es lo correcto: lo tiene que poder cargar ESLint a pelo, antes de que exista TypeScript—. Dentro
 * de `kamayuk-lib` eso se tipa solo, porque alli el archivo se importa por ruta relativa y
 * `allowJs` lo alcanza. **Desde aqui no**: llega por `node_modules/@kamayuk/verificaciones/`, y
 * TypeScript **no aplica `allowJs` a nada que cuelgue de `node_modules`**. El rojo, medido:
 *
 *     eslint.prohibiciones.mjs(71,25): error TS7016: Could not find a declaration file for module
 *       '@kamayuk/verificaciones/prohibiciones'. '…/node_modules/@kamayuk/verificaciones/
 *       prohibiciones.mjs' implicitly has an 'any' type.
 *
 * ...y con el, once `TS7006` derivados, uno por cada `p` de las derivaciones que quedan sin tipo.
 *
 * La salida de `maxNodeModuleJsDepth` **se probo y no vale**: abre `node_modules` ENTERO al
 * comprobador, y lo primero que sale son veintitantos errores dentro de `jsdom` pidiendo
 * `@types/whatwg-url`. Un arreglo que pone en rojo dependencias de terceros no es un arreglo.
 *
 * <h2>Que se declara, y que NO</h2>
 *
 * **La FORMA, nunca la lista.** Aqui no hay ni una clave, ni un selector, ni un mensaje: eso es lo
 * que #137 dejo de copiar. Y que la forma siga siendo esta no depende de leer este archivo: lo
 * comprueba en tiempo de ejecucion `las-prohibiciones-son-las-de-la-libreria.test.ts`, que exige
 * de cada prohibicion de la libreria sus cuatro campos con texto dentro.
 *
 * Se declara ademas **solo lo que este arbol importa**. `CLIENTE_DE_API`, `PUERTA_DE_IDENTIDAD` y
 * `DONDE_SE_LLAMA_A_FETCH` son las rutas de la libreria y aqui no se usan —las de este arbol las
 * pone `eslint.prohibiciones.mjs`—, asi que no se nombran: una declaracion que promete mas de lo
 * que se usa es superficie que nadie ejercita.
 *
 * **Esto se borra el dia que `kamayuk-lib` publique un `prohibiciones.d.mts`**, que es donde
 * tendria que estar.
 */
declare module '@kamayuk/verificaciones/prohibiciones' {
  export interface Prohibicion {
    /** Identificador estable. Tambien el nombre del archivo de su muestra, sin extension. */
    readonly clave: string;
    /** La fila de la tabla de reglas del producto a la que sirve. */
    readonly regla: string;
    /** Selector ESQuery que la detecta. Admite varios separados por coma. */
    readonly selector: string;
    /** Lo que se le dice a quien la incumple. */
    readonly message: string;
    /**
     * Prefijos de ruta donde NO aplica, **en el arbol de la libreria**. Es una lista desde
     * `kamayuk-lib`#4, y por eso este arbol puede tener un numero distinto de prefijos para la
     * misma regla sin que ninguna de las dos cifras sea un error.
     */
    readonly salvo?: readonly string[];
  }

  export const PROHIBICIONES: readonly Prohibicion[];
  export const REGLAS_EXIGIDAS: readonly string[];
}
