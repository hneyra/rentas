import type { ClaveDeHoja } from '../arbol.ts';
import type { DefinicionDePantalla as Pantalla } from '@kamayuk/ui';

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
          { etiqueta: 'Documento', tipo: 'r' },
          { etiqueta: 'Fecha de cálculo', tipo: 'r' },
          { etiqueta: 'Insoluto', tipo: 'r' },
          { etiqueta: 'Interés y reajuste', tipo: 'r' },
          { etiqueta: 'Gastos y costas', tipo: 'r' },
          { etiqueta: 'Total', tipo: 'r' },
          { etiqueta: 'Beneficio vigente', tipo: 'r' },
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
          columnas: [
            { rotulo: 'Código', alineadoDerecha: false },
            { rotulo: 'Nombre o razón social', alineadoDerecha: false },
            { rotulo: 'Documento', alineadoDerecha: false },
            { rotulo: 'Unidades', alineadoDerecha: false },
            { rotulo: 'Deuda hoy S/', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
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
          columnas: [
            { rotulo: 'Código predial', alineadoDerecha: false },
            { rotulo: 'Ubicación', alineadoDerecha: false },
            { rotulo: 'Titular', alineadoDerecha: false },
            { rotulo: 'Autovalúo S/', alineadoDerecha: true },
            { rotulo: 'Deuda S/', alineadoDerecha: true },
            { rotulo: 'Conciliado', alineadoDerecha: false },
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
          { etiqueta: 'Resultado', tipo: 'r' },
          {
            etiqueta: 'Incluye deuda en coactiva',
            tipo: 'c',
            casilla: 'Suma también lo que está en cobranza coactiva',
          },
        ],
        tabla: {
          titulo: 'Deuda que impide la constancia',
          columnas: [
            { rotulo: 'Año', alineadoDerecha: false },
            { rotulo: 'Concepto', alineadoDerecha: false },
            { rotulo: 'Cuotas', alineadoDerecha: false },
            { rotulo: 'Total S/', alineadoDerecha: true },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
