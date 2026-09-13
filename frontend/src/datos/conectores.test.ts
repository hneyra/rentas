import { describe, expect, it } from 'vitest';

import { PANTALLAS } from '../pantallas/definiciones/index.ts';
import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import { coordenada } from '@kamayuk/ui';
import { CONECTORES, NO_PUBLICADO } from './conectores.ts';
import type { CorridaDelPredial, DeudaEnCoactiva, Paginado } from './lecturas.ts';

/**
 * **Lo que cada pantalla conectada saca de su respuesta** (#97).
 *
 * Lo que se comprueba no es que el mapeo «funcione»: es que **ningun campo se quede sin decidir**.
 * Un campo de solo lectura de una pantalla conectada tiene que estar en uno de los dos sitios —con
 * dato, o declarado «no publicado»—, y **nunca en ninguno**: un campo olvidado se dibuja con el
 * motivo de la PANTALLA, que en una pantalla conectada dice que si esta conectada. Seria un hueco
 * mintiendo sobre su propia causa.
 */

/** La respuesta que la instalacion da de verdad, recortada a lo que el conector usa. */
const CORRIDA: CorridaDelPredial = {
  id: 1,
  ejercicio: '2026',
  alcance: 'PADRON',
  sector: null,
  simulacion: false,
  conjunto: 'V3',
  fechaCalculo: '28/01/2026 02:14',
  observados: 534,
  etapas: [
    { etapa: 'Lectura del padron', registros: 62418, monto: '—', observados: 0, estado: 'Conforme' },
    { etapa: 'Generacion de cuponeras', registros: 61350, monto: '—', observados: 534, estado: 'Observado' },
  ],
};

const PAGINA: Paginado<DeudaEnCoactiva> = {
  contenido: [],
  pagina: 0,
  tamano: 20,
  totalElementos: 388,
  totalPaginas: 20,
  hayMas: true,
};

/** Los campos de solo lectura de una pantalla, por su coordenada. */
function soloLecturaDe(clave: ClaveDeHoja): readonly string[] {
  return PANTALLAS[clave].bloques.flatMap((bloque, b) =>
    bloque.campos.flatMap((campo, c) => (campo.tipo.startsWith('r') ? [coordenada(b, c)] : [])),
  );
}

describe('los conectores', () => {
  it('EL CENTINELA: hay dos, y no cero ni cuarenta', () => {
    // Cero dejaria todo lo de abajo sin sujeto. Cuarenta significaria que alguien conecto
    // pantallas cuyas operaciones no publican lo que ensenan, que es lo que este archivo evita.
    expect(Object.keys(CONECTORES).sort()).toEqual(['coa-panel', 'panel']);
  });

  it('NINGUN campo de una pantalla conectada se queda sin decidir', () => {
    const olvidados: string[] = [];
    for (const [clave, conector] of Object.entries(CONECTORES)) {
      if (conector === undefined) continue;
      const respuesta = clave === 'panel' ? CORRIDA : PAGINA;
      const reparto = conector.repartir(respuesta as never);
      for (const coord of soloLecturaDe(clave as ClaveDeHoja)) {
        const decidido =
          reparto.valores.has(coord as never) || reparto.noPublicados.has(coord as never);
        if (!decidido) olvidados.push(`  ${clave} · ${coord}`);
      }
    }
    expect(
      olvidados,
      'Hay campos de una pantalla CONECTADA que no salen de ningun sitio ni se declaran «no\n' +
        'publicado». Se dibujarian con el motivo de la pantalla, que en una conectada dice que SI\n' +
        `esta conectada — un hueco mintiendo sobre su propia causa:\n${olvidados.join('\n')}`,
    ).toEqual([]);
  });
});

describe('`panel` — la ultima corrida', () => {
  const conector = CONECTORES.panel;
  if (conector === undefined) throw new Error('falta el conector de `panel`');
  const reparto = conector.repartir(CORRIDA as never);

  it('la fecha y los observados salen de la respuesta', () => {
    expect(reparto.valores.get(coordenada(0, 1))).toBe('28/01/2026 02:14');
    expect(reparto.valores.get(coordenada(0, 3))).toBe('534');
  });

  it('la tabla sale de `etapas`, con sus cinco columnas en orden', () => {
    const filas = reparto.filas.get(0);
    expect(filas).toHaveLength(2);
    expect(filas?.[1]).toEqual(['Generacion de cuponeras', '61350', '—', '534', 'Observado']);
    // Cinco celdas por fila, que son las cinco columnas que la definicion declara.
    expect(PANTALLAS.panel.bloques[0]?.tabla?.columnas).toHaveLength(5);
    expect(filas?.[0]).toHaveLength(5);
  });

  it('NO deduce «cuentas emitidas» de la ultima etapa, aunque el numero coincida', () => {
    // La ultima etapa trae 61 350 registros y el artboard ensena 61 350 cuentas emitidas. **Que
    // coincidan no las hace lo mismo**: una es «cuantas cuponeras se generaron» y la otra «cuantas
    // cuentas quedaron emitidas», y el dia que difieran nadie sabria que el numero era deducido.
    expect(reparto.valores.has(coordenada(0, 2))).toBe(false);
    expect(reparto.noPublicados.get(coordenada(0, 2))).toBe(NO_PUBLICADO);
    // Y por si alguien lo dedujera igualmente: el valor de la etapa no puede aparecer como valor.
    expect([...reparto.valores.values()]).not.toContain('61350');
  });
});

describe('`coa-panel` — los expedientes coactivos', () => {
  const conector = CONECTORES['coa-panel'];
  if (conector === undefined) throw new Error('falta el conector de `coa-panel`');
  const reparto = conector.repartir(PAGINA as never);

  it('«expedientes abiertos» sale del TOTAL, no del tamano de la pagina', () => {
    // `totalElementos` y no `contenido.length`: la pagina trae veinte de 388, y contar lo que
    // llego daria «20 expedientes abiertos» — un numero exacto y falso.
    expect(reparto.valores.get(coordenada(0, 1))).toBe('388');
    expect(reparto.valores.get(coordenada(0, 1))).not.toBe('20');
  });

  it('y los otros cuatro se declaran «no publicado», en vez de contarse sobre la pagina', () => {
    // «Con REC notificada», «con medida cautelar» y «sin REC» son agregados que la operacion no
    // publica; contarlos sobre una pagina de veinte daria ceros indistinguibles de ceros reales.
    // Y «deuda en cartera» sumada sobre esa pagina seria sencillamente falsa.
    for (const campo of [2, 3, 4, 5]) {
      expect(reparto.noPublicados.get(coordenada(0, campo)), `campo ${campo}`).toBe(NO_PUBLICADO);
    }
    expect(reparto.valores.size).toBe(1);
  });
});
