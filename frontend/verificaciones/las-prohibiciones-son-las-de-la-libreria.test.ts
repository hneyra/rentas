// @vitest-environment node
//
// Compara dos modulos y lee un archivo del disco. No es un DOM lo que necesita.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  PROHIBICIONES as DEL_PRODUCTO,
  REGLAS_EXIGIDAS as EXIGIDAS_POR_EL_PRODUCTO,
} from '@kamayuk/verificaciones/prohibiciones';
import { describe, expect, it } from 'vitest';

import { PROHIBICIONES, REGLAS_EXIGIDAS, SALVO_EN_ESTE_ARBOL } from '../eslint.prohibiciones.mjs';

/**
 * **Las nueve prohibiciones son UNA lista, y este arbol solo le pone las rutas** (#137).
 *
 * <h2>El defecto que esto cierra</h2>
 *
 * Las nueve nacieron en `frontend/eslint.prohibiciones.mjs` y las mudo `kamayuk-lib`#4 a
 * `paquetes/verificaciones/prohibiciones.mjs`. Alli la cabecera dice que «las consumen el
 * `eslint.config.js` de este repositorio **y el de cada sistema**»... y aqui quedo la copia, sin
 * enlace y sin nadie que las comparara. **Ya habia divergido** cuando se midio.
 *
 * Y lo peor no era la divergencia sino que **no habia rojo que la dijera**: cada
 * `reglas-de-eslint.test.ts` compara contra SU propio archivo, asi que los dos repositorios
 * estaban en verde midiendo listas distintas. Es la misma forma que costo `kamayuk-lib`#8 con los
 * tokens —verde a los dos lados, midiendo cosas distintas— y la que cuesta cada vez: una decima
 * prohibicion anadida alla no habria llegado aqui, y nadie se habria enterado.
 *
 * <h2>Por que una comparacion y no «ya se importa, luego no puede divergir»</h2>
 *
 * Porque lo que impide la divergencia hoy es que `eslint.prohibiciones.mjs` DERIVE la lista, y eso
 * es una propiedad del archivo, no del sistema: se pierde con un `PROHIBICIONES = [` escrito
 * encima, que es exactamente como aparecio el fork la primera vez. Esta guarda es lo que se pone
 * rojo ese dia, y lo hace por partida doble:
 *
 *   · comparando **clave a clave y campo a campo** contra el modulo de la libreria, de modo que
 *     una prohibicion que exista a un lado y no al otro —o un mensaje reescrito— sale roja; y
 *   · exigiendo que este archivo **no escriba ningun `selector`**, que es lo unico que caza el
 *     fork el dia que se hace, cuando la copia todavia es identica y la comparacion pasaria.
 *
 * Lo que NO se compara es `salvo`: es lo unico que este arbol pone de su parte, y se comprueba
 * contra `SALVO_EN_ESTE_ARBOL` —no contra la libreria—, porque `src/api/` aqui y `paquetes/api/`
 * alla son correctas cada una en su arbol.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FUENTE_DE_LA_DERIVACION = readFileSync(join(AQUI, '..', 'eslint.prohibiciones.mjs'), 'utf8');

/** Los campos que son del PRODUCTO: identicos a los dos lados, sin excepcion. */
const DEL_PRODUCTO_Y_NO_DE_AQUI = ['clave', 'regla', 'selector', 'message'] as const;

describe('la lista es la de `@kamayuk/verificaciones`, no una copia suya', () => {
  it('EL CENTINELA: la libreria publica las nueve', () => {
    // Sin esto, una libreria que exportara una lista VACIA dejaria todo lo de abajo recorriendo
    // cero elementos y pasando en verde — con ESLint sin una sola prohibicion encendida.
    expect(
      DEL_PRODUCTO.length,
      '`@kamayuk/verificaciones` no publico ninguna prohibicion.',
    ).toBeGreaterThanOrEqual(9);
    expect(EXIGIDAS_POR_EL_PRODUCTO.length).toBeGreaterThanOrEqual(8);
  });

  it('y cada una trae sus cuatro campos con algo dentro', () => {
    // Esto es lo que sostiene `tipos/kamayuk-verificaciones.d.ts`, que declara la FORMA del
    // modulo porque TypeScript no aplica `allowJs` dentro de `node_modules`. Una declaracion
    // sin nadie que la ejerza es una promesa: si la libreria renombrara un campo, el `.d.ts`
    // seguiria compilando y la comparacion de abajo pasaria comparando `undefined` con
    // `undefined`. Aqui se mide el dato, no la declaracion.
    const mancos = DEL_PRODUCTO.filter((p) =>
      DEL_PRODUCTO_Y_NO_DE_AQUI.some((campo) => typeof p[campo] !== 'string' || p[campo] === ''),
    ).map((p) => p.clave ?? '(sin clave)');

    expect(
      mancos,
      'La forma que `tipos/kamayuk-verificaciones.d.ts` declara ya no es la que publica\n' +
        '`@kamayuk/verificaciones`. Corrige la declaracion — o el paquete, si lo que cambio\n' +
        'no tenia que cambiar.',
    ).toEqual([]);
  });

  it('ninguna prohibicion esta a un lado y no al otro', () => {
    const aqui = PROHIBICIONES.map((p) => p.clave);
    const alla = DEL_PRODUCTO.map((p) => p.clave);

    const soloAlla = alla.filter((clave) => !aqui.includes(clave));
    const soloAqui = aqui.filter((clave) => !alla.includes(clave));

    expect(
      { soloAlla, soloAqui },
      'Las dos listas se separaron, que es el defecto que #137 cerro:\n' +
        `  solo en @kamayuk/verificaciones: ${soloAlla.join(', ') || '(ninguna)'}\n` +
        `  solo en rentas:                  ${soloAqui.join(', ') || '(ninguna)'}\n` +
        'Las nueve son del PRODUCTO. Una que solo exista aqui es un fork empezando; una que\n' +
        'solo exista alla es una regla del producto que este frontend dejo de aplicar.',
    ).toEqual({ soloAlla: [], soloAqui: [] });
  });

  it.each(DEL_PRODUCTO.map((p) => ({ clave: p.clave })))(
    '«$clave» llega con el mismo selector y el mismo mensaje',
    ({ clave }) => {
      const alla = DEL_PRODUCTO.find((p) => p.clave === clave);
      const aqui = PROHIBICIONES.find((p) => p.clave === clave);

      for (const campo of DEL_PRODUCTO_Y_NO_DE_AQUI) {
        expect(
          aqui?.[campo],
          `«${clave}» tiene otro «${campo}» aqui que en la libreria. Ese campo es del producto:\n` +
            'reescribirlo de un lado deja las dos verificaciones en verde midiendo cosas\n' +
            'distintas. Si el texto esta mal, se corrige en `kamayuk-lib`.',
        ).toBe(alla?.[campo]);
      }
    },
  );

  it('y las reglas del producto se reexportan tal cual', () => {
    // `REGLAS_EXIGIDAS` es la unica lista escrita a mano de las dos, y la que impide que borrar
    // una prohibicion se lleve su prueba por delante. Escrita otra vez aqui, seria lo mismo que
    // la lista de prohibiciones: dos verdes sobre dos listas.
    expect(REGLAS_EXIGIDAS).toEqual(EXIGIDAS_POR_EL_PRODUCTO);
  });
});

describe('lo unico que este arbol pone es la ruta', () => {
  it('las que exceptuan algo son las mismas, exceptuen donde exceptuen', () => {
    const alla = DEL_PRODUCTO.filter((p) => p.salvo !== undefined).map((p) => p.clave);
    const aqui = PROHIBICIONES.filter((p) => p.salvo !== undefined).map((p) => p.clave);

    expect(
      aqui,
      'Una excepcion que existe a un lado y no al otro no es una ruta distinta: es otra regla.',
    ).toEqual(alla);
  });

  it('y cae donde `SALVO_EN_ESTE_ARBOL` dice, que es lo unico propio', () => {
    // Por clave y no por posicion: el orden de la lista lo pone la libreria y el del objeto,
    // quien lo escribe. Comparar por posicion daria un rojo que habla de orden el dia que haya
    // dos excepciones, y un rojo que no habla de su defecto se acaba ignorando.
    const situadas = Object.fromEntries(
      PROHIBICIONES.filter((p) => p.salvo !== undefined).map((p) => [p.clave, [...(p.salvo ?? [])]]),
    );

    expect(situadas).toEqual(
      Object.fromEntries(Object.entries(SALVO_EN_ESTE_ARBOL).map(([c, rutas]) => [c, [...rutas]])),
    );
  });

  it('las rutas de este arbol NO son las de la libreria, y esta bien que no lo sean', () => {
    // Es el centinela de la parametrizacion: si algun dia coincidieran, el `salvo` habria dejado
    // de ser un parametro sin que nadie lo dijera — y la siguiente ruta que la libreria mueva se
    // llevaria por delante el lint de este arbol.
    const alla = new Set(DEL_PRODUCTO.flatMap((p) => [...(p.salvo ?? [])]));
    const aqui = new Set(PROHIBICIONES.flatMap((p) => [...(p.salvo ?? [])]));

    expect([...aqui].some((ruta) => alla.has(ruta))).toBe(false);
  });
});

describe('LA GUARDA DEL FORK: este archivo deriva, no escribe', () => {
  it('no declara ni un `selector`', () => {
    // El dia que alguien vuelva a pegar la lista aqui, la comparacion de arriba PASARIA —la copia
    // recien hecha es identica— y el fork empezaria en verde, que es exactamente como empezo el
    // de #137. Esto es lo que lo caza en el momento de hacerlo.
    const declarados = FUENTE_DE_LA_DERIVACION.split('\n').filter((linea) =>
      /^\s*selector:/.test(linea),
    );

    expect(
      declarados,
      '`eslint.prohibiciones.mjs` esta escribiendo prohibiciones otra vez. Las nueve son del\n' +
        'producto y viven en `@kamayuk/verificaciones`; aqui solo se les ponen las rutas.',
    ).toEqual([]);
  });

  it('y lo que importa es el paquete, no una ruta al clon hermano', () => {
    // Un `import '../../kamayuk-lib/paquetes/verificaciones/prohibiciones.mjs'` tambien
    // «derivaria»... saltandose el `link:`, o sea sin `package.json` que lo declare, sin
    // `enlace-con-kamayuk-lib.test.ts` que lo vigile y con un `ENOENT` por todo rojo el dia que
    // el hermano no este.
    expect(FUENTE_DE_LA_DERIVACION).toContain("import('@kamayuk/verificaciones/prohibiciones')");

    const porRuta = FUENTE_DE_LA_DERIVACION.split('\n').filter((linea) =>
      /(?:^import .* from|\bimport\()\s*'[^']*kamayuk-lib\//.test(linea),
    );
    expect(porRuta, 'la libreria se alcanza por su nombre de paquete, no por el disco').toEqual([]);
  });
});
