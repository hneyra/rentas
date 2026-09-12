// @vitest-environment node
//
// Lee el artboard del DISCO. Bajo jsdom `import.meta.url` no es una URL `file:` y
// `fileURLToPath` revienta con `TypeError: The URL must be of scheme file`.

import { describe, expect, it } from 'vitest';

import { ARBOL, CLAVES_DE_HOJA } from '../src/pantallas/arbol.ts';
import { PANTALLAS } from '../src/pantallas/definiciones/index.ts';
import {
  esCampoDeCasilla,
  esCampoDeLista,
  esCampoDeSoloLectura,
  type Bloque,
  type Campo,
  type Modulo,
  type Pantalla,
  type Tabla,
} from '../src/pantallas/tipos.ts';
import {
  artboardV8,
  hojasDelArtboard,
  type BloqueDelArtboard,
  type CampoDelArtboard,
  type ModuloDelArtboard,
  type TablaDelArtboard,
} from './artboard-v8.ts';

/**
 * **La guarda anti-deriva: el codigo dice lo que el artboard dice** (UI-5, #85, AC4).
 *
 * Es la que responde a «debe lucir idéntico». No cuenta pantallas: las **compara**, hoja por
 * hoja, bloque por bloque, campo por campo y tipo por tipo, contra
 * `frontend/diseno/RentasV8.dc.html` — el archivo vendorizado, no una copia de sus datos escrita
 * aqui. Si la lista viviera en esta prueba, cambiar el dato y cambiar la prueba serian el mismo
 * commit y nadie se enteraria; 302 campos con su etiqueta y su tipo son justo la clase de dato
 * que se «arregla» de memoria.
 *
 * <h2>Que NO comprueba esta guarda, porque lo comprueba el compilador</h2>
 *
 * Que haya **una pantalla por hoja y ninguna de mas** (AC2) no se afirma aqui con un `expect`:
 * `PANTALLAS` esta declarado `satisfies Record<ClaveDeHoja, Pantalla>` y `ClaveDeHoja` sale del
 * propio `ARBOL`, asi que una hoja sin pantalla no compila. Lo que si se afirma aqui es que el
 * arbol del que sale `ClaveDeHoja` **sea el del artboard**, que es la mitad que el compilador no
 * puede saber.
 *
 * <h2>Por que el disco se toca dentro de cada `it`</h2>
 *
 * AC6, y viene de un defecto medido. Cuatro barreras de este directorio leen su artboard en el
 * cuerpo del modulo; el dia que el archivo falte mueren durante la RECOLECCION y sus pruebas no
 * llegan a existir. Aqui la lista de casos sale de `CLAVES_DE_HOJA` —que es codigo importado y
 * no puede faltar—, y el artboard se lee dentro del caso: si falta, salen cuarenta rojos que
 * **nombran el archivo**, no un `ENOENT` sobre una ruta larga.
 */

/* ── De nuestro dato a la forma del artboard ───────────────────────────────────────────── */
//
// Un solo adaptador, y no dos descriptores. Convertir nuestro lado a la forma posicional del
// artboard y describir las dos desde ahi deja una sola pieza que pueda equivocarse; con un
// descriptor por lado, el dia que uno derive el otro le seguiria la corriente.

/** Un campo nuestro, en la forma `[etiqueta, tipo, opciones | ayuda]` del artboard. */
function campoComoElArtboard(campo: Campo): CampoDelArtboard {
  if (esCampoDeLista(campo)) return [campo.etiqueta, campo.tipo, campo.opciones];
  if (esCampoDeSoloLectura(campo)) return [campo.etiqueta, campo.tipo, campo.valor];
  if (esCampoDeCasilla(campo)) return [campo.etiqueta, campo.tipo, campo.casilla];
  if (campo.ayuda === undefined) return [campo.etiqueta, campo.tipo];
  return [campo.etiqueta, campo.tipo, campo.ayuda];
}

/** Una tabla nuestra, en la forma `{ t, c, f, n?, i?, a?, cn? }` del artboard. */
function tablaComoElArtboard(tabla: Tabla): TablaDelArtboard {
  // Las claves opcionales se OMITEN cuando no estan, en vez de ponerlas a `undefined`: lo que
  // compara abajo es `toStrictEqual`, que distingue las dos cosas. Es deliberado — con
  // `toEqual`, una nota perdida en la transcripcion pasaria por «no habia nota».
  return {
    t: tabla.titulo,
    ...(tabla.conteo === undefined ? {} : { cn: tabla.conteo }),
    ...(tabla.accion === undefined ? {} : { a: tabla.accion }),
    c: tabla.columnas.map((columna) => [columna.rotulo, columna.alineadoDerecha ? 1 : 0] as const),
    f: tabla.filas,
    ...(tabla.columnaDeInsignia === undefined ? {} : { i: tabla.columnaDeInsignia }),
    ...(tabla.nota === undefined ? {} : { n: tabla.nota }),
  };
}

/** Un bloque nuestro, en la forma `[titulo, nota, campos, tabla?]` del artboard. */
function bloqueComoElArtboard(bloque: Bloque): BloqueDelArtboard {
  const campos = bloque.campos.map(campoComoElArtboard);
  if (bloque.tabla === undefined) return [bloque.titulo, bloque.nota, campos];
  return [bloque.titulo, bloque.nota, campos, tablaComoElArtboard(bloque.tabla)];
}

/** Una pantalla nuestra, en la forma del artboard. */
function pantallaComoElArtboard(pantalla: Pantalla): readonly BloqueDelArtboard[] {
  return pantalla.bloques.map(bloqueComoElArtboard);
}

/** Un modulo nuestro, en la forma `[rotulo, nota, clave, codigo, trazos, hojas]`. */
function moduloComoElArtboard(modulo: Modulo): ModuloDelArtboard {
  return [
    modulo.rotulo,
    modulo.nota,
    modulo.slug,
    modulo.codigo,
    modulo.trazos,
    modulo.hojas.map((hoja) => [
      hoja.clave,
      hoja.rotulo,
      hoja.operaciones.map((operacion) => [operacion.verbo, operacion.ruta, operacion.nota] as const),
      hoja.piezasDeclaradas.map((pieza) => [pieza.pieza, pieza.uso] as const),
    ]),
  ];
}

/* ── El descriptor, que es el que da un rojo legible ───────────────────────────────────── */

/** Un campo, en una linea con su coordenada. */
function describirCampo(campo: CampoDelArtboard, bloque: number, indice: number): string {
  const tercero =
    campo.length === 2
      ? ''
      : Array.isArray(campo[2])
        ? ` opciones «${(campo[2] as readonly string[]).join(' | ')}»`
        : ` tercero «${String(campo[2])}»`;
  return `bloque ${bloque} · campo ${indice}: «${campo[0]}» tipo «${campo[1]}»${tercero}`;
}

/** Una tabla, en tantas lineas como columnas y filas tenga. */
function describirTabla(tabla: TablaDelArtboard, bloque: number): readonly string[] {
  return [
    `bloque ${bloque} · tabla titulo: «${tabla.t}»`,
    `bloque ${bloque} · tabla conteo: «${tabla.cn ?? '—'}»`,
    `bloque ${bloque} · tabla accion: «${tabla.a ?? '—'}»`,
    `bloque ${bloque} · tabla insignia en columna: ${tabla.i ?? '—'}`,
    `bloque ${bloque} · tabla nota: «${tabla.n ?? '—'}»`,
    ...tabla.c.map(
      (columna, i) =>
        `bloque ${bloque} · tabla columna ${i}: «${columna[0]}» ${columna[1] === 1 ? 'derecha' : 'izquierda'}`,
    ),
    ...tabla.f.map((fila, i) => `bloque ${bloque} · tabla fila ${i}: ${fila.join(' | ')}`),
  ];
}

/** Una pantalla entera, linea a linea. Es lo que se compara cuando algo no cuadra. */
function describirPantalla(bloques: readonly BloqueDelArtboard[]): readonly string[] {
  return bloques.flatMap((bloque, i) => [
    `bloque ${i} · titulo: «${bloque[0]}»`,
    `bloque ${i} · nota: «${bloque[1]}»`,
    ...bloque[2].map((campo, j) => describirCampo(campo, i, j)),
    ...(bloque.length === 4 ? describirTabla(bloque[3], i) : [`bloque ${i} · sin tabla`]),
  ]);
}

/* ── El centinela ──────────────────────────────────────────────────────────────────────── */

describe('el artboard V8 sigue diciendo lo que esta guarda cree que dice', () => {
  it('EL CENTINELA: la extraccion trae diez modulos, cuarenta hojas y cuarenta pantallas', () => {
    // AC5. Sin esto, un cambio de formato en el artboard dejaria los tres literales vacios y las
    // cuarenta comparaciones de abajo pasarian en VERDE comparando nada con nada — que es como
    // una guarda se queda sin sujeto sin que nadie la borre. Es la forma que #78 y #80 ya
    // pagaron dos veces.
    const { arbol, pantallas, instrucciones } = artboardV8();

    expect(arbol.length, 'el artboard no declaro ni un modulo').toBe(10);
    expect(hojasDelArtboard().length, 'el artboard no declaro ni una hoja').toBe(40);
    expect(Object.keys(pantallas).length, 'el artboard no declaro ni una pantalla').toBe(40);
    expect(Object.keys(instrucciones).length, 'el artboard no declaro ni una instruccion').toBe(40);
  });

  it('EL CENTINELA: y las pantallas traen contenido, no cascarones', () => {
    // Cuarenta claves con una lista vacia detras tambien pasarian el centinela de arriba. Estas
    // son las cifras del artboard de hoy, contadas sobre el: 45 bloques, 302 campos y 31 tablas.
    const bloques = Object.values(artboardV8().pantallas);
    const planos = bloques.flat();

    expect(planos.length, 'el artboard no declaro ni un bloque').toBe(45);
    expect(planos.reduce((total, bloque) => total + bloque[2].length, 0)).toBe(302);
    expect(planos.filter((bloque) => bloque.length === 4).length).toBe(31);
  });

  it('EL CENTINELA: y las claves del artboard son las mismas que las del codigo', () => {
    // La premisa de las cuarenta comparaciones de abajo: cada una busca su pantalla por la clave
    // del arbol NUESTRO. Si el artboard cambiara una clave, cada `it` lo diria por su lado, pero
    // esta linea lo dice una vez y en orden.
    expect(Object.keys(artboardV8().pantallas)).toEqual([...CLAVES_DE_HOJA]);
    expect(Object.keys(artboardV8().instrucciones)).toEqual([...CLAVES_DE_HOJA]);
  });
});

/* ── El arbol ──────────────────────────────────────────────────────────────────────────── */

describe('AC3 — el arbol es el del artboard, modulo a modulo', () => {
  it('son diez modulos de cuatro hojas, y cuarenta claves distintas', () => {
    expect(ARBOL.map((modulo) => modulo.hojas.length)).toEqual(Array(10).fill(4));
    expect(CLAVES_DE_HOJA).toHaveLength(40);
    expect(new Set(CLAVES_DE_HOJA).size, 'dos hojas con la misma clave').toBe(40);
  });

  it.each(ARBOL.map((modulo, i) => [modulo.rotulo, i] as const))(
    '«%s»: rotulo, nota, slug, codigo, trazos, hojas, operaciones y piezas',
    (_rotulo, i) => {
      const delArtboard = artboardV8().arbol[i];

      expect(
        delArtboard,
        `El artboard ya no trae un modulo en la posicion ${i}. El orden del arbol es el que se\n` +
          'dibuja: no se reordena aqui, se reordena en el artboard.',
      ).toBeDefined();
      expect(
        moduloComoElArtboard(ARBOL[i] as Modulo),
        'Este modulo dejo de decir lo que el artboard dice de el. El artboard manda: si el\n' +
          'cambio es deliberado, entra primero ahi y de ahi se transcribe.',
      ).toStrictEqual(delArtboard);
    },
  );
});

/* ── Las cuarenta pantallas ────────────────────────────────────────────────────────────── */

describe('AC4 — cada pantalla cuadra con el artboard, campo por campo', () => {
  it.each(CLAVES_DE_HOJA.map((clave) => [clave] as const))('«%s»', (clave) => {
    const delArtboard = artboardV8().pantallas[clave];

    expect(
      delArtboard,
      `El artboard ya no declara la pantalla «${clave}». Es una hoja del arbol sin pantalla que\n` +
        'dibujar: o vuelve al artboard, o sale del arbol.',
    ).toBeDefined();

    const nuestra = pantallaComoElArtboard(PANTALLAS[clave]);

    // Primero las lineas, que es lo que da un rojo que se lee: dice la coordenada exacta
    // —bloque, campo— y las dos versiones del texto.
    expect(
      describirPantalla(nuestra),
      `«${clave}» dejo de decir lo que el artboard dice. El artboard manda.`,
    ).toEqual(describirPantalla(delArtboard as readonly BloqueDelArtboard[]));

    // Y luego la comparacion exhaustiva, que no depende de que el descriptor sepa mirar. Es
    // `toStrictEqual` y no `toEqual` a proposito: una clave opcional puesta a `undefined` no es
    // lo mismo que una clave que no esta.
    expect(nuestra).toStrictEqual(delArtboard);
  });

  it.each(CLAVES_DE_HOJA.map((clave) => [clave] as const))(
    'la instruccion de «%s» es la del artboard, literal',
    (clave) => {
      // AC9: las cadenas se trasladan literales. Esta es una frase escrita para ensenar el
      // procedimiento —«la deuda está calculada a hoy: cambia cada día, no se guarda»—, no
      // relleno, y se compara caracter a caracter.
      expect(PANTALLAS[clave].instruccion).toBe(artboardV8().instrucciones[clave]);
    },
  );
});
