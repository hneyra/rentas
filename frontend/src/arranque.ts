/**
 * El arranque de `rentas-web`: primero quien contesta, despues quien pregunta.
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
 * <h2>La bandera se lee de `import.meta.env`, y de ahi y no de otro sitio</h2>
 *
 * Vite sustituye `import.meta.env.VITE_*` por su valor **al construir**, asi que con la bandera
 * apagada la condicion queda en `"false" === "true"`, Rollup la pliega y **se lleva por delante
 * el `import()` dinamico entero** — el proxy, las trece operaciones y todas las cifras del
 * artboard. Ese es todo el mecanismo de AC3.
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

/** Instala el proxy si la bandera lo pide, y solo entonces monta la aplicacion. */
export async function arrancar(montar: () => void): Promise<void> {
  if (import.meta.env.VITE_KAMAYUK_PROXY_DE_DATOS === 'true') {
    const { instalarProxyDeDatos } = await import('./api/proxy.ts');
    instalarProxyDeDatos({ latencia: true });
  }
  montar();
}
