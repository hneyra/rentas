// @vitest-environment node
//
// Barre archivos del disco. No es un DOM lo que necesita.

import { readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { ARBOL } from '../src/pantallas/arbol.ts';

/**
 * **El interprete no sabe que existe este sistema** (#88, AC6).
 *
 * <h2>Por que se sigue vigilando desde AQUI, ahora que el interprete es de la libreria (#153)</h2>
 *
 * Hasta #153 el interprete vivia en `src/pantallas`, y esta guarda barria ese directorio. Subio a
 * `@kamayuk/ui` con `kamayuk-lib`#27, y alli lo barre `sin-suponer-un-sistema`. **Esa guarda cubre
 * esta solo en parte**, medido al subir: prohibe los prefijos de API, los globales y el vocabulario
 * tributario, pero no puede prohibir lo que solo sabe este repositorio —los diez rotulos de modulo,
 * los diez codigos, las cuarenta claves de hoja y `padron`—, porque una libreria comun **no puede
 * leer el arbol de un sistema**. Borrar esta guarda con el motivo de que «ya existe la equivalente»
 * habria dejado esa mitad sin vigilar.
 *
 * Asi que se queda, **apuntada al interprete dentro de `@kamayuk/ui`**, alcanzado por el enlace. Y
 * como `kamayuk-lib` corre `yarn verificar` de cada consumidor en su job `consumidores`, un PR de la
 * libreria que meta en el interprete una clave de hoja de Rentas se pone rojo **alli**, antes de
 * mezclarse.
 *
 * <h2>Donde esta el interprete dentro del paquete NO se escribe: se lee de su `index.ts`</h2>
 *
 * De la disposicion interna de `@kamayuk/ui` no hay nada prometido. Escribir `interprete/` aqui
 * seria una ruta que, el dia que la libreria reordene, dejaria esta guarda barriendo un directorio
 * que no existe — y el centinela de abajo lo diria, pero con un rojo que no nombra la causa. Se
 * busca de donde exporta el paquete `Pantalla`, y se barre ese directorio.
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
 * motivo: un comentario que dice de donde subio un archivo **es la explicacion de por que el codigo
 * de al lado es como es**. Lo que no puede aparecer es en el CODIGO. Y se omiten las pruebas: la
 * libreria prueba su interprete con definiciones inventadas, que no viajan.
 */

const requerir = createRequire(import.meta.url);

/** La raiz de `@kamayuk/ui`, por el enlace: la misma que usa `tailwind.ts`. */
const RAIZ_DE_UI = dirname(requerir.resolve('@kamayuk/ui'));

/** El directorio del modulo desde el que `@kamayuk/ui` exporta `Pantalla`, o `null` si no lo exporta. */
function dondeEstaElInterprete(): string | null {
  const indice = readFileSync(join(RAIZ_DE_UI, 'index.ts'), 'utf8');
  const casado = /export\s*\{[^}]*\bPantalla\b[^}]*\}\s*from\s*'([^']+)'/.exec(indice);
  return casado?.[1] === undefined ? null : dirname(resolve(RAIZ_DE_UI, casado[1]));
}

const DONDE = dondeEstaElInterprete();

function archivosDelInterprete(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const ruta = join(dir, e.name);
    if (e.isDirectory()) return archivosDelInterprete(ruta);
    if (!/\.tsx?$/.test(e.name) || e.name.includes('.test.')) return [];
    return [ruta];
  });
}

const ARCHIVOS = DONDE === null ? [] : archivosDelInterprete(DONDE);

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
    expect(DONDE, '`@kamayuk/ui` ya no exporta `Pantalla` desde su `index.ts`').not.toBeNull();
    // Pantalla, sus tres piezas, los tipos y los datos: seis.
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
        '  El interprete es de `@kamayuk/ui` y lo usan varios sistemas: lo que sabe de Rentas\n' +
        '  entra como dato por `props`, no escrito dentro. Se arregla en `kamayuk-lib`.',
    ).toEqual([]);
  });
});
