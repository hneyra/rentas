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
