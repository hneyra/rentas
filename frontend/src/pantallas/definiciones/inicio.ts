import type { ClaveDeHoja } from '../arbol.ts';
import type { Pantalla } from '../tipos.ts';

/**
 * Las cuatro pantallas de **Inicio** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'ini-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const INICIO = {
  'ini-panel': {
    instruccion: 'revise el avance del ejercicio. Si algo no cuadra, la sección «Trabajo parado» dice qué acto falta.',
    bloques: [
      {
        titulo: 'Ejercicio en curso',
        nota: '',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025', '2024'] },
          { etiqueta: 'Emitido del ejercicio', tipo: 'r', valor: 'S/ 23,725,394.80' },
          { etiqueta: 'Recaudado', tipo: 'r', valor: 'S/ 18,424,251.20' },
          { etiqueta: 'Avance', tipo: 'r', valor: '77.7 %' },
          { etiqueta: 'Contribuyentes activos', tipo: 'r', valor: '62,418' },
          { etiqueta: 'Observados sin emisión', tipo: 'r', valor: '534' },
        ],
      },
    ],
  },
  'ini-flujo': {
    instruccion: 'elija el tributo y el periodo. El saldo por cobrar es lo que sigue vivo mientras no prescriba.',
    bloques: [
      {
        titulo: 'Emitido contra recaudado',
        nota: 'Por tributo, al día de hoy.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025', '2024'] },
          { etiqueta: 'Desde', tipo: 'd' },
          { etiqueta: 'Hasta', tipo: 'd' },
          {
            etiqueta: 'Tributo',
            tipo: 's',
            opciones: [
              'Todos',
              'Impuesto predial',
              'Arbitrios municipales',
              'Patrimonio vehicular',
              'Alcabala',
              'Multas',
            ],
          },
        ],
        tabla: {
          titulo: 'Cuadre por tributo',
          columnas: [
            { rotulo: 'Tributo', alineadoDerecha: false },
            { rotulo: 'Emitido S/', alineadoDerecha: true },
            { rotulo: 'Recaudado S/', alineadoDerecha: true },
            { rotulo: 'Saldo S/', alineadoDerecha: true },
            { rotulo: 'Avance', alineadoDerecha: true },
          ],
          filas: [
            ['Impuesto predial', '9,418,204.60', '8,420,118.40', '998,086.20', '89.4 %'],
            ['Arbitrios municipales', '5,884,110.20', '5,112,440.80', '771,669.40', '86.9 %'],
            ['Patrimonio vehicular', '2,884,000.00', '1,882,400.00', '1,001,600.00', '65.3 %'],
            ['Alcabala', '1,420,880.00', '1,420,880.00', '0.00', '100.0 %'],
            ['Multas y papeletas', '4,118,200.00', '1,588,412.00', '2,529,788.00', '38.6 %'],
          ],
          nota: 'El saldo por cobrar no es deuda perdida: es lo que sigue vivo mientras no prescriba.',
        },
      },
    ],
  },
  'ini-parado': {
    instruccion: 'elija un frente y resuélvalo: cada fila es dinero que no entra por un acto que se puede hacer hoy.',
    bloques: [
      {
        titulo: 'Trabajo parado',
        nota: 'Dinero que no entra por un acto que se puede hacer hoy.',
        campos: [
          {
            etiqueta: 'Módulo',
            tipo: 's',
            opciones: [
              'Todos',
              'Tránsito',
              'Valores',
              'Coactiva',
              'Fiscalización',
              'Autorizaciones',
            ],
          },
          {
            etiqueta: 'Antigüedad mínima',
            tipo: 's',
            opciones: ['Cualquiera', 'Más de 30 días', 'Más de 90 días'],
          },
        ],
        tabla: {
          titulo: 'Frentes abiertos',
          columnas: [
            { rotulo: 'Módulo', alineadoDerecha: false },
            { rotulo: 'Qué falta', alineadoDerecha: false },
            { rotulo: 'Registros', alineadoDerecha: true },
            { rotulo: 'Importe S/', alineadoDerecha: true },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          filas: [
            ['Tránsito', 'Papeletas caducadas sin notificar', '1,842', '788,976.00', 'Vencida'],
            ['Valores', 'Emitidos y sin notificar', '412', '184,412.00', 'Vencida'],
            ['Coactiva', 'Expedientes importados sin REC', '388', '162,844.00', 'Vencida'],
            ['Fiscalización', 'Actas con diferencia sin emitir', '61', '214,882.40', 'Por vencer'],
            ['Autorizaciones', 'Solicitudes con el plazo agotado', '42', '0.00', 'Por vencer'],
          ],
          columnaDeInsignia: 4,
        },
      },
    ],
  },
  'ini-cierre': {
    instruccion: 'cuadre lo contado en caja contra lo registrado. La diferencia es lo que hay que explicar.',
    bloques: [
      {
        titulo: 'Cierre del día',
        nota: 'Lo cobrado en el turno, contra lo contado en caja.',
        campos: [
          { etiqueta: 'Caja', tipo: 's', opciones: ['C-1', 'C-2', 'C-3'] },
          { etiqueta: 'Turno', tipo: 's', opciones: ['Mañana', 'Tarde'] },
          { etiqueta: 'Fecha', tipo: 'd' },
          { etiqueta: 'Cajero', tipo: 'r', valor: 'J. Cárdenas Vega' },
          { etiqueta: 'Recaudado del turno', tipo: 'r', valor: 'S/ 9,418.60' },
          { etiqueta: 'Diferencia de arqueo', tipo: 'r', valor: 'S/ 0.00' },
        ],
        tabla: {
          titulo: 'Lo cobrado por concepto',
          columnas: [
            { rotulo: 'Concepto', alineadoDerecha: false },
            { rotulo: 'Recibos', alineadoDerecha: true },
            { rotulo: 'Efectivo S/', alineadoDerecha: true },
            { rotulo: 'Tarjeta S/', alineadoDerecha: true },
            { rotulo: 'Total S/', alineadoDerecha: true },
          ],
          filas: [
            ['Impuesto predial', '31', '4,182.40', '1,204.00', '5,386.40'],
            ['Arbitrios municipales', '14', '1,884.20', '412.00', '2,296.20'],
            ['Patrimonio vehicular', '4', '882.00', '564.00', '1,446.00'],
            ['Tasas del TUPA', '3', '290.00', '0.00', '290.00'],
          ],
          nota: 'El arqueo se cuadra contra lo contado en caja, no contra lo registrado: la diferencia es lo que hay que explicar.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
