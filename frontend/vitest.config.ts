import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  /**
   * La MISMA base que `vite.config.ts`, y no por simetria: de aqui sale
   * `import.meta.env.BASE_URL`, que es la raiz de la aplicacion.
   *
   * Con la base por omision —`/`— el entorno de pruebas no se parece al real **justo en lo
   * que falla**: el `redirect_uri` volvia a la raiz del SITIO en vez de a la de la
   * aplicacion, la prueba que lo fija afirmaba `http://localhost:5173/`, y las dos cosas
   * eran ciertas a la vez. El defecto llego a produccion con su prueba en verde.
   */
  base: '/rentas/',
  plugins: [react()],
  test: {
    environment: 'jsdom',
    // Sin globales: un `describe` que aparece de la nada no dice de donde sale, y el
    // compilador tampoco. Aqui cada cosa se importa.
    globals: false,
    // Las pruebas del codigo viven JUNTO al codigo; las de las barreras, en
    // `verificaciones/`, porque no prueban una unidad sino una propiedad del arbol.
    include: ['{src,verificaciones}/**/*.test.{ts,tsx}'],
    exclude: ['**/node_modules/**', '**/dist/**', 'verificaciones/muestras/**'],
    setupFiles: ['./vitest.setup.ts'],
  },
});
