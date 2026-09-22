/**
 * **El `Request` del arnes acepta la senal que crea el documento** (#289). Se IMPORTA de la
 * libreria y no se escribe aqui: es `kamayuk-lib`#90, publicado en #92, y el porque entero —con la
 * fuente de `undici` delante— esta en `arnes-del-request.ts`.
 *
 * El resumen: bajo Vitest el `Request` es el de `undici` y el `AbortSignal` es el de jsdom, y
 * desde Node 24 `undici` 7 rechaza la senal de otro realm. Quien construye ese `Request` es
 * `react-router`, en cada navegacion, asi que sin esta linea y con Node 24
 * `la-siembra-abre-los-destinos.test.tsx` daba **7 pruebas en verde y 48 errores**, y el arbol
 * entero **3 960** con `yarn verificar` en RC=1 y ninguna prueba roja.
 *
 * **Por que no devolverle a jsdom el `AbortController` de Node**, que fue el primer arreglo que se
 * escribio para este issue (un entorno de vitest «de un solo realm»): quitaba los 48, pero rompia
 * la otra mitad al reves —medido con Node 24.21.0: `document.addEventListener('x', f, { signal })`
 * lanzaba `TypeError: Failed to execute 'addEventListener' on 'EventTarget': parameter 3
 * dictionary has member 'signal' that is not of type 'AbortSignal'`—. Es lo mismo que la libreria
 * dejo escrito, y lo que ya adoptaron `caja` y `normativa`.
 *
 * Va lo primero: `react-router` construye su `Request` en la primera navegacion.
 */
import '@kamayuk/verificaciones/arnes-del-request';
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

/**
 * Testing Library limpia el DOM entre pruebas por su cuenta solo cuando Vitest corre con
 * `globals: true`. Aqui corre sin globales —los importes explicitos dicen de donde sale
 * cada cosa— asi que la limpieza se enchufa a mano; sin ella, la segunda prueba encuentra
 * dos aplicaciones montadas y `getByRole` falla por ambiguo, que es un rojo que no habla
 * de lo que se estaba probando.
 */
afterEach(cleanup);

/**
 * **La instancia de i18next, para TODAS las pruebas** (#103).
 *
 * `react-i18next` sin proveedor usa la instancia global de `i18next`, que solo existe si alguien
 * la inicializo. En la aplicacion lo hace `main.tsx`; en las pruebas no lo hacia nadie, y el
 * sintoma era pequeno y confuso: `t('{{count}} registro', { count: 2 })` devolvia **«2 registro»**
 * —la clave, interpolada, sin elegir forma plural— porque el recurso que trae `_one` y `_other` no
 * estaba cargado.
 *
 * Importarlo aqui es lo que hace que una prueba de componente vea **lo mismo que la pantalla**.
 * Ponerlo en cada archivo que lo necesite seria lo contrario: la que se olvidara pasaria en verde
 * comprobando texto sin traducir.
 */
import './src/i18n/i18n.ts';
