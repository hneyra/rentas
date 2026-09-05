import js from '@eslint/js';
import globals from 'globals';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

import { PROHIBICIONES } from './eslint.prohibiciones.mjs';

/**
 * Reglas de ESLint del frontend de `rentas`.
 *
 * Mismo criterio que en el backend (ARQ-04) y que en `infra/`: **toda prohibicion que
 * pueda expresarse como verificacion automatica se expresa asi.** Una prohibicion que solo
 * vive en un documento se incumple en seis meses, y nadie se entera hasta que hay que
 * arreglar veinte sitios.
 *
 * Las prohibiciones NO estan aqui: estan en `eslint.prohibiciones.mjs`, porque las lee
 * tambien `verificaciones/reglas-de-eslint.test.ts`, que exige de cada una su muestra que
 * la viola. **Una regla que no puede fallar no protege nada.**
 */

/** Las prohibiciones que valen en todo el arbol. */
const EN_TODAS_PARTES = PROHIBICIONES.map(({ selector, message }) => ({ selector, message }));

/**
 * Las excepciones, una por directorio exceptuado.
 *
 * Se derivan de los `salvo` en vez de escribirse: una excepcion escrita a mano se olvida
 * de la prohibicion que se anadio ayer, y la deja apagada en un directorio entero.
 */
const EXCEPCIONES = [...new Set(PROHIBICIONES.map((p) => p.salvo).filter((s) => s !== undefined))];

/** @type {import('eslint').Linter.Config[]} */
const bloquesDeExcepcion = EXCEPCIONES.map((directorio) => ({
  files: [`${directorio}**/*.{ts,tsx}`],
  rules: {
    'no-restricted-syntax': [
      'error',
      ...PROHIBICIONES.filter((p) => p.salvo !== directorio).map(({ selector, message }) => ({
        selector,
        message,
      })),
    ],
  },
}));

export default tseslint.config(
  {
    ignores: [
      '**/dist/**',
      '**/node_modules/**',
      '**/*.config.js',
      '**/*.config.ts',
      // Violan las reglas a proposito. Se lintan desde la prueba, con su texto y una ruta
      // sintetica dentro de `src/`, que es donde la regla tiene que aplicar de verdad.
      'verificaciones/muestras/**',
    ],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,

  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.es2022 },
    },
    plugins: {
      'react-hooks': reactHooks,
      'jsx-a11y': jsxA11y,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.flatConfigs.recommended.rules,

      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],

      'no-restricted-syntax': ['error', ...EN_TODAS_PARTES],
    },
  },

  ...bloquesDeExcepcion,

  {
    // Las pruebas y los arneses corren en Node y hablan DE las prohibiciones: una prueba
    // que no puede escribir `municipalidadId` no puede comprobar que esta prohibido.
    //
    // `verificaciones/*.ts` y no `verificaciones/**/*.ts`: un comodin de dos niveles se
    // llevaria por delante `verificaciones/muestras/`, y entonces las muestras dejarian de
    // violar nada a ojos de `yarn lint`. Hoy no se lintan porque estan en `ignores`; si
    // manana alguien quita esa linea, tienen que ponerse ROJAS, no pasar en silencio.
    files: ['**/*.test.{ts,tsx}', 'verificaciones/*.ts'],
    languageOptions: { globals: { ...globals.node } },
    rules: { 'no-restricted-syntax': 'off' },
  },
);
