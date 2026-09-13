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
