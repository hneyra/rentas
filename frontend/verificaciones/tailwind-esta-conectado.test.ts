// @vitest-environment node
//
// En `node` y no en jsdom: importar `vite.config.ts` de verdad —en vez de leerlo como texto, que
// permitiria que la configuracion dijera una cosa y la prueba comprobara otra— arrastra a
// esbuild, que bajo jsdom muere con «Invariant violation: new TextEncoder().encode("")
// instanceof Uint8Array is incorrectly false».
import { describe, expect, it } from 'vitest';

import configuracion from '../vite.config.ts';

/**
 * **Tailwind procesa de verdad** (#90, AC2).
 *
 * <h2>Lo que esto anade a lo que #91 ya comprueba</h2>
 *
 * #91 compila la hoja de `@kamayuk/ui` **dentro de la prueba** y mide que los 38 colores del
 * artboard generan su utilidad. Eso demuestra que la paleta funciona; **no** demuestra que este
 * frontend la procese. Son dos cosas distintas y hasta #90 solo era cierta la primera: las clases
 * estaban escritas y no las leia nadie.
 *
 * Con el complemento fuera, todo sigue compilando y todas las pruebas siguen pasando —comparan
 * `className` como texto—, y la aplicacion sale **sin un solo estilo**. Es el fallo mas silencioso
 * que puede tener una interfaz: el HTML es correcto, la consola esta limpia, y la pantalla es una
 * columna de texto negro sobre blanco.
 *
 * <h2>Por que se mira la configuracion y no el CSS emitido</h2>
 *
 * Porque emitirlo exige un `vite build` entero —9 s— en cada corrida de la suite, y lo que se
 * quiere saber aqui cabe en una linea: que el complemento esta puesto. Que lo que emite es
 * correcto es justo lo que #91 mide, y esa prueba si compila de verdad.
 */

/** Los nombres de los complementos, sea cual sea la profundidad a la que Vite los anide. */
function aplanar(valor: unknown): { name?: string }[] {
  if (Array.isArray(valor)) return valor.flatMap((x: unknown) => aplanar(x));
  if (valor === null || valor === undefined) return [];
  return [valor as { name?: string }];
}

describe('Tailwind esta conectado a este frontend', () => {
  // Aplanado a mano y no con `flat(Infinity)`: el tipo de `plugins` de Vite es recursivo y con
  // `Infinity` el compilador se rinde —`TS2589: Type instantiation is excessively deep and
  // possibly infinite`—. Aqui solo interesan los nombres.
  const complementos = aplanar(configuracion.plugins ?? []);

  it('EL CENTINELA: la configuracion trae complementos', () => {
    // Sin esto, un `plugins` que dejara de existir —o un cambio de forma en la configuracion—
    // dejaria la lista vacia y la comprobacion de abajo fallando por el motivo equivocado, o
    // pasando si alguien la invirtiera.
    expect(complementos.length, 'vite.config.ts no declaro ni un complemento').toBeGreaterThan(1);
  });

  it('el complemento de Tailwind esta puesto', () => {
    const nombres = complementos.map((c) => c?.name ?? '').filter((n) => n !== '');
    expect(
      nombres.some((n) => n.includes('tailwind')),
      'Sin el complemento, las clases de `@kamayuk/ui` y del interprete no producen CSS: la\n' +
        'aplicacion sale sin un solo estilo y NADA se pone rojo — las pruebas comparan\n' +
        `\`className\` como texto. Complementos declarados: ${nombres.join(', ')}`,
    ).toBe(true);
  });

  it('y va ANTES que el de React', () => {
    // No es indiferente: el de Tailwind tiene que ver los archivos para saber que clases se usan.
    const nombres = complementos.map((c) => c?.name ?? '');
    const tailwind = nombres.findIndex((n) => n.includes('tailwind'));
    const react = nombres.findIndex((n) => n.includes('react'));
    expect(tailwind, 'no esta el de Tailwind').toBeGreaterThanOrEqual(0);
    expect(react, 'no esta el de React').toBeGreaterThanOrEqual(0);
    expect(tailwind).toBeLessThan(react);
  });
});
