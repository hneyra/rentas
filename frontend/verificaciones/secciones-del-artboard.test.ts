import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

import {
  ACTIVIDAD,
  BANDEJA,
  CIFRAS,
  COBERTURA,
  COLA_TOTAL,
  FECHA_DE_CORTE_DEL_PANEL,
  NOTA_DE_COBERTURA,
} from '../src/datos/prototipo.ts';
import { TIPOS_DE_DOCUMENTO, longitudDe } from '../src/dominio/documento.ts';
import { SECCIONES_DEL_EXPEDIENTE } from '../src/secciones/expediente.ts';
import { ESTADOS_CON_TONO, tonoDelEstado } from '../src/secciones/tonos.ts';
import { ARTBOARD, RAIZ, comoDato, leer, literalTras as literalDe, textoTras as textoDe } from './tokens.ts';

/**
 * Las dos secciones de F-5 dicen lo que dice el artboard, y **el artboard se lee**.
 *
 * Es la misma disciplina de `tokens-del-artboard` y `arbol-del-artboard`: lo que se compara sale
 * de `frontend/diseno/RentasV6.dc.html`, no de una copia de sus datos escrita aqui. Si la lista
 * viviera en esta prueba, cambiar el port y cambiar la lista serian el mismo commit y nadie se
 * enteraria.
 *
 * <h2>Y antes de comparar, se comprueban las PREMISAS</h2>
 *
 * El primer grupo no prueba el port: prueba que el artboard sigue teniendo la forma que el resto
 * del archivo da por hecha. Sin el, un artboard que cambiara de forma daria listas vacias y todas
 * las comparaciones de abajo pasarian comparando nada con nada — que es la manera silenciosa de
 * que una prueba deje de proteger.
 *
 * <h2>Lo que ademas se mide contra el CONTRATO</h2>
 *
 * Dos afirmaciones de este trabajo no son sobre el artboard sino sobre lo que el backend
 * publica, y se comprueban leyendo `docs/50-api/formas-de-la-api.json`: que el padron no publica
 * el estado de cobranza ni la deuda —que es lo que obliga a componer la fila de tres
 * operaciones— y que el KPI del panel no publica ninguna variacion —que es lo que deja fuera la
 * pastilla «+3.1» del artboard—.
 */

const html = leer(ARTBOARD);
const FORMAS = join(RAIZ, '../docs/50-api/formas-de-la-api.json');
const formas = JSON.parse(readFileSync(FORMAS, 'utf8')) as Record<string, unknown>;

/**
 * Los tres analizadores del artboard viven en `tokens.ts` desde F-6.
 *
 * Estaban aqui, y subieron cuando `determinacion-y-valores-del-artboard.test.ts` necesito leer
 * el mismo archivo: dos copias del mismo analizador pueden divergir, y la que divergiera
 * compararia contra otra cosa sin decirlo. Se envuelven con el `html` ya leido para no repetirlo
 * en las veinte llamadas de abajo.
 */
const literalTras = (marca: string) => literalDe(html, marca);
const textoTras = (marca: string) => textoDe(html, marca);

interface CifraDelArtboard {
  etiqueta: string;
  valor: string;
  delta: string;
  nota: string;
}

interface CampoDelArtboard {
  k: string;
  l: string;
  t?: string;
  o?: string[];
  ancho?: number;
  opcional?: boolean;
  ph?: string;
  ayuda?: string;
}

interface PasoDelArtboard {
  id: string;
  label: string;
  nota: string;
  campos: CampoDelArtboard[];
  tabla?: {
    titulo: string;
    accion: string;
    vacioTexto: string;
    cols: [string, number][];
    nota: string;
  };
}

const cifrasDelArtboard = comoDato(literalTras('cifras: ')) as CifraDelArtboard[];
const bandejaDelArtboard = comoDato(literalTras('bandeja: ')) as [
  string,
  string,
  string,
  string,
  number,
][];
const coberturaDelArtboard = comoDato(literalTras('cobertura: ')) as [string, number, string][];
const actividadDelArtboard = comoDato(literalTras('actividad: ')) as [
  string,
  string,
  string,
  string,
  string,
][];
const pasosDelArtboard = comoDato(literalTras('const PASOS = ')) as PasoDelArtboard[];
const docsDelArtboard = comoDato(literalTras('const DOCS = ')) as Record<string, number>;
const secsDelArtboard = comoDato(literalTras('const SECS = ')) as [
  string,
  string,
  string,
  string,
][];
const prediosDelArtboard = comoDato(literalTras('const PREDIOS = ')) as {
  cod: string;
  titulo: string;
  titular: string;
  uso: string;
  autovaluo: string;
  estado: string;
  tono: string;
  valor: number;
  contexto: string;
}[];

describe('el artboard sigue diciendo lo que esta prueba cree que dice', () => {
  it('el panel declara cuatro cifras, tres frentes, cinco barras y cuatro actos', () => {
    expect(cifrasDelArtboard).toHaveLength(4);
    expect(bandejaDelArtboard).toHaveLength(3);
    expect(coberturaDelArtboard).toHaveLength(5);
    expect(actividadDelArtboard).toHaveLength(4);
  });

  it('el expediente declara SEIS secciones, y `DOCS` tres tipos de documento', () => {
    expect(pasosDelArtboard).toHaveLength(6);
    expect(Object.keys(docsDelArtboard)).toHaveLength(3);
  });

  it('las cuatro secciones del modulo, con «predios» rotulado «Contribuyentes»', () => {
    // Es la premisa del issue: la clave interna es distinta del rotulo, y las dos son
    // deliberadas — la clave va al codigo y al slug, el rotulo a la pantalla.
    expect(secsDelArtboard).toHaveLength(4);
    expect(secsDelArtboard[0]).toEqual(['panel', 'Panel', '', 'panel']);
    expect(secsDelArtboard[1]).toEqual(['predios', 'Contribuyentes', '62,418', 'contribuyentes']);
  });

  it('el padron declara CINCO contribuyentes, con su estado y su tono', () => {
    expect(prediosDelArtboard).toHaveLength(5);
    expect(prediosDelArtboard.map((uno) => uno.estado)).toEqual([
      'Con deuda',
      'Al día',
      'En coactiva',
      'Observado',
      'Al día',
    ]);
  });
});

describe('AC1 — el panel es el del artboard, dato a dato', () => {
  it('las cuatro cifras, con su etiqueta, su valor y su nota', () => {
    expect(
      CIFRAS.map((cifra) => [cifra.etiqueta, cifra.valor, cifra.nota]),
      'La captura del panel dejo de ser la del artboard.',
    ).toEqual(cifrasDelArtboard.map((cifra) => [cifra.etiqueta, cifra.valor, cifra.nota]));
  });

  it('los tres frentes de la cola de trabajo, enteros', () => {
    expect(
      BANDEJA.map((frente) => [
        frente.etiqueta,
        frente.tono,
        frente.titulo,
        frente.detalle,
        frente.cuantos,
      ]),
    ).toEqual(bandejaDelArtboard);
  });

  it('las cinco barras de avance, con su decimal', () => {
    expect(COBERTURA.map((fila) => [fila.tributo, fila.avance, fila.detalle])).toEqual(
      coberturaDelArtboard,
    );
  });

  it('los cuatro movimientos de la bitacora', () => {
    expect(
      ACTIVIDAD.map((acto) => [acto.tipo, acto.tono, acto.codigo, acto.detalle, acto.cuando]),
    ).toEqual(actividadDelArtboard);
  });

  it('el total de la cola se DERIVA de los tres frentes, y da el del artboard', () => {
    // Regla 4 de PORTAR.md: las cifras derivadas se derivan. El artboard escribe «1,134
    // pendientes» y ademas los tres sumandos; si algun dia dejaran de sumar eso, esta prueba
    // lo dice en vez de dejar una cabecera que miente sobre sus propias filas.
    const suma = BANDEJA.reduce((total, frente) => total + frente.cuantos, 0);
    expect(`${suma.toLocaleString('en-US')} pendientes`).toBe(textoTras('colaTotal:'));
    expect(COLA_TOTAL).toBe(textoTras('colaTotal:'));
  });

  it('la nota del avance es la del artboard, letra por letra', () => {
    expect(html).toContain(NOTA_DE_COBERTURA);
  });

  it('la fecha de corte es «al 31 de agosto», que el artboard escribe DOS veces', () => {
    // Una en la cabecera del avance y otra en la nota de la tarjeta «Recaudado». Es la fecha a
    // la que estan las cifras del panel, y no es la de captura del expediente (12/08/2026).
    expect(FECHA_DE_CORTE_DEL_PANEL).toBe('2026-08-31');
    expect(html).toContain('al 31 de agosto');
    expect(CIFRAS[1]?.nota).toContain('Al 31 de agosto');
  });
});

describe('las dos cosas del artboard que el port NO dibuja, y el contrato explica', () => {
  it('el artboard SI declara la pastilla «+3.1», y el contrato no publica variacion', () => {
    // La premisa: si el artboard dejara de traerla, esta renuncia dejaria de significar algo.
    expect(cifrasDelArtboard.map((cifra) => cifra.delta)).toEqual(['', '+3.1', '', '']);
    expect(CIFRAS.map((cifra) => cifra.delta)).toEqual(['', '+3.1', '', '']);

    const kpi = (formas['GET /indicadores/recaudacion'] as { kpis: Record<string, unknown>[] })
      .kpis[0];
    expect(
      Object.keys(kpi ?? {}).sort(),
      'El contrato publica un campo nuevo en el KPI. Si es la variacion, la pastilla del ' +
        'artboard ya se puede dibujar: hasta hoy no se dibuja porque nadie la sostiene.',
    ).toEqual(['importe', 'label', 'note', 'value']);
  });

  it('el artboard escribe una DISTANCIA y la bitacora publica un INSTANTE', () => {
    expect(actividadDelArtboard.map((acto) => acto[4])).toEqual([
      'hace 2 h',
      'ayer',
      'ayer',
      'hace 3 días',
    ]);

    const fila = (
      formas['GET /seguridad/auditoria'] as { contenido: Record<string, unknown>[] }
    ).contenido[0];
    expect(fila?.['fecha']).toBe('instante');
  });
});

describe('AC2 — lo que el padron publica, y lo que no', () => {
  it('`GET /rentas/contribuyentes` publica OCHO campos, y ninguno es el estado ni la deuda', () => {
    const contribuyente = (
      formas['GET /rentas/contribuyentes'] as { contenido: Record<string, unknown>[] }
    ).contenido[0];
    const campos = Object.keys(contribuyente ?? {});

    expect(campos.sort()).toEqual([
      'activo',
      'codigo',
      'condicionEspecial',
      'id',
      'nombreRazonSocial',
      'numeroDocumento',
      'tipoDocumento',
      'tipoPersona',
      // Este `sort()` es la afirmacion entera: el dia que aparezca aqui un `estado` o una
      // `deuda`, la fila del padron deja de tener que componerse de tres operaciones y esta
      // prueba dice donde mirar.
    ]);
  });

  it('el artboard SI dibuja los dos que faltan, y por eso el hueco se nota', () => {
    expect(prediosDelArtboard.every((uno) => uno.estado !== '')).toBe(true);
    expect(prediosDelArtboard.every((uno) => uno.autovaluo.startsWith('S/ '))).toBe(true);
  });

  it('las DOS operaciones que si contestan por contribuyente estan en el contrato', () => {
    expect(Object.keys(formas)).toContain('GET /coactiva/deudas');
    expect(Object.keys(formas)).toContain('GET /rentas/predial/corridas/{corridaId}/observados');
  });
});

describe('AC4 — las seis secciones del expediente son las de `PASOS`', () => {
  it('los seis identificadores y sus seis rotulos', () => {
    expect(SECCIONES_DEL_EXPEDIENTE.map((seccion) => [seccion.id, seccion.rotulo])).toEqual(
      pasosDelArtboard.map((paso) => [paso.id, paso.label]),
    );
  });

  it('la nota de cabecera de cada seccion, letra por letra', () => {
    expect(SECCIONES_DEL_EXPEDIENTE.map((seccion) => seccion.nota)).toEqual(
      pasosDelArtboard.map((paso) => paso.nota),
    );
  });

  it.each(pasosDelArtboard.map((paso, i) => [paso.label, i] as const))(
    '«%s» tiene los campos del artboard, con su tipo y sus opciones',
    (_rotulo, i) => {
      const aqui = SECCIONES_DEL_EXPEDIENTE[i];
      const alli = pasosDelArtboard[i];
      expect(aqui).toBeDefined();
      expect(alli).toBeDefined();

      expect(
        aqui?.campos.map((campo) => ({
          k: campo.clave,
          l: campo.etiqueta,
          // El artboard no escribe `t` cuando el campo es de texto; el port si lo declara.
          t: campo.tipo === 'text' ? undefined : campo.tipo,
          o: campo.opciones,
          ancho: campo.ancho === true ? 1 : undefined,
          opcional: campo.opcional,
          ph: campo.ph,
          ayuda: campo.ayuda,
        })),
      ).toEqual(
        alli?.campos.map((campo) => ({
          k: campo.k,
          l: campo.l,
          t: campo.t,
          o: campo.o,
          ancho: campo.ancho,
          opcional: campo.opcional,
          ph: campo.ph,
          ayuda: campo.ayuda,
        })),
      );
    },
  );

  it('las tres tablas conservan su titulo, su accion, sus columnas y sus dos textos', () => {
    const conTabla = pasosDelArtboard.filter((paso) => paso.tabla !== undefined);
    expect(conTabla).toHaveLength(3);

    expect(
      SECCIONES_DEL_EXPEDIENTE.filter((seccion) => seccion.tabla !== undefined).map((seccion) => ({
        titulo: seccion.tabla?.titulo,
        accion: seccion.tabla?.accion,
        vacioTexto: seccion.tabla?.vacioTexto,
        nota: seccion.tabla?.nota,
        cols: seccion.tabla?.columnas.map(([rotulo, derecha]) => [rotulo, derecha ? 1 : 0]),
      })),
    ).toEqual(
      conTabla.map((paso) => ({
        titulo: paso.tabla?.titulo,
        accion: paso.tabla?.accion,
        vacioTexto: paso.tabla?.vacioTexto,
        nota: paso.tabla?.nota,
        cols: paso.tabla?.cols,
      })),
    );
  });
});

describe('AC7 — la longitud de cada documento es la de `DOCS`', () => {
  it('los tres tipos, en el orden del artboard', () => {
    expect(TIPOS_DE_DOCUMENTO).toEqual(Object.keys(docsDelArtboard));
  });

  it.each(Object.entries(docsDelArtboard))('«%s» tiene %s dígitos', (tipo, largo) => {
    expect(longitudDe(tipo)).toBe(largo);
  });
});

describe('AC8 — el documento repetido es el del padron, y no una constante', () => {
  it('`DOC_EN_USO` es el documento de uno de los cinco del padron', () => {
    // Es lo que permite que la comprobacion del alta no sea contra una constante: el documento
    // repetido esta en el padron que el proxy sirve, y el aviso nombra a quien lo tiene.
    const enUso = textoTras('const DOC_EN_USO =');
    expect(enUso).toBe('44218937');

    const dueno = prediosDelArtboard.find((uno) => uno.titular.includes(enUso));
    expect(dueno?.titulo).toBe('Castillo Pascuala, María Elena');
    expect(dueno?.cod).toBe('00000003541');
  });
});

describe('el tono de cada estado es el que el artboard declara a su lado', () => {
  /** `ok`/`warn`/`bad`/`info` del artboard -> el tono del sistema de diseno. */
  const EQUIVALE: Readonly<Record<string, string>> = {
    ok: 'ok',
    warn: 'atencion',
    bad: 'mal',
    info: 'info',
  };

  const pares = [
    ...prediosDelArtboard.map((uno) => [uno.estado, uno.tono] as const),
    ...bandejaDelArtboard.map((frente) => [frente[0], frente[1]] as const),
    ...actividadDelArtboard.map((acto) => [acto[0], acto[1]] as const),
  ];

  it.each(pares)('«%s» se pinta con el tono «%s»', (estado, tono) => {
    expect(tonoDelEstado(estado)).toBe(EQUIVALE[tono]);
  });

  it('la tabla no tiene tonos de sobra: cada uno se usa o lo declara el port', () => {
    // Los dos que el artboard no declara son los que salen de `activo`, que es lo unico que la
    // operacion del padron publica del estado de un contribuyente.
    const delArtboard = new Set(pares.map(([estado]) => estado));
    expect(ESTADOS_CON_TONO.filter((estado) => !delArtboard.has(estado)).sort()).toEqual([
      'Activo',
      'De baja',
    ]);
  });

  it('un estado que nadie declara sale en gris, y no revienta', () => {
    expect(tonoDelEstado('En fraccionamiento')).toBe('info');
  });
});
