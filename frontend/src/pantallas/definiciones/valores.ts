import type { ClaveDeHoja } from '../arbol.ts';
import type { DefinicionDePantalla as Pantalla } from '@kamayuk/ui';

/**
 * Las cuatro pantallas de **Valores** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'val-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const VALORES = {
  'val-panel': {
    instruccion: 'un valor emitido y sin notificar no cobra, y le corre el plazo de prescripción igual.',
    bloques: [
      {
        titulo: 'Valores del ejercicio',
        nota: 'Un valor emitido y sin notificar no cobra, y le corre el plazo igual.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          { etiqueta: 'Emitidos', tipo: 'r' },
          { etiqueta: 'Notificados', tipo: 'r' },
          { etiqueta: 'Sin notificar', tipo: 'r' },
          { etiqueta: 'Reclamados', tipo: 'r' },
          { etiqueta: 'Por prescribir este año', tipo: 'r' },
        ],
      },
    ],
  },
  'val-val': {
    instruccion: 'registre la notificación: es lo que hace exigible la deuda del valor.',
    bloques: [
      {
        titulo: 'Valor',
        nota: 'La notificación es lo que hace exigible la deuda.',
        campos: [
          { etiqueta: 'Nº de valor', tipo: '' },
          {
            etiqueta: 'Tipo de valor',
            tipo: 's',
            opciones: [
              'Orden de pago',
              'Resolución de determinación',
              'Resolución de multa',
              'Resolución de pérdida de fraccionamiento',
            ],
          },
          { etiqueta: 'Contribuyente', tipo: '1' },
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025', '2024'] },
          {
            etiqueta: 'Concepto',
            tipo: 's',
            opciones: [
              'Impuesto predial',
              'Arbitrios municipales',
              'Patrimonio vehicular',
              'Multa tributaria',
            ],
          },
          { etiqueta: 'Insoluto', tipo: '' },
          { etiqueta: 'Interés', tipo: 'r' },
          { etiqueta: 'Total del valor', tipo: 'r' },
          { etiqueta: 'Fecha de emisión', tipo: 'd' },
          {
            etiqueta: 'Fecha de notificación',
            tipo: 'd',
            ayuda: 'Sin ella el valor no es exigible',
          },
          {
            etiqueta: 'Forma de notificación',
            tipo: 's',
            opciones: [
              'Personal en domicilio fiscal',
              'Con certificación de negativa',
              'Cedulón',
              'Publicación',
              'Electrónica',
            ],
          },
          {
            etiqueta: 'Estado',
            tipo: 's',
            opciones: ['Emitido', 'Notificado', 'Reclamado', 'Firme', 'Anulado'],
          },
        ],
        tabla: {
          titulo: 'Movimientos del valor',
          columnas: [
            { rotulo: 'Fecha', alineadoDerecha: false },
            { rotulo: 'Movimiento', alineadoDerecha: false },
            { rotulo: 'Documento', alineadoDerecha: false },
            { rotulo: 'Importe S/', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
        },
      },
    ],
  },
  'val-cart': {
    instruccion: 'simule antes de emitir: una corrida toca miles de cuentas y la notificación es el cuello de botella.',
    bloques: [
      {
        titulo: 'Emisión por lote',
        nota: 'Una corrida toca miles de cuentas: se simula antes de emitir.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          {
            etiqueta: 'Tipo de valor',
            tipo: 's',
            opciones: ['Orden de pago', 'Resolución de determinación', 'Resolución de multa'],
          },
          {
            etiqueta: 'Concepto',
            tipo: 's',
            opciones: ['Impuesto predial', 'Arbitrios municipales', 'Patrimonio vehicular'],
          },
          {
            etiqueta: 'Alcance',
            tipo: 's',
            opciones: ['Todo el padrón', 'Por sector', 'Por rango de deuda', 'Sólo observados'],
          },
          { etiqueta: 'Sector', tipo: 's', opciones: ['Todos', '01', '02', '03', '04', '05'] },
          { etiqueta: 'Deuda mínima', tipo: '' },
          {
            etiqueta: 'Antigüedad mínima',
            tipo: 's',
            opciones: ['Cualquiera', 'Más de 90 días', 'Más de un año'],
          },
          {
            etiqueta: 'Genera cuponera PDF',
            tipo: 'c',
            casilla: 'Produce el archivo para imprenta',
          },
        ],
        tabla: {
          titulo: 'Última corrida',
          columnas: [
            { rotulo: 'Etapa', alineadoDerecha: false },
            { rotulo: 'Registros', alineadoDerecha: true },
            { rotulo: 'Monto S/', alineadoDerecha: true },
            { rotulo: 'Observados', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
          nota: 'La notificación es el cuello de botella: lo emitido y no notificado no cobra.',
        },
      },
    ],
  },
  'val-tip': {
    instruccion: 'declarar la prescripción es un acto: se hace de oficio o a pedido, y queda en la bitácora.',
    bloques: [
      {
        titulo: 'Tipos de valor y prescripción',
        nota: 'La deuda prescribe a los cuatro años; un acto de cobranza reinicia el plazo.',
        campos: [
          {
            etiqueta: 'Tipo de valor',
            tipo: 's',
            opciones: [
              'Todos',
              'Orden de pago',
              'Resolución de determinación',
              'Resolución de multa',
            ],
          },
          {
            etiqueta: 'Ejercicio',
            tipo: 's',
            opciones: ['Todos', '2026', '2025', '2024', '2023', '2022'],
          },
          {
            etiqueta: 'Estado',
            tipo: 's',
            opciones: ['Todos', 'Vigente', 'Por prescribir', 'Prescrito'],
          },
          { etiqueta: 'Prescriben este año', tipo: 'r' },
        ],
        tabla: {
          titulo: 'Reloj de prescripción',
          columnas: [
            { rotulo: 'Ejercicio', alineadoDerecha: false },
            { rotulo: 'Valores', alineadoDerecha: true },
            { rotulo: 'Importe S/', alineadoDerecha: true },
            { rotulo: 'Prescribe el', alineadoDerecha: false },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
          nota: 'Declarar la prescripción es un acto: se hace de oficio o a pedido, y queda en la bitácora.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
