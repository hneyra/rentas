/**
 * **El contraste de WCAG 1.4.3, en un solo sitio** (#140).
 *
 * <h2>Por que vive aparte, y por que lo importan dos mundos</h2>
 *
 * Lo usan dos guardas que miden el mismo umbral por extremos opuestos del camino:
 *
 *   1. `tinta-4-no-es-color-de-texto.test.ts`, que lo aplica a los HEX **del artboard** para
 *      demostrar que el token que se prohibe es ilegible de verdad — en vez de afirmarlo.
 *   2. `e2e/los-temas-llegan-al-navegador.spec.ts`, que lo aplica a los `rgb(...)` que
 *      **Chromium computa** sobre las dos frases del cajon, en las seis combinaciones.
 *
 * Escrito dos veces serian dos formulas que divergen, y la que se queda vieja es la que alguien
 * usa para decidir un color. Por eso admite las DOS formas en que un color llega —`#rrggbb` del
 * archivo y `rgb(r, g, b)` del navegador— en vez de obligar a cada lado a convertir por su cuenta.
 */

/**
 * Lo que WCAG 1.4.3 (nivel AA) pide para texto normal.
 *
 * El texto grande —18 pt, o 14 pt en negrita— se conforma con 3:1, y aqui no se contempla a
 * proposito: las dos frases del cajon van a **12 px**, que no es texto grande por ningun
 * criterio. Un umbral con excepciones invita a colocar el texto en la excepcion.
 */
export const UMBRAL_DE_TEXTO = 4.5;

/** `#93a3af` o `rgb(147, 163, 175)` -> `[147, 163, 175]`. Lo que devuelve el navegador y lo que trae el archivo. */
export function canalesDe(color: string): readonly [number, number, number] {
  const limpio = color.trim();

  const hex = /^#([0-9a-f]{6})$/i.exec(limpio);
  if (hex !== null) {
    const n = Number.parseInt(hex[1] as string, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }

  // `rgb(147, 163, 175)` y `rgba(147, 163, 175, 0.5)`, que es como lo devuelve `getComputedStyle`.
  const rgb = /^rgba?\(\s*([0-9.]+)[\s,]+([0-9.]+)[\s,]+([0-9.]+)/i.exec(limpio);
  if (rgb !== null) {
    return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])];
  }

  // Ni HEX ni `rgb()`. Devolver un color por omision seria calcular un contraste inventado y
  // darlo por bueno, que es justo el modo de fallo que estas guardas existen para impedir.
  throw new Error(`No se pudo leer el color «${color}»: se esperaba «#rrggbb» o «rgb(r, g, b)».`);
}

/** La luminancia relativa de WCAG, sobre los tres canales ya linealizados. */
export function luminancia(color: string): number {
  const [r, g, b] = canalesDe(color).map((canal) => {
    const c = canal / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * La razon de contraste entre dos colores, de 1 a 21.
 *
 * El orden no importa —se toma el mas claro como numerador—, que es como lo define WCAG y como lo
 * quiere quien la usa: nadie tiene que acordarse de cual era la tinta y cual el papel.
 */
export function contraste(uno: string, otro: string): number {
  const a = luminancia(uno);
  const b = luminancia(otro);
  const [claro, oscuro] = a > b ? [a, b] : [b, a];
  return (claro + 0.05) / (oscuro + 0.05);
}

/** Con dos decimales, que es como se lee en un rojo y como lo publica el issue. */
export const conDosDecimales = (razon: number): string => razon.toFixed(2);
