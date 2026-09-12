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
  /**
   * **UNA sola copia de React, aunque `@kamayuk/ui` venga por `link:`** (#88).
   *
   * El `link:` es un enlace simbolico al clon hermano, asi que un `import 'react'` desde dentro de
   * `@kamayuk/ui` —o desde `radix-ui`, que vive en el `node_modules` de aquel— se resuelve contra
   * **su** arbol y no contra el de este frontend. Resultado: dos React en la misma pagina, y el
   * segundo no tiene despachador de ganchos.
   *
   * El rojo que da no dice nada de esto:
   *
   *     Cannot read properties of null (reading 'useId')
   *
   * ...y sale en la primera pieza que use un gancho —`Etiqueta`, por su `useId()`—, o sea muy
   * lejos de la causa. Medido al estrenar las piezas de `kamayuk-lib`#11: catorce de dieciseis
   * pruebas del interprete en rojo con ese mensaje.
   *
   * **Y la lista tiene que incluir a `radix-ui`, no solo a React.** Con `['react', 'react-dom']` a
   * secas el rojo cambia de `useId` a `useCallback` y sigue ahi: lo que faltaba por deduplicar era
   * la libreria de primitivas, que arrastra su propio React desde el arbol del hermano. El cambio
   * de gancho en el mensaje es la unica pista de que se avanzo, y es facil leerla como «lo mismo».
   *
   * Va en los dos archivos —empaquetado y pruebas— porque cada uno resuelve por su cuenta, y
   * arreglar solo uno deja el otro roto de una forma que nadie mira hasta que le toca.
   */
  resolve: {
    dedupe: ['react', 'react-dom', 'radix-ui', 'react-hook-form', 'react-day-picker'],
  },
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
