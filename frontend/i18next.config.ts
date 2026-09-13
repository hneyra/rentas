import { defineConfig } from 'i18next-cli';

/**
 * **La extraccion de claves** (#103).
 *
 * <h2>Que extrae, y de donde</h2>
 *
 * Todo lo que pase por `t(...)` en `src/`. Con el castellano como clave, lo que sale es
 * directamente **la lista que un traductor recibe**: 747 frases en castellano, no 747
 * identificadores que alguien tendria que descifrar.
 *
 * <h2>Por que `es.json` se llena, si i18next devolveria la clave igualmente</h2>
 *
 * Porque sin el no hay nada que sincronizar: un segundo idioma se hace **copiando `es.json` y
 * traduciendo los valores**, y con el archivo vacio no habria de donde copiar. Ese es el
 * andamiaje que el encargo pide.
 *
 * El riesgo de tener el castellano dos veces —en la definicion y en el locale— lo cierra
 * `verificaciones/el-locale-no-se-aparta-de-su-clave.test.ts`: **cada valor tiene que ser igual a
 * su clave**, salvo las formas plurales, que no pueden serlo. Asi el locale no puede divergir del
 * artboard sin ponerse rojo.
 */
export default defineConfig({
  locales: ['es'],
  extract: {
    input: ['src/**/*.{ts,tsx}'],
    output: 'src/i18n/locales/{{language}}.json',
    // La clave ES el castellano: lleva puntos, dos puntos y comas dentro. Con los separadores
    // puestos, «Nada se escribe hasta que pulse Guardar.» se partiria por el punto.
    keySeparator: false,
    nsSeparator: false,
    // Sin entrada, el valor es la propia clave. Es lo que hace que `es.json` no pueda divergir.
    defaultValue: (_locale: string, _ns: string, clave: string) => clave,
    // Ordenadas: un diff de este archivo tiene que decir que cadena cambio, no que todo se movio.
    sort: true,
  },
});
