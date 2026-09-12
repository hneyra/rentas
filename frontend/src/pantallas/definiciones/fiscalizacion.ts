import type { ClaveDeHoja } from '../arbol.ts';
import type { Pantalla } from '../tipos.ts';

/**
 * Las cuatro pantallas de **Fiscalización** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'fis-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const FISCALIZACION = {
  'fis-panel': {
    instruccion: 'elija un programa y siga su embudo, de lo detectado a lo que sostiene una determinación.',
    bloques: [
      {
        titulo: 'Estado de la fiscalización',
        nota: 'Lo detectado, lo inspeccionado y lo que sostiene una determinación.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          { etiqueta: 'Programa', tipo: 's', opciones: ['Todos', 'PF-2026-014', 'PF-2026-008'] },
          { etiqueta: 'Detectados por cruce', tipo: 'r', valor: '3,418' },
          { etiqueta: 'Programados', tipo: 'r', valor: '96' },
          { etiqueta: 'Con acta cerrada', tipo: 'r', valor: '84' },
          { etiqueta: 'Con diferencia', tipo: 'r', valor: '61' },
        ],
      },
    ],
  },
  'fis-actas': {
    instruccion: 'registre lo que midió en campo. La diferencia contra lo declarado es lo que sostiene la liquidación.',
    bloques: [
      {
        titulo: 'Acta de inspección',
        nota: 'Lo que el verificador midió frente a lo que el titular declaró.',
        campos: [
          { etiqueta: 'Nº de acta', tipo: '' },
          { etiqueta: 'Programa', tipo: 's', opciones: ['PF-2026-014', 'PF-2026-008'] },
          { etiqueta: 'Tipo de acta', tipo: 's', opciones: ['Predial', 'Vehicular'] },
          { etiqueta: 'Código predial', tipo: '' },
          { etiqueta: 'Contribuyente', tipo: '1' },
          {
            etiqueta: 'Verificador',
            tipo: 's',
            opciones: ['Reto Santos, Víctor', 'Peña Sandoval, Luis'],
          },
          { etiqueta: 'Fecha de inspección', tipo: 'd' },
          { etiqueta: 'Con presencia del titular', tipo: 'c', casilla: 'El titular firmó el acta' },
          {
            etiqueta: 'Observaciones',
            tipo: 'a1',
            ayuda: 'Lo que hay que saber antes de volver al predio',
          },
        ],
        tabla: {
          titulo: 'Declarado contra verificado',
          columnas: [
            { rotulo: 'Concepto', alineadoDerecha: false },
            { rotulo: 'Declarado', alineadoDerecha: true },
            { rotulo: 'Verificado', alineadoDerecha: true },
            { rotulo: 'Diferencia', alineadoDerecha: true },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          filas: [
            ['Área de terreno (m²)', '210.00', '210.00', '0.00', 'Conforme'],
            ['Área construida (m²)', '164.50', '198.00', '+33.50', 'Con diferencia'],
            ['Número de pisos', '2', '2', '0', 'Conforme'],
            ['Uso del predio', 'Casa habitación', 'Comercio', '—', 'Con diferencia'],
          ],
          columnaDeInsignia: 4,
          nota: 'La diferencia es lo que sostiene la determinación. Sin acta cerrada no se puede liquidar.',
        },
      },
    ],
  },
  'fis-prog': {
    instruccion: 'defina el criterio del cruce y el tamaño de la muestra. De ahí salen las inspecciones del programa.',
    bloques: [
      {
        titulo: 'Programa de fiscalización',
        nota: 'La muestra sale de un cruce: catastro contra rentas.',
        campos: [
          { etiqueta: 'Nº de programa', tipo: '' },
          { etiqueta: 'Denominación', tipo: '1' },
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          {
            etiqueta: 'Criterio del cruce',
            tipo: 's',
            opciones: [
              'Predio sin declarar',
              'Área subvaluada',
              'Uso distinto al declarado',
              'Omiso a la declaración',
            ],
          },
          { etiqueta: 'Sector', tipo: 's', opciones: ['Todos', '01', '02', '03', '04', '05'] },
          { etiqueta: 'Tamaño de la muestra', tipo: '' },
          { etiqueta: 'Fecha de inicio', tipo: 'd' },
          { etiqueta: 'Fecha de cierre', tipo: 'd' },
        ],
        tabla: {
          titulo: 'Muestra del programa',
          conteo: '96 de 3,418 detectados',
          accion: 'Regenerar muestra',
          columnas: [
            { rotulo: 'Código predial', alineadoDerecha: false },
            { rotulo: 'Contribuyente', alineadoDerecha: false },
            { rotulo: 'Causa del cruce', alineadoDerecha: false },
            { rotulo: 'Diferencia estimada S/', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          filas: [
            [
              '02-014-D-14-01',
              'Suc. Rufina Medina Medina',
              'Área subvaluada',
              '1,842.60',
              'Inspeccionado',
            ],
            [
              '04-021-B-07-00',
              'Castillo Pascuala, María E.',
              'Predio sin declarar',
              '3,412.00',
              'Programado',
            ],
            [
              '02-016-A-09-00',
              'Inversiones del Norte S.A.C.',
              'Uso distinto al declarado',
              '8,844.20',
              'Con diferencia',
            ],
          ],
          columnaDeInsignia: 4,
        },
      },
    ],
  },
  'fis-res': {
    instruccion: 'compruebe la liquidación por ejercicio. Emitir la resolución es lo que la vuelve deuda exigible.',
    bloques: [
      {
        titulo: 'Liquidación de la diferencia',
        nota: 'La deuda omitida se determina aquí y entra en la cuenta corriente al emitir.',
        campos: [
          { etiqueta: 'Nº de acta', tipo: 'r', valor: 'ACT-2026-00418' },
          { etiqueta: 'Contribuyente', tipo: 'r', valor: 'Suc. Rufina Medina Medina' },
          {
            etiqueta: 'Ejercicios alcanzados',
            tipo: 's',
            opciones: ['2024 — 2026', '2022 — 2026', 'Sólo 2026'],
          },
          { etiqueta: 'Insoluto omitido', tipo: 'r', valor: 'S/ 603.00' },
          { etiqueta: 'Interés', tipo: 'r', valor: 'S/ 79.80' },
          { etiqueta: 'Multa tributaria', tipo: 'r', valor: 'S/ 267.50' },
          {
            etiqueta: 'Artículo del Código Tributario',
            tipo: 's',
            opciones: [
              '176º — No presentar declaración',
              '178º — Declarar cifras falsas',
              'No aplica',
            ],
          },
          { etiqueta: 'Total liquidado', tipo: 'r', valor: 'S/ 950.30' },
        ],
        tabla: {
          titulo: 'Detalle por ejercicio',
          columnas: [
            { rotulo: 'Ejercicio', alineadoDerecha: false },
            { rotulo: 'Base omitida S/', alineadoDerecha: true },
            { rotulo: 'Insoluto S/', alineadoDerecha: true },
            { rotulo: 'Interés S/', alineadoDerecha: true },
            { rotulo: 'Total S/', alineadoDerecha: true },
          ],
          filas: [
            ['2024', '33,500.00', '201.00', '48.24', '249.24'],
            ['2025', '33,500.00', '201.00', '26.13', '227.13'],
            ['2026', '33,500.00', '201.00', '5.43', '206.43'],
          ],
          nota: 'Emitir la resolución de determinación es lo que convierte esto en deuda exigible.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
