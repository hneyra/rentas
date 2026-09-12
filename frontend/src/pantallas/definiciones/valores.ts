import type { ClaveDeHoja } from '../arbol.ts';
import type { Pantalla } from '../tipos.ts';

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
          { etiqueta: 'Emitidos', tipo: 'r', valor: '8,224' },
          { etiqueta: 'Notificados', tipo: 'r', valor: '4,118' },
          { etiqueta: 'Sin notificar', tipo: 'r', valor: '4,106' },
          { etiqueta: 'Reclamados', tipo: 'r', valor: '188' },
          { etiqueta: 'Por prescribir este año', tipo: 'r', valor: '412' },
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
          { etiqueta: 'Interés', tipo: 'r', valor: 'S/ 224.44' },
          { etiqueta: 'Total del valor', tipo: 'r', valor: 'S/ 2,067.04' },
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
          conteo: '3 movimientos',
          columnas: [
            { rotulo: 'Fecha', alineadoDerecha: false },
            { rotulo: 'Movimiento', alineadoDerecha: false },
            { rotulo: 'Documento', alineadoDerecha: false },
            { rotulo: 'Importe S/', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          filas: [
            ['04/08/2026', 'Emisión', 'OP-2026-004182', '2,067.04', 'Conforme'],
            ['11/08/2026', 'Notificación personal', 'Cédula 2026-1184', '—', 'Conforme'],
            ['28/08/2026', 'Reclamación', 'Expediente 2026-0918', '—', 'Por vencer'],
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
          conteo: 'Ejecutada el 28/08/2026',
          columnas: [
            { rotulo: 'Etapa', alineadoDerecha: false },
            { rotulo: 'Registros', alineadoDerecha: true },
            { rotulo: 'Monto S/', alineadoDerecha: true },
            { rotulo: 'Observados', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          filas: [
            ['Selección de la cartera', '8,412', '3,884,116.00', '0', 'Conforme'],
            ['Emisión de valores', '8,224', '3,812,440.00', '188', 'Conforme'],
            ['Generación de cedulones', '8,224', '—', '0', 'Conforme'],
            ['Notificación', '4,118', '—', '4,106', 'Observado'],
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
          { etiqueta: 'Prescriben este año', tipo: 'r', valor: '412 valores · S/ 184,412.00' },
        ],
        tabla: {
          titulo: 'Reloj de prescripción',
          conteo: '4 tramos',
          columnas: [
            { rotulo: 'Ejercicio', alineadoDerecha: false },
            { rotulo: 'Valores', alineadoDerecha: true },
            { rotulo: 'Importe S/', alineadoDerecha: true },
            { rotulo: 'Prescribe el', alineadoDerecha: false },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          filas: [
            ['2022', '412', '184,412.00', '31/12/2026', 'Por vencer'],
            ['2023', '588', '244,116.00', '31/12/2027', 'Conforme'],
            ['2024', '844', '388,240.00', '31/12/2028', 'Conforme'],
            ['2021 y anteriores', '188', '84,116.00', 'Vencido', 'Vencida'],
          ],
          columnaDeInsignia: 4,
          nota: 'Declarar la prescripción es un acto: se hace de oficio o a pedido, y queda en la bitácora.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
