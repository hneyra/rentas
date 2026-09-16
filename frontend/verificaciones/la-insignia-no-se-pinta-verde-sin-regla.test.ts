// @vitest-environment node
//
// Lee el artboard, el enumerado del backend y el contrato. No es un DOM lo que necesita.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { CONECTORES } from '../src/datos/conectores.ts';
import type { ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { PANTALLAS, pantallaDe } from '../src/pantallas/definiciones/index.ts';
import { TONO_SIN_RECONOCER, reconocido, tonoDe } from '../src/pantallas/tono.ts';
import { artboardV8 } from './artboard-v8.ts';
import { RAIZ } from './artboards.ts';

/**
 * **Ninguna columna de insignia se pinta de verde sin que una regla la reconozca** (#175, AC2).
 *
 * <h2>El defecto que la trae</h2>
 *
 * `tonoDe()` devolvia `ok` —verde, «conforme»— cuando ninguna de sus dos listas reconocia el texto
 * de la celda. Medido en `ini-parado`: su quinta columna es la de la insignia y lo unico que `GET
 * /indicadores/trabajo-parado` publica para ella es `porQueCuestaDinero`, que es una **frase**
 * —«sin emitir no se pueden notificar ni cobrar, y prescriben»—. Resultado: **la interfaz pintaba
 * en verde, con la insignia de «conforme», trabajo que esta parado y cuesta dinero.**
 *
 * Y no era una pantalla: era **toda** columna de insignia cuyo texto no estuviera en la lista.
 *
 * <h2>Por que esta guarda, y no solo la prueba del reparto</h2>
 *
 * Porque `src/pantallas/tono.test.ts` prueba el reparto **sobre los textos que ella misma
 * escribe**, y el defecto no estaba en un texto escrito: estaba en los que **llegan** —del
 * artboard, que es lo que las 40 pantallas dibujan, y del backend, que es lo que los once
 * conectores reparten—. Una prueba que elige sus propios ejemplos nunca habria visto las cuatro
 * frases de `ini-parado`, porque a nadie se le ocurre escribirlas como ejemplo de un estado.
 *
 * Asi que esta barre **las 40 definiciones** y mide contra dos fuentes que no son de aqui:
 *
 * <table>
 *   <tr><td>el artboard</td><td>las celdas que `RentasV8.dc.html` escribe en cada columna de
 *     insignia: **17 cadenas** en **22 columnas**. Es el vocabulario del diseno</td></tr>
 *   <tr><td>el backend de este repositorio</td><td>las cuatro frases del enumerado
 *     `FrenteDeTrabajo`, leidas del Java. Es lo que la operacion publica de verdad</td></tr>
 * </table>
 *
 * <h2>Y la totalidad: una hoja que se conecta no se salta esto en silencio</h2>
 *
 * La ola de conexiones crece de una en una, y una comprobacion que recorre un registro y se calla
 * sobre lo que no reconoce deja de ser una comprobacion en la siguiente mezcla —ya paso en
 * `conectores.test.ts`, con la guarda de «cada conector tiene su muestra»—. Aqui la totalidad esta
 * escrita: **toda columna de insignia de una hoja CONECTADA tiene que estar declarada** en
 * {@link CONECTADAS}, diciendo que campo de la respuesta cae en ella. Conectar una hoja con tabla
 * de insignia sin declararlo sale rojo.
 */

/* ── Lo que el artboard escribe en una columna de insignia ─────────────────────────────── */

/** Las 40 hojas, con el tipo que `pantallaDe` pide. */
const hojas = (): readonly ClaveDeHoja[] => Object.keys(PANTALLAS) as readonly ClaveDeHoja[];

/** Una celda de insignia: de que hoja, de que columna y que dice. */
interface CeldaDeInsignia {
  readonly hoja: ClaveDeHoja;
  readonly bloque: number;
  readonly columna: number;
  readonly rotulo: string;
  readonly texto: string;
}

/** Las columnas de insignia de las 40 definiciones, con las celdas que el artboard les escribe. */
function celdasDeInsignia(): readonly CeldaDeInsignia[] {
  const artboard = artboardV8();
  return hojas().flatMap((hoja) =>
    pantallaDe(hoja).bloques.flatMap((bloque, b) => {
      const tabla = bloque.tabla;
      if (tabla?.columnaDeInsignia === undefined) return [];
      const columna = tabla.columnaDeInsignia;
      const tablaDelArtboard = artboard.pantallas[hoja]?.[b]?.[3];
      const filas = tablaDelArtboard === undefined ? [] : tablaDelArtboard.f;
      return filas.flatMap((fila) => {
        const texto = fila[columna];
        return texto === undefined
          ? []
          : [
              {
                hoja,
                bloque: b,
                columna,
                rotulo: tabla.columnas[columna]?.rotulo ?? String(columna),
                texto,
              },
            ];
      });
    }),
  );
}

/** Las columnas de insignia declaradas por las 40 definiciones, hoja a hoja. */
function columnasDeInsignia(): readonly { readonly hoja: ClaveDeHoja; readonly bloque: number }[] {
  return hojas().flatMap((hoja) =>
    pantallaDe(hoja).bloques.flatMap((bloque, b) =>
      bloque.tabla?.columnaDeInsignia === undefined ? [] : [{ hoja, bloque: b }],
    ),
  );
}

/* ── Lo que el backend publica en la de `ini-parado` ───────────────────────────────────── */

const FRENTE_DE_TRABAJO = join(
  RAIZ,
  '../backend/kamayuk-rentas-indicadores/src/main/java/kamayuk/rentas/indicadores/dominio/FrenteDeTrabajo.java',
);

/**
 * Las frases que `GET /indicadores/trabajo-parado` pone en la columna de insignia de `ini-parado`,
 * **leidas del enumerado del backend de este repositorio**.
 *
 * No se copian aqui a mano: copiadas, esta guarda seguiria en verde el dia que el backend cambiara
 * una frase, que es justo el dia en que hay que volver a mirar de que color sale.
 *
 * El indice del argumento sale de la **firma del constructor** y no de contar posiciones: si
 * alguien reordena los parametros, esto lee otro campo y el centinela lo dice.
 */
function frasesDelBackend(): readonly string[] {
  if (!existsSync(FRENTE_DE_TRABAJO)) {
    throw new Error(
      `FALTA EL ENUMERADO DEL BACKEND: ${FRENTE_DE_TRABAJO}\n\n` +
        '  Esta guarda lee de ahi lo que `GET /indicadores/trabajo-parado` publica en la columna\n' +
        '  de insignia de `ini-parado`. Si el archivo se movio, muevela con el; si el enumerado\n' +
        '  desaparecio, esta guarda ya no tiene sujeto y hay que decir por que.',
    );
  }
  const java = readFileSync(FRENTE_DE_TRABAJO, 'utf8');

  const firma = /FrenteDeTrabajo\(([^)]*)\)\s*\{/.exec(java);
  if (firma?.[1] === undefined) throw new Error('no se encontro el constructor de `FrenteDeTrabajo`');
  const parametros = firma[1].split(',').map((p) => p.trim().split(/\s+/).at(-1));
  const indice = parametros.indexOf('porQueCuestaDinero');
  if (indice === -1) {
    throw new Error(
      `«porQueCuestaDinero» ya no es un parametro de \`FrenteDeTrabajo\`: ${parametros.join(', ')}`,
    );
  }

  const constantes = [
    ...java.matchAll(/\b[A-Z][A-Z_]*\(\s*("(?:[^"\\]|\\.)*"(?:\s*,\s*"(?:[^"\\]|\\.)*")*)\s*\)/g),
  ];
  return constantes.flatMap((constante) => {
    const argumentos = [...(constante[1] ?? '').matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]);
    const frase = argumentos[indice];
    return frase === undefined ? [] : [frase];
  });
}

/* ── Las columnas de insignia que ya reciben del backend ───────────────────────────────── */

/**
 * **Toda columna de insignia de una hoja conectada, con el campo que cae en ella.**
 *
 * Se escribe a mano a proposito —conectar una hoja es una decision y se revisa leyendo su diff—,
 * pero **no se puede olvidar**: la prueba de totalidad compara esta lista contra las 40
 * definiciones cruzadas con el registro `CONECTORES`.
 *
 * Lo que hay que mirar al anadir una linea es la columna «que le llega»: si es un **estado** —un
 * enumerado cerrado que el backend publica—, tiene que estar en alguna de las tres listas de
 * `tono.ts`, o saldra con el tono de «no se» y la insignia no dira nada. Si es **otra cosa** —una
 * frase, una medida, un riesgo—, el tono de «no se» es lo correcto **y sobra la insignia**: eso es
 * un issue, no una regla nueva.
 */
const CONECTADAS: readonly {
  readonly hoja: ClaveDeHoja;
  readonly bloque: number;
  readonly leLlega: string;
}[] = [
  { hoja: 'panel', bloque: 0, leLlega: 'etapas[].estado — un estado de verdad: «Conforme», «Observado»' },
  {
    hoja: 'ini-parado',
    bloque: 0,
    leLlega: 'frentes[].porQueCuestaDinero — UNA FRASE, no un estado. Es el defecto de #175, y el estado lo tiene que publicar el backend (#183)',
  },
  { hoja: 'con-doc', bloque: 0, leLlega: 'obligaciones[].fase — `Fase`: ORDINARIA, VALOR, COACTIVA, CONVENIO. Solo COACTIVA es un juicio' },
  { hoja: 'coa-exp', bloque: 0, leLlega: 'actuaciones[].medida — la medida cautelar del acto, o «—» cuando no la lleva. Tampoco es un estado' },
  { hoja: 'aut-cat', bloque: 0, leLlega: 'ciiu[].riesgoItse — «Bajo», «Medio», «Alto». Solo «Bajo» es conforme; los otros dos no son un juicio de la administracion' },
  { hoja: 'aut-tram', bloque: 0, leLlega: 'licencias[].estado — el padron publica «VIGENTE», que si es un estado' },
  // Entro con #181, DESPUES de que se escribiera esta guarda, y por eso este renglon es la prueba
  // de que la guarda hace lo que dice: la hoja se conecto entre medias y salio roja sola.
  {
    hoja: 'seg-aud',
    bloque: 0,
    leLlega:
      'la raya del artboard — «Riesgo» NO LA PUBLICA NADIE, asi que la celda no trae estado sino ' +
      'la ausencia de uno. Con el verde por omision esa raya se pintaba de CONFORME, que es el ' +
      'defecto de #175 en su forma mas pura: una insignia verde sobre una celda vacia',
  },
  // Las dos de Fiscalizacion entran con #179, DESPUES de escribirse esta guarda — la tercera vez
  // que pasa, y la tercera que sale roja sola en vez de heredar el defecto en silencio.
  {
    hoja: 'fis-prog',
    bloque: 0,
    leLlega:
      'derivado de `contenido[].visitado`: «Inspeccionado» o «Programado». Es un booleano de la ' +
      'muestra, no un estado que la administracion conceda — «Programado» no dice que algo este ' +
      'bien, dice que todavia no se ha ido a mirar',
  },
  {
    hoja: 'fis-actas',
    bloque: 0,
    leLlega:
      '`hallazgo` — CONFORME, OMISO, SUBVALUADOR, USO_DISTINTO, NO_UBICADO, o la raya cuando el ' +
      'acta no lo trae. Solo CONFORME es un juicio favorable; los otros cuatro son lo que el ' +
      'fiscalizador anoto en campo, y la raya no es ninguna de las dos cosas',
  },
  // Las dos de Transito entran con #180, y son el PRIMER caso en que la celda de la insignia no
  // lleva texto sino la ausencia declarada de uno: `tra-pap` usa la celda sin dato de
  // `kamayuk-lib`#87, que ademas anuncia el motivo dentro.
  {
    hoja: 'tra-pap',
    bloque: 0,
    leLlega:
      'NADA — «Estado» no la publica nadie: el expediente trae los `acuses[]` de cada acto, uno ' +
      'por intento, y ningun estado. Va celda sin dato con su motivo dentro, no una raya muda, y ' +
      'por eso no hay tono que calificar',
  },
  {
    hoja: 'tra-veh',
    bloque: 0,
    leLlega:
      '`contenido[].estado` del internamiento — EN_DEPOSITO, ENTREGADO, REMATADO. Ninguno es un ' +
      'juicio de la administracion sobre el vehiculo: dicen donde esta, no si esta conforme',
  },
];

/* ── Y el contrato, que es lo que dira cuando el backend publique el estado ─────────────── */

const FORMAS = join(RAIZ, '../docs/50-api/formas-de-la-api.json');

describe('ninguna insignia se pinta de verde sin que una regla la reconozca', () => {
  it('EL CENTINELA: hay 22 columnas de insignia, el artboard les escribe 17 textos y el backend 4 frases', () => {
    // Sin esto, un artboard que dejara de traer celdas —o una ruta mal calculada— dejaria todo lo
    // de abajo recorriendo la lista vacia y pasando en verde sobre la nada. Es como este
    // repositorio se quedo sin guarda dos veces (#78, #80).
    expect(columnasDeInsignia()).toHaveLength(22);
    const textos = new Set(celdasDeInsignia().map((c) => c.texto));
    expect(textos.size, 'el artboard no escribe ni un texto en una columna de insignia').toBe(17);
    expect(frasesDelBackend(), 'el enumerado del backend no trae sus cuatro frentes').toHaveLength(4);
    expect(Object.keys(CONECTORES).length, 'no hay ni un conector que barrer').toBeGreaterThan(10);
  });

  it('EL VERDE SE GANA: ninguna celda del artboard llega a `ok` sin una regla que la nombre', () => {
    const regaladas = celdasDeInsignia().filter(
      (celda) => tonoDe(celda.texto) === 'ok' && !reconocido(celda.texto),
    );
    expect(
      regaladas.map((c) => `  ${c.hoja} · bloque ${String(c.bloque)} · «${c.rotulo}» · «${c.texto}»`),
      'Hay celdas de insignia que salen VERDES sin que ninguna regla de `tono.ts` las reconozca.\n' +
        '  El verde dice «conforme», y decirlo sobre lo que no se reconoce es la peor de las tres\n' +
        '  opciones: un estado desconocido se lee como «todo bien». Lo desconocido lleva el tono\n' +
        '  de «no se» — ver `src/pantallas/tono.ts`.',
    ).toEqual([]);
  });

  it('y lo que ninguna regla reconoce sale con el tono de «no se», el mismo para todas', () => {
    const desconocidas = celdasDeInsignia().filter((celda) => !reconocido(celda.texto));
    // Seis de las diecisiete: «Alto», «Con diferencia», «En deposito», «Medio», «Pendiente» y
    // «Programado». Las seis salian VERDES hasta #175.
    expect(new Set(desconocidas.map((c) => c.texto)).size).toBe(6);
    for (const celda of desconocidas) {
      expect(tonoDe(celda.texto), `${celda.hoja} · «${celda.texto}»`).toBe(TONO_SIN_RECONOCER);
    }
  });

  it('EL REPARTO SIGUE DISCRIMINANDO: los 17 textos caen en los cuatro tonos, y no todos en uno', () => {
    // Sin esta, un `tonoDe` que devolviera SIEMPRE el tono de «no se» pasaria las dos de arriba
    // —ninguna seria verde— y dejaria la pantalla tan muda como la dejaba el verde de antes.
    const cuenta = (tono: string) =>
      new Set(
        celdasDeInsignia()
          .filter((c) => tonoDe(c.texto) === tono)
          .map((c) => c.texto),
      ).size;
    expect({ ok: cuenta('ok'), mal: cuenta('mal'), atencion: cuenta('atencion'), info: cuenta('info') }).toEqual(
      { ok: 6, mal: 3, atencion: 2, info: 6 },
    );
  });

  it('LAS CUATRO FRASES de `GET /indicadores/trabajo-parado` no son un estado, y ninguna es verde', () => {
    for (const frase of frasesDelBackend()) {
      expect(reconocido(frase), `«${frase}» la reconoce una regla, y es una FRASE`).toBe(false);
      expect(tonoDe(frase), `«${frase}»`).not.toBe('ok');
      expect(tonoDe(frase), `«${frase}»`).toBe(TONO_SIN_RECONOCER);
    }
  });

  it('TOTALIDAD: toda columna de insignia de una hoja CONECTADA esta declarada', () => {
    const conectadas = columnasDeInsignia().filter((c) =>
      Object.prototype.hasOwnProperty.call(CONECTORES, c.hoja),
    );
    const declaradas = new Set(CONECTADAS.map((c) => `${c.hoja}·${String(c.bloque)}`));
    const sinDeclarar = conectadas
      .filter((c) => !declaradas.has(`${c.hoja}·${String(c.bloque)}`))
      .map((c) => `  ${c.hoja} · bloque ${String(c.bloque)}`);
    expect(
      sinDeclarar,
      'Hay hojas CONECTADAS con columna de insignia que no declaran que campo cae en ella.\n' +
        '  Anadelas a `CONECTADAS` diciendo que le llega: es la unica forma de que la proxima\n' +
        '  hoja que se conecte no herede el defecto de #175 en silencio.',
    ).toEqual([]);
    // Y al reves: una declaracion que ya no corresponde a ninguna columna conectada.
    const vivas = new Set(conectadas.map((c) => `${c.hoja}·${String(c.bloque)}`));
    expect(
      CONECTADAS.filter((c) => !vivas.has(`${c.hoja}·${String(c.bloque)}`)).map((c) => c.hoja),
      'sobra una declaracion en `CONECTADAS`',
    ).toEqual([]);
  });

  it('EL CONTRATO lo confirma: `trabajo-parado` no publica ningun estado — y cuando lo publique, esto sale rojo', () => {
    const formas = JSON.parse(readFileSync(FORMAS, 'utf8')) as Record<string, unknown>;
    const forma = formas['GET /indicadores/trabajo-parado'] as
      | { readonly frentes?: readonly Record<string, unknown>[] }
      | undefined;
    const frente = forma?.frentes?.[0];
    expect(frente, 'el contrato ya no declara `frentes` en `trabajo-parado`').toBeDefined();
    const campos = Object.keys(frente ?? {});
    expect(campos, 'el contrato dejo de publicar la frase que hoy cae en la insignia').toContain(
      'porQueCuestaDinero',
    );
    expect(
      campos.filter((c) => /estado|situacion|situación/i.test(c)),
      'EL BACKEND YA PUBLICA EL ESTADO DEL FRENTE (#183), y esta guarda esta roja a proposito:\n' +
        '  · dale a la columna de `ini-parado` su regla de insignia sobre ESE campo,\n' +
        '  · deja de mandarle `porQueCuestaDinero`, que es una frase y nunca fue un estado,\n' +
        '  · y borra esta comprobacion, que ya no tiene nada que vigilar.',
    ).toEqual([]);
  });
});
