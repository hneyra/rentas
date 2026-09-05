import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
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
