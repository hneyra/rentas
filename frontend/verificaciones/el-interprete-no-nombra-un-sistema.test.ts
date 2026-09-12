// @vitest-environment node
//
// Barre archivos del disco. No es un DOM lo que necesita.

import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { ARBOL } from '../src/pantallas/arbol.ts';

/**
 * **El interprete no sabe que existe este sistema** (#88, AC6).
 *
 * <h2>Por que se vigila desde AQUI y no desde la libreria</h2>
 *
 * Porque el interprete todavia vive aqui. `@kamayuk/ui` ya tiene su guarda —
 * `sin-suponer-un-sistema`— y barre sus propios paquetes; esta es la misma idea aplicada al
 * archivo que **va a mudarse alli**. Sin ella, la mudanza del dia que `catastro` se reconstruya
 * dejaria de ser mover un archivo y pasaria a ser desenredarlo.
 *
 * <h2>La lista prohibida NO se escribe: se deriva del arbol</h2>
 *
 * Los rotulos de los diez modulos, las cuarenta claves de hoja y sus rotulos salen de `ARBOL`. Una
 * lista escrita a mano se queda vieja el dia que el arbol cambie, y lo hace **en verde**: sigue
 * vigilando palabras que ya no existen y deja pasar las nuevas.
 *
 * <h2>Se omiten los comentarios, a proposito</h2>
 *
 * Es la misma decision que toma `sin-el-nombre-del-monolito` en `infrastructure`, y por el mismo
 * motivo: un comentario que dice «este archivo NO sabe que existe Rentas» **es la explicacion de
 * por que el codigo de al lado es como es**. Prohibirlo obligaria a escribir el javadoc en
 * acertijos. Lo que no puede aparecer es en el CODIGO.
 */

const DONDE = 'src/pantallas';

/** Los archivos del interprete: todo `src/pantallas` menos el dato, que SI es de este sistema. */
const SOLO_DATO = new Set(['arbol.ts', 'tipos.ts', 'avisos.ts', 'definiciones']);

function archivosDelInterprete(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    if (SOLO_DATO.has(e.name)) return [];
    const ruta = join(dir, e.name);
    if (e.isDirectory()) return archivosDelInterprete(ruta);
    if (!/\.tsx?$/.test(e.name) || e.name.includes('.test.')) return [];
    return [ruta];
  });
}

const ARCHIVOS = archivosDelInterprete(DONDE);

/** Sin comentarios de bloque ni de linea. Ver el javadoc. */
const sinComentarios = (fuente: string): string =>
  fuente.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/.*$/gm, ' ');

/**
 * Lo que el interprete no puede nombrar, derivado del arbol mas lo estructural.
 *
 * Los rotulos entran **enteros y entre limites de palabra**: «Panel» a secas es una palabra comun
 * y prohibirla suelta daria falsos rojos en cualquier comentario tecnico.
 */
const PROHIBIDO: readonly { readonly que: string; readonly patron: RegExp }[] = [
  ...ARBOL.map((m) => ({ que: `el modulo «${m.rotulo}»`, patron: new RegExp(`['"\`]${escapar(m.rotulo)}['"\`]`) })),
  ...ARBOL.map((m) => ({ que: `el codigo de modulo «${m.codigo}»`, patron: new RegExp(`\\b${escapar(m.codigo)}\\b`) })),
  ...ARBOL.flatMap((m) =>
    m.hojas.map((h) => ({ que: `la clave de hoja «${h.clave}»`, patron: new RegExp(`['"\`]${escapar(h.clave)}['"\`]`) })),
  ),
  { que: 'el prefijo de la API de un sistema', patron: /\/(rentas|catastro|caja|normativa|identidad)\/api/i },
  { que: 'el global de configuracion de un sistema', patron: /__KAMAYUK_[A-Z]+__/ },
  {
    que: 'vocabulario tributario, que es negocio de un contexto (ADR-0030 §4)',
    // Sin `\b` por delante: el limite de palabra no existe dentro de camelCase, y es justo ahi
    // donde el vocabulario se cuela de verdad —`totalDeArbitrios`—. Lo aprendio la libreria.
    patron: /arbitrios?\b|alicuotas?\b|autovaluo\b|predial\b|padron\b|padrón\b|contribuyentes?\b/i,
  },
];

function escapar(texto: string): string {
  return texto.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

describe('el interprete no nombra un sistema', () => {
  it('EL CENTINELA: hay archivos que barrer y palabras que prohibir', () => {
    // Sin esto, renombrar el directorio dejaria las comprobaciones de abajo recorriendo la lista
    // vacia y pasando en verde — que es como una guarda se queda sin sujeto sin que nadie la
    // borre. Ya paso en este repositorio con el artboard (#78).
    expect(ARCHIVOS.length, 'no se leyo ni un archivo del interprete').toBeGreaterThanOrEqual(5);
    // Diez modulos + diez codigos + cuarenta hojas + las cuatro estructurales.
    expect(PROHIBIDO.length, 'la lista prohibida vino vacia').toBeGreaterThanOrEqual(60);
  });

  it('ninguno nombra un modulo, una hoja, una ruta ni un tributo', () => {
    const hallazgos = ARCHIVOS.flatMap((ruta) => {
      const codigo = sinComentarios(readFileSync(ruta, 'utf8'));
      return PROHIBIDO.filter((p) => p.patron.test(codigo)).map(
        (p) => `  ${ruta}: nombra ${p.que}`,
      );
    });

    expect(
      hallazgos,
      'El interprete dejo de ser comun:\n' +
        `${hallazgos.join('\n')}\n\n` +
        '  La forma `[titulo, nota, campos, tabla]` es del PRODUCTO y este archivo esta destinado\n' +
        '  a `@kamayuk/ui`. Lo que sabe de Rentas entra como dato, no escrito dentro.',
    ).toEqual([]);
  });
});
