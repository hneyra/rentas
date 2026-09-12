import type { ClaveDeHoja } from '../arbol.ts';
import type { Pantalla } from '../tipos.ts';

/**
 * Las cuatro pantallas de **Tránsito** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'tra-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const TRANSITO = {
  'tra-panel': {
    instruccion: 'atienda primero las caducadas sin notificar: existen y ya no se pueden cobrar.',
    bloques: [
      {
        titulo: 'Papeletas del ejercicio',
        nota: 'Por situación, con lo que se puede y no se puede cobrar.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          { etiqueta: 'Levantadas', tipo: 'r', valor: '8,412' },
          { etiqueta: 'Notificadas', tipo: 'r', valor: '5,884' },
          { etiqueta: 'Canceladas', tipo: 'r', valor: '2,118' },
          { etiqueta: 'Caducadas sin notificar', tipo: 'r', valor: '1,842' },
          { etiqueta: 'En coactiva', tipo: 'r', valor: '388' },
        ],
      },
    ],
  },
  'tra-pap': {
    instruccion: 'registre la papeleta y su notificación. Sin notificar dentro del plazo, la papeleta caduca.',
    bloques: [
      {
        titulo: 'Papeleta de tránsito',
        nota: 'La fecha de notificación decide si la papeleta se puede cobrar.',
        campos: [
          { etiqueta: 'Nº de papeleta', tipo: '' },
          { etiqueta: 'Placa', tipo: '' },
          { etiqueta: 'Fecha de la infracción', tipo: 'd' },
          { etiqueta: 'Hora', tipo: '' },
          {
            etiqueta: 'Código de infracción',
            tipo: 's',
            opciones: [
              'M-01 — Conducir sin licencia',
              'M-08 — Exceso de velocidad',
              'G-58 — Estacionar en zona rígida',
              'L-05 — No portar SOAT',
            ],
          },
          { etiqueta: 'Lugar', tipo: '1' },
          {
            etiqueta: 'Inspector',
            tipo: 's',
            opciones: ['Vílchez Rojas, Andrés', 'Peña Sandoval, Luis'],
          },
          { etiqueta: 'Infractor', tipo: '1' },
          { etiqueta: 'Documento del infractor', tipo: '' },
          { etiqueta: 'Licencia de conducir', tipo: '' },
          {
            etiqueta: 'Vehículo internado',
            tipo: 'c',
            casilla: 'El vehículo quedó en el depósito municipal',
          },
          { etiqueta: 'Observaciones', tipo: 'a1' },
        ],
        tabla: {
          titulo: 'Actos de la papeleta',
          conteo: '4 actos',
          columnas: [
            { rotulo: 'Nº', alineadoDerecha: false },
            { rotulo: 'Acto', alineadoDerecha: false },
            { rotulo: 'Fecha', alineadoDerecha: false },
            { rotulo: 'Documento', alineadoDerecha: false },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          filas: [
            ['1', 'Levantamiento', '18/07/2026', 'Papeleta 0041182', 'Conforme'],
            ['2', 'Notificación', '24/07/2026', 'Cédula 2026-0884', 'Conforme'],
            ['3', 'Descargo del infractor', '02/08/2026', 'Expediente 2026-0918', 'Por vencer'],
            ['4', 'Resolución de sanción', '—', '—', 'Pendiente'],
          ],
          columnaDeInsignia: 4,
          nota: 'Una papeleta no notificada dentro del plazo caduca: existe, y ya no se puede cobrar.',
        },
      },
    ],
  },
  'tra-veh': {
    instruccion: 'registre el internamiento. Sin la papeleta cancelada y la custodia pagada no se emite la orden de retiro.',
    bloques: [
      {
        titulo: 'Internamiento en depósito',
        nota: 'La custodia se tasa por día y la paga el titular al retirar.',
        campos: [
          { etiqueta: 'Placa', tipo: '' },
          { etiqueta: 'Nº de papeleta', tipo: '' },
          { etiqueta: 'Fecha de internamiento', tipo: 'd' },
          {
            etiqueta: 'Depósito',
            tipo: 's',
            opciones: ['Depósito municipal 1', 'Depósito municipal 2'],
          },
          {
            etiqueta: 'Clase de vehículo',
            tipo: 's',
            opciones: ['Automóvil', 'Camioneta', 'Motocicleta', 'Camión', 'Trimóvil'],
          },
          { etiqueta: 'Marca y modelo', tipo: '' },
          { etiqueta: 'Días de custodia', tipo: 'r', valor: '54' },
          { etiqueta: 'Tasa diaria', tipo: 'r', valor: 'S/ 18.00' },
          { etiqueta: 'Total de custodia', tipo: 'r', valor: 'S/ 972.00' },
          { etiqueta: 'Grúa', tipo: 'c', casilla: 'Se usó grúa para el traslado' },
        ],
        tabla: {
          titulo: 'Vehículos internados',
          conteo: '3 de 188',
          columnas: [
            { rotulo: 'Placa', alineadoDerecha: false },
            { rotulo: 'Clase', alineadoDerecha: false },
            { rotulo: 'Ingreso', alineadoDerecha: false },
            { rotulo: 'Días', alineadoDerecha: true },
            { rotulo: 'Custodia S/', alineadoDerecha: true },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          filas: [
            ['T2G-418', 'Automóvil', '18/07/2026', '54', '972.00', 'En depósito'],
            ['V1H-882', 'Camioneta', '02/08/2026', '39', '702.00', 'En depósito'],
            ['M4J-118', 'Motocicleta', '28/08/2026', '13', '156.00', 'Por vencer'],
          ],
          columnaDeInsignia: 5,
          nota: 'Sin la papeleta cancelada y la custodia pagada no se emite la orden de retiro.',
        },
      },
    ],
  },
  'tra-cua': {
    instruccion: 'consulte el código y su escala. La multa se recalcula cada año con la UIT.',
    bloques: [
      {
        titulo: 'Cuadro de infracciones',
        nota: 'La escala la fija el Reglamento Nacional de Tránsito.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          {
            etiqueta: 'Tipo',
            tipo: 's',
            opciones: ['Todas', 'Muy grave (M)', 'Grave (G)', 'Leve (L)'],
          },
          { etiqueta: 'UIT vigente', tipo: 'r', valor: 'S/ 5,350.00' },
          { etiqueta: 'Buscar código', tipo: '1' },
        ],
        tabla: {
          titulo: 'Códigos y escala',
          conteo: '4 de 312',
          columnas: [
            { rotulo: 'Código', alineadoDerecha: false },
            { rotulo: 'Infracción', alineadoDerecha: false },
            { rotulo: 'Tipo', alineadoDerecha: false },
            { rotulo: '% UIT', alineadoDerecha: true },
            { rotulo: 'Multa S/', alineadoDerecha: true },
            { rotulo: 'Medida', alineadoDerecha: false },
          ],
          filas: [
            ['M-01', 'Conducir sin licencia', 'Muy grave', '50 %', '2,675.00', 'Retención'],
            ['M-08', 'Exceso de velocidad', 'Muy grave', '50 %', '2,675.00', 'Retención'],
            ['G-58', 'Estacionar en zona rígida', 'Grave', '8 %', '428.00', 'Ninguna'],
            ['L-05', 'No portar SOAT', 'Leve', '4 %', '214.00', 'Ninguna'],
          ],
          nota: 'La multa se recalcula cada año con la UIT: la tabla guarda el porcentaje, no el importe.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
