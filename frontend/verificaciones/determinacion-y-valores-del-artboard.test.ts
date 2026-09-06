import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

import type { DeterminacionIndividual } from '../src/datos/lecturas.ts';
import {
  TIPOS,
  conteo,
  filasDeLaAlcabala,
  filasDelVehicular,
} from '../src/secciones/determinacion.ts';
import { TABLAS_DE_VALORES } from '../src/secciones/valores.ts';
import { ARTBOARD, RAIZ, comoDato, leer, literalTras, sinComentarios } from './tokens.ts';

/**
 * Las dos secciones de F-6 dicen lo que dice el artboard, y **el artboard se lee**.
 *
 * Misma disciplina que `arbol-del-artboard`, `tokens-del-artboard` y `secciones-del-artboard`:
 * lo que se compara sale de `frontend/diseno/RentasV6.dc.html`, no de una copia de sus datos
 * escrita aqui. Con la lista dentro de la prueba, cambiar el port y cambiar la lista serian el
 * mismo commit y nadie se enteraria.
 *
 * <h2>Y antes de comparar, se comprueban las PREMISAS</h2>
 *
 * El primer grupo no prueba el port: prueba que el artboard sigue teniendo la forma que el resto
 * del archivo da por hecha. Sin el, un artboard que cambiara de forma daria listas vacias y
 * todas las comparaciones de abajo pasarian comparando nada con nada.
 *
 * <h2>Lo que ademas se mide contra el CONTRATO</h2>
 *
 * Tres afirmaciones de este trabajo no son sobre el artboard sino sobre lo que el backend
 * publica, y se comprueban leyendo `docs/50-api/formas-de-la-api.json`:
 *
 *   1. Dos de las seis determinaciones **no publican `fechaCalculo`**, que es lo que impide
 *      dibujar sus importes (regla 9);
 *   2. **ninguna operacion publica la tabla de valores del ejercicio** — lo unico que dice algo
 *      de ella son las senas del conjunto sellado—, que es lo que obliga a componer la escala de
 *      las determinaciones; y
 *   3. `GET /rentas/arbitrios` **no publica ni la zona ni el criterio**, que es lo que deja
 *      vacias cuatro columnas del cuadro de arbitrios.
 */

const html = leer(ARTBOARD);
const FORMAS = join(RAIZ, '../docs/50-api/formas-de-la-api.json');
const formas = JSON.parse(readFileSync(FORMAS, 'utf8')) as Record<string, unknown>;

interface DeterminacionDelArtboard {
  titulo: string;
  nota: string;
  cols: [string, number][];
  filas: string[][];
}

interface TablaDeValoresDelArtboard {
  label: string;
  nota: string;
  cols: [string, number][];
  filas: string[][];
  pie: string;
}

const nodosDelArtboard = comoDato(literalTras(html, 'const NODOS = ')) as [string, string][];
const determinacionesDelArtboard = comoDato(
  literalTras(html, 'const DETERMINACIONES = '),
) as DeterminacionDelArtboard[];
const valDelArtboard = comoDato(literalTras(html, 'const VAL = ')) as TablaDeValoresDelArtboard[];
const secsDelArtboard = comoDato(literalTras(html, 'const SECS = ')) as [
  string,
  string,
  string,
  string,
][];

/** Los campos hoja de una forma declarada, por su camino. */
function hojasDe(forma: unknown, camino = ''): readonly string[] {
  if (Array.isArray(forma)) {
    return forma.flatMap((elemento) => hojasDe(elemento, `${camino}[]`));
  }
  if (forma !== null && typeof forma === 'object') {
    return Object.entries(forma as Record<string, unknown>).flatMap(([campo, valor]) =>
      hojasDe(valor, `${camino}.${campo}`),
    );
  }
  return [camino];
}

describe('el artboard sigue diciendo lo que esta prueba cree que dice', () => {
  it('declara SEIS nodos, SEIS determinaciones y TRES tablas de valores', () => {
    expect(nodosDelArtboard).toHaveLength(6);
    expect(determinacionesDelArtboard).toHaveLength(6);
    expect(valDelArtboard).toHaveLength(3);
  });

  it('`SECS` rotula «territorio» como «Determinación», con el slug `determinacion`', () => {
    // Es la premisa del issue: la clave interna no es el rotulo, y las dos son deliberadas.
    expect(secsDelArtboard[2]).toEqual(['territorio', 'Determinación', '6', 'determinacion']);
    expect(secsDelArtboard[3]).toEqual(['valores', 'Valores', '', 'valores']);
  });

  it('la memoria del predial trae NUEVE filas, tres de ellas de tramo', () => {
    const memoria = determinacionesDelArtboard[0];
    expect(memoria?.filas).toHaveLength(9);
    expect(memoria?.filas.filter((fila) => fila[0] === '×')).toHaveLength(3);
  });

  it('la corrida masiva trae CINCO etapas, y la ultima queda «Con observados»', () => {
    const masivo = determinacionesDelArtboard[1];
    expect(masivo?.filas).toHaveLength(5);
    expect(masivo?.filas[4]?.[4]).toBe('Con observados');
    expect(masivo?.filas[4]?.[3]).toBe('534');
  });
});

describe('AC1 y AC2 — los seis tipos son los de `DETERMINACIONES`', () => {
  it('los seis titulos, en su orden', () => {
    expect(TIPOS.map((tipo) => tipo.titulo)).toEqual(
      determinacionesDelArtboard.map((una) => una.titulo),
    );
  });

  it('la nota explicativa de cada uno, letra por letra', () => {
    expect(TIPOS.map((tipo) => tipo.nota)).toEqual(determinacionesDelArtboard.map((una) => una.nota));
  });

  it('las columnas de cada uno, con su marca de alineacion numerica', () => {
    expect(
      TIPOS.map((tipo) => tipo.columnas.map(([rotulo, derecha]) => [rotulo, derecha ? 1 : 0])),
    ).toEqual(determinacionesDelArtboard.map((una) => una.cols));
  });
});

describe('AC1 — el conteo del artboard se compone, y con sus cifras da lo mismo', () => {
  it.each(nodosDelArtboard.map((nodo, i) => [nodo[0], nodo[1], i] as const))(
    '«%s» → «%s»',
    (_titulo, texto, i) => {
      // El artboard escribe «62,418 cuentas»; el port compone «<cifra> <unidad>» y la cifra sale
      // de la respuesta. Aqui se comprueba que, con la cifra del artboard, escribe lo mismo — o
      // sea que lo unico que cambia es de donde viene el numero.
      const cuantos = Number(texto.split(' ')[0]?.replace(/,/g, ''));
      const unidad = TIPOS[i]?.unidad ?? '';
      expect(conteo(cuantos, unidad)).toBe(texto);
    },
  );
});

describe('AC6 — las tres tablas de valores son las de `VAL`', () => {
  it('los tres rotulos, en su orden', () => {
    expect(TABLAS_DE_VALORES.map((tabla) => tabla.rotulo)).toEqual(
      valDelArtboard.map((una) => una.label),
    );
  });

  it('la nota y el pie de cada una, letra por letra', () => {
    expect(TABLAS_DE_VALORES.map((tabla) => [tabla.nota, tabla.pie])).toEqual(
      valDelArtboard.map((una) => [una.nota, una.pie]),
    );
  });

  it('las columnas de cada una, con su marca de alineacion', () => {
    expect(
      TABLAS_DE_VALORES.map((tabla) =>
        tabla.columnas.map(([rotulo, derecha]) => [rotulo, derecha ? 1 : 0]),
      ),
    ).toEqual(valDelArtboard.map((una) => una.cols));
  });
});

describe('las dos determinaciones que no publican su fecha, medido en el contrato', () => {
  it('la alcabala y los espectaculos NO traen `fechaCalculo`', () => {
    for (const clave of ['POST /rentas/alcabala', 'POST /rentas/espectaculos']) {
      expect(Object.keys(formas[clave] as Record<string, unknown>), clave).not.toContain(
        'fechaCalculo',
      );
    }
  });

  it('las otras cuatro SI, y por eso la renuncia es de esas dos y no de todas', () => {
    for (const clave of [
      'POST /rentas/predial/calculo-individual',
      'POST /rentas/predial/calculo-masivo',
      'POST /rentas/vehicular/calculo',
    ]) {
      expect(Object.keys(formas[clave] as Record<string, unknown>), clave).toContain('fechaCalculo');
    }
    const arbitrio = (formas['GET /rentas/arbitrios'] as { contenido: Record<string, unknown>[] })
      .contenido[0];
    expect(Object.keys(arbitrio ?? {})).toContain('fechaCalculo');
  });

  it('el artboard SI les dibuja importes, y por eso el hueco se nota', () => {
    // La premisa de la renuncia: si el artboard no tuviera cifras ahi, no habria nada que dejar
    // de dibujar y la decision no significaria nada.
    expect(determinacionesDelArtboard[4]?.filas.map((fila) => fila[3])).toContain('1,245.00');
    expect(determinacionesDelArtboard[5]?.filas[0]?.[6]).toBe('8,400.00');
  });
});

describe('ninguna operacion publica la tabla de valores del ejercicio', () => {
  it('lo unico que el contrato dice del conjunto sellado son sus SENAS', () => {
    // Cuatro campos, y ninguno es un valor: ni la UIT, ni un tramo, ni una alicuota. El dia que
    // publique alguno, esta prueba se pone roja y dice donde llenar la seccion de verdad.
    expect(
      Object.keys(formas['GET /seguridad/parametros/ejercicios/{ejercicio}'] as Record<string, unknown>).sort(),
    ).toEqual(['conjuntoId', 'ejercicio', 'sellado', 'version']);
  });

  it('las cifras de la escala solo existen DENTRO de una determinacion', () => {
    const hojas = hojasDe(formas['POST /rentas/predial/calculo-individual']);
    expect(hojas).toContain('.uit');
    expect(hojas).toContain('.tramos[].alicuota');
    expect(hojas).toContain('.tramos[].limiteSuperior');
    expect(hojas).toContain('.minimoImponible');
    expect(hojas).toContain('.derechoDeEmision');
  });

  it('y ninguna otra de las 181 las publica sueltas', () => {
    // Recorridas todas: las que nombran una UIT o una alicuota son determinaciones y catalogos
    // de infracciones —donde la multa se expresa en porcentaje de UIT—, nunca una tabla de
    // valores del ejercicio. Si apareciera una, esta lista cambia y hay que mirarla.
    const conValores = Object.entries(formas)
      .filter(([clave]) => clave !== '_')
      .filter(([, forma]) =>
        hojasDe(forma).some((hoja) => /\.uit$|limiteSuperior|porcentajeUit/i.test(hoja)),
      )
      .map(([clave]) => clave)
      .sort();

    expect(conValores).toEqual([
      'GET /infracciones/administrativas/codigos/reporte',
      'GET /infracciones/cuis',
      'GET /transito/codigos',
      'POST /rentas/predial/calculo-individual',
    ]);
  });

  it('`GET /rentas/arbitrios` no publica ni la zona ni el criterio ni la frecuencia', () => {
    const arbitrio = (formas['GET /rentas/arbitrios'] as { contenido: Record<string, unknown>[] })
      .contenido[0];
    const campos = Object.keys(arbitrio ?? {});
    expect(campos.sort()).toEqual([
      'contribuyenteId',
      'ejercicio',
      'fechaCalculo',
      'id',
      'monto',
      'periodo',
      'predioId',
      'servicio',
    ]);
  });

  it('el artboard SI dibuja las cuatro zonas y el criterio', () => {
    expect(valDelArtboard[1]?.cols.map((columna) => columna[0])).toEqual([
      'Servicio',
      'Zona 1',
      'Zona 2',
      'Zona 3',
      'Zona 4',
      'Criterio',
    ]);
    expect(valDelArtboard[1]?.filas[0]).toEqual([
      'Barrido de calles',
      '11.20',
      '8.40',
      '6.10',
      '4.20',
      'Metro de frontis',
    ]);
  });
});

describe('regla 5 — el port no escribe un multiplo de la UIT que el artboard si escribe', () => {
  /** Una memoria vehicular minima, para poder leer los rotulos que el port compone. */
  const VEHICULAR = {
    fechaCalculo: '2026-08-12',
    conjunto: 'Conjunto 2026 sellado',
    alicuota: '1.0',
    minimoImponible: '80.25',
    determinaciones: [
      {
        ejercicio: '2026',
        placa: 'T2R-418',
        baseImponible: '112800.00',
        montoDeterminado: '1128.00',
      },
    ],
  };

  it('el artboard rotula «Mínimo imponible — 1.5 % UIT» y «Tramo inafecto — 10 UIT»', () => {
    const conceptos = [
      ...(determinacionesDelArtboard[3]?.filas.map((fila) => fila[1]) ?? []),
      ...(determinacionesDelArtboard[4]?.filas.map((fila) => fila[1]) ?? []),
    ];
    expect(conceptos).toContain('Mínimo imponible — 1.5 % UIT');
    expect(conceptos).toContain('Tramo inafecto — 10 UIT');
  });

  it('el port los rotula sin el multiplo, porque el multiplo es un valor normativo', () => {
    // «1.5 % UIT» y «10 UIT» son cifras tributarias, y la regla 5 prohibe que vivan en el
    // codigo. El port las deja fuera del rotulo en vez de escribirlas: el valor al que se
    // aplican llega en la respuesta, y el multiplo lo publicara `normativa` el dia que se pida.
    const vehicular = filasDelVehicular(VEHICULAR).map((fila) => fila.celdas[1]?.texto);
    expect(vehicular).toContain('Mínimo imponible');
    expect(vehicular).not.toContain('Mínimo imponible — 1.5 % UIT');

    const alcabala = filasDeLaAlcabala({
      id: 30601,
      ejercicio: '2026',
      baseImponible: '41500.00',
      montoDeterminado: '1245.00',
    }).map((fila) => fila.celdas[1]?.texto);
    expect(alcabala).toContain('Tramo inafecto');
    expect(alcabala).not.toContain('Tramo inafecto — 10 UIT');
  });

  it('y ninguno de los dos archivos del port trae una cifra de la escala', () => {
    for (const archivo of ['src/secciones/determinacion.ts', 'src/secciones/valores.ts']) {
      const fuente = sinComentarios(leer(join(RAIZ, archivo))).replace(/\/\/.*$/gm, '');
      for (const cifra of ['5,350.00', '80,250.00', '321,000.00', '267,500.00', '53,500.00']) {
        expect(fuente, `${archivo} · ${cifra}`).not.toContain(cifra);
      }
    }
  });
});

describe('AC2 — la columna numerica es comparable de un vistazo', () => {
  it('el CSS declara `tabular-nums` para la cabecera y la celda de cifra', () => {
    const css = sinComentarios(leer(join(RAIZ, 'src/estilos/secciones.css')));
    const bloque = /\.kr-tabla__th--cifra,\s*\.kr-tabla__td--cifra\s*\{([^}]*)\}/.exec(css);

    expect(bloque, 'el bloque de la celda de cifra ya no existe con ese nombre').not.toBeNull();
    expect(bloque?.[1]).toContain('text-align: right');
    expect(bloque?.[1]).toContain('font-variant-numeric: tabular-nums');
  });

  it('el conteo de la lista de tipos tambien, que es una columna de cifras', () => {
    const css = sinComentarios(leer(join(RAIZ, 'src/estilos/secciones.css')));
    const bloque = /\.kr-determinacion__conteo\s*\{([^}]*)\}/.exec(css);
    expect(bloque?.[1]).toContain('font-variant-numeric: tabular-nums');
  });
});

describe('el port declara los tipos que el resto del archivo da por hechos', () => {
  it('`DeterminacionIndividual` sigue publicando los tres totales de AC4', () => {
    // Una comprobacion de TIPOS y no de valores: si el contrato dejara de publicar uno, el
    // compilador lo diria antes que ninguna prueba. Esto solo lo deja escrito.
    const campos: readonly (keyof DeterminacionIndividual)[] = [
      'impuestoInsoluto',
      'derechoDeEmision',
      'totalAPagar',
      'tramos',
    ];
    const declarados = Object.keys(
      formas['POST /rentas/predial/calculo-individual'] as Record<string, unknown>,
    );
    for (const campo of campos) {
      expect(declarados, campo).toContain(campo);
    }
  });
});
