import type { ClaveDeHoja } from '../arbol.ts';
import type { Pantalla } from '../tipos.ts';

/**
 * Las cuatro pantallas de **Consultas** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'con-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const CONSULTAS = {
  'con-panel': {
    instruccion: 'la deuda está calculada a hoy: cambia cada día, no se guarda.',
    bloques: [
      {
        titulo: 'Cuenta corriente del contribuyente',
        nota: 'La deuda es a la fecha de hoy: no se guarda, se calcula.',
        campos: [
          { etiqueta: 'Contribuyente', tipo: '1' },
          { etiqueta: 'Documento', tipo: 'r', valor: 'DNI 03593174' },
          { etiqueta: 'Fecha de cálculo', tipo: 'r', valor: '12/09/2026' },
          { etiqueta: 'Insoluto', tipo: 'r', valor: 'S/ 3,041.92' },
          { etiqueta: 'Interés y reajuste', tipo: 'r', valor: 'S/ 413.32' },
          { etiqueta: 'Gastos y costas', tipo: 'r', valor: 'S/ 108.00' },
          { etiqueta: 'Total', tipo: 'r', valor: 'S/ 3,563.24' },
          { etiqueta: 'Beneficio vigente', tipo: 'r', valor: 'Pensionista — 50 UIT' },
        ],
      },
    ],
  },
  'con-contrib': {
    instruccion: 'escriba lo que tenga: el sistema reconoce DNI, RUC, placa, código o nombre.',
    bloques: [
      {
        titulo: 'Buscar contribuyente',
        nota: 'Un solo campo: el sistema reconoce DNI, RUC, placa, código o nombre.',
        campos: [
          { etiqueta: 'Nombre, documento, placa o código', tipo: '1' },
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025', '2024'] },
          { etiqueta: 'Con deuda vencida', tipo: 'c', casilla: 'Sólo los que deben algo vencido' },
          {
            etiqueta: 'En cobranza coactiva',
            tipo: 'c',
            casilla: 'Sólo los que están en coactiva',
          },
        ],
        tabla: {
          titulo: 'Resultados',
          conteo: '4 de 62,418',
          columnas: [
            { rotulo: 'Código', alineadoDerecha: false },
            { rotulo: 'Nombre o razón social', alineadoDerecha: false },
            { rotulo: 'Documento', alineadoDerecha: false },
            { rotulo: 'Unidades', alineadoDerecha: false },
            { rotulo: 'Deuda hoy S/', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          filas: [
            [
              '00000025673',
              'Suc. Rufina Medina Medina',
              'DNI 03593174',
              '2 predios · 1 vehículo',
              '3,563.24',
              'Con deuda',
            ],
            [
              '00000003541',
              'Castillo Pascuala, María Elena',
              'DNI 44218937',
              '2 predios · 2 vehículos',
              '591.94',
              'Al día',
            ],
            [
              '00000006550',
              'Díaz Madrid, Julio César',
              'DNI 02718844',
              '3 predios',
              '9,412.15',
              'En coactiva',
            ],
            [
              '00000006551',
              'Noblecilla Arismendiz S.A.C.',
              'RUC 20525118447',
              '1 predio',
              '412.00',
              'Observado',
            ],
          ],
          columnaDeInsignia: 5,
        },
      },
    ],
  },
  'con-obj': {
    instruccion: 'cuando no sepa el contribuyente pero sí el predio, la placa o el valor, búsquelo por el objeto.',
    bloques: [
      {
        titulo: 'Consulta por objeto',
        nota: 'Cuando no se sabe el contribuyente pero sí el predio, la placa o el valor.',
        campos: [
          {
            etiqueta: 'Objeto',
            tipo: 's',
            opciones: ['Predio', 'Vehículo', 'Valor notificado', 'Expediente coactivo'],
          },
          {
            etiqueta: 'Identificador del objeto',
            tipo: '1',
            ayuda: 'Código predial, placa o número de valor',
          },
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['Todos', '2026', '2025', '2024'] },
        ],
        tabla: {
          titulo: 'Predios encontrados',
          conteo: '2 predios',
          columnas: [
            { rotulo: 'Código predial', alineadoDerecha: false },
            { rotulo: 'Ubicación', alineadoDerecha: false },
            { rotulo: 'Titular', alineadoDerecha: false },
            { rotulo: 'Autovalúo S/', alineadoDerecha: true },
            { rotulo: 'Deuda S/', alineadoDerecha: true },
            { rotulo: 'Conciliado', alineadoDerecha: false },
          ],
          filas: [
            [
              '02-014-D-14-01',
              'Calle Santa Rosa 116',
              'Suc. Rufina Medina Medina',
              '132,196.75',
              '2,360.76',
              'Conforme',
            ],
            [
              '04-021-B-07-00',
              'Mz. B Lt. 7 — Bellavista',
              'Castillo Pascuala, María E.',
              '38,420.00',
              '0.00',
              'Observado',
            ],
          ],
          columnaDeInsignia: 5,
          nota: 'Un predio sin conciliar tiene ficha catastral y no genera deuda predial.',
        },
      },
    ],
  },
  'con-doc': {
    instruccion: 'compruebe la deuda antes de emitir: con deuda pendiente sale constancia de deuda, no de no adeudo.',
    bloques: [
      {
        titulo: 'Constancia de no adeudo',
        nota: 'Se emite sólo si no debe nada. Con deuda sale constancia de deuda.',
        campos: [
          { etiqueta: 'Contribuyente', tipo: '1' },
          {
            etiqueta: 'Objeto de la constancia',
            tipo: 's',
            opciones: ['Todos sus tributos', 'Un predio', 'Un vehículo'],
          },
          { etiqueta: 'Identificador del objeto', tipo: '' },
          {
            etiqueta: 'Motivo',
            tipo: 's',
            opciones: ['Trámite notarial', 'Transferencia', 'Licencia', 'Otro'],
          },
          { etiqueta: 'Nº de expediente', tipo: '' },
          { etiqueta: 'Resultado', tipo: 'r', valor: 'Con deuda: saldría constancia de deuda' },
          {
            etiqueta: 'Incluye deuda en coactiva',
            tipo: 'c',
            casilla: 'Suma también lo que está en cobranza coactiva',
          },
        ],
        tabla: {
          titulo: 'Deuda que impide la constancia',
          conteo: '2 conceptos',
          columnas: [
            { rotulo: 'Año', alineadoDerecha: false },
            { rotulo: 'Concepto', alineadoDerecha: false },
            { rotulo: 'Cuotas', alineadoDerecha: false },
            { rotulo: 'Total S/', alineadoDerecha: true },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          filas: [
            ['2024', 'Impuesto predial', '1 a 4', '2,067.04', 'Vencida'],
            ['2024', 'Patrimonio vehicular', '1', '892.44', 'En coactiva'],
          ],
          columnaDeInsignia: 4,
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
