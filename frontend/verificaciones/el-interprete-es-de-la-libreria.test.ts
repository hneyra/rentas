// @vitest-environment node
//
// Mira el arbol del disco. No es un DOM lo que necesita.

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

import { Pantalla } from '@kamayuk/ui';
import { describe, expect, it } from 'vitest';

import { RAIZ } from './artboards.ts';

/**
 * **El interprete de pantallas es de `@kamayuk/ui`, y aqui no vuelve** (#153).
 *
 * <h2>De donde viene</h2>
 *
 * `rentas` escribio el interprete para sus cuarenta pantallas (#88) sabiendo que no era suyo: su
 * propio docblock decia que estaba destinado a `@kamayuk/ui` y que se mudaria con el segundo
 * consumidor. Se mudo en `kamayuk-lib`#27, y en #153 se borro la copia de aqui:
 * `src/pantallas/Pantalla.tsx`, sus tres piezas de `src/pantallas/piezas/`, `datos.ts` y la mitad
 * de `tipos.ts` que el interprete leia.
 *
 * <h2>Por que una guarda, si basta con mirar</h2>
 *
 * Por lo mismo que `la-v6-no-esta`: un borrado se deshace sin querer —un `git checkout` de un
 * archivo suelto «para ver como era», una rama vieja que se mezcla— y **no rompe nada**. La copia
 * vuelta compila, nadie la importa y la suite sigue en verde. Lo que pasa es peor que un rojo: hay
 * dos interpretes, uno de ellos muerto, y el siguiente que arregle un defecto lo arregla en el que
 * no se sirve. O, si alguien la vuelve a importar, `rentas` deja de dibujar lo que dibujan los
 * demas sistemas y la divergencia no la ve ninguna guarda de la libreria.
 *
 * <h2>Por RUTA, y ademas por NOMBRE</h2>
 *
 * Por ruta, porque dice exactamente que no puede estar. Y por nombre —ningun archivo de `src/`
 * declara `Pantalla`, `BloqueDeLaPantalla`, `CampoDelBloque` ni `TablaDelBloque`—, porque la copia
 * que vuelve no tiene por que volver al mismo sitio.
 */

/** Lo que salio en #153, con lo que lo sustituye. */
const SE_FUE: readonly { readonly ruta: string; readonly que: string }[] = [
  { ruta: 'src/pantallas/Pantalla.tsx', que: 'el interprete; es `Pantalla` de `@kamayuk/ui`' },
  { ruta: 'src/pantallas/piezas', que: 'el bloque, el campo y la tabla del interprete; viven dentro de `@kamayuk/ui`' },
  { ruta: 'src/pantallas/datos.ts', que: '`Ausencia`, `Coordenada` y `DatosDeLaPantalla`; son de `@kamayuk/ui`' },
  { ruta: 'src/pantallas/Pantalla.test.tsx', que: 'sus doce pruebas; viven en `paquetes/ui/interprete/Pantalla.test.tsx`' },
];

/** Los componentes del interprete, por su nombre. Declararlos en `src/` es tener una copia. */
const PIEZAS = ['Pantalla', 'BloqueDeLaPantalla', 'CampoDelBloque', 'TablaDelBloque'] as const;

/** Todos los `.ts`/`.tsx` bajo `src/`, pruebas incluidas: una copia en una prueba tambien es una copia. */
function fuentes(desde = join(RAIZ, 'src')): readonly string[] {
  return readdirSync(desde).flatMap((entrada) => {
    const ruta = join(desde, entrada);
    if (statSync(ruta).isDirectory()) return fuentes(ruta);
    return /\.tsx?$/.test(entrada) ? [ruta] : [];
  });
}

describe('el interprete es de la libreria', () => {
  it('EL CENTINELA: la libreria SI lo publica, y `src/` se puede leer', () => {
    // Sin esto, una libreria que dejara de exportarlo haria que la guarda de abajo pasara en verde
    // sobre un sistema que no tiene con que dibujar sus pantallas.
    expect(typeof Pantalla, '`@kamayuk/ui` no exporta `Pantalla`').toBe('function');
    expect(fuentes().length, 'no se leyo ni un archivo de `src/`').toBeGreaterThan(20);
  });

  it('la copia de `rentas` no volvio al arbol', () => {
    const vueltas = SE_FUE.filter((x) => existsSync(join(RAIZ, x.ruta))).map(
      (x) => `  ${x.ruta} — ${x.que}`,
    );
    expect(
      vueltas,
      'La copia del interprete esta otra vez en el arbol:\n' +
        `${vueltas.join('\n')}\n\n` +
        '  Hay dos interpretes y solo uno se sirve. Si hace falta cambiar como se dibuja una\n' +
        '  pantalla, se cambia en `kamayuk-lib/paquetes/ui/interprete/`.',
    ).toEqual([]);
  });

  it('y ningun archivo de `src/` declara una pieza del interprete con otro nombre de archivo', () => {
    const declaracion = new RegExp(
      `\\b(?:function|const|class)\\s+(${PIEZAS.join('|')})\\b`,
    );
    const copias = fuentes().flatMap((ruta) => {
      const casado = declaracion.exec(readFileSync(ruta, 'utf8'));
      return casado === null ? [] : [`  ${relative(RAIZ, ruta)} declara «${casado[1] ?? ''}»`];
    });
    expect(
      copias,
      'Hay una copia de una pieza del interprete en `src/`:\n' +
        `${copias.join('\n')}\n\n` +
        '  Se importa de `@kamayuk/ui`. Lo que este sistema le dice al interprete —la traduccion,\n' +
        '  sus tres palabras y el tono de las insignias— va en `src/pantallas/PantallaDeRentas.tsx`.',
    ).toEqual([]);
  });
});
