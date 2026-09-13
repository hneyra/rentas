import type { ClaveDeHoja } from '../arbol.ts';
import type { DefinicionDePantalla as Pantalla } from '@kamayuk/ui';

/**
 * Las cuatro pantallas de **Coactiva** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'coa-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const COACTIVA = {
  'coa-panel': {
    instruccion: 'los expedientes sin REC están abiertos y el procedimiento no ha empezado.',
    bloques: [
      {
        titulo: 'Cartera coactiva',
        nota: 'Por etapa, con lo que está parado y por qué.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          { etiqueta: 'Expedientes abiertos', tipo: 'r' },
          { etiqueta: 'Con REC notificada', tipo: 'r' },
          { etiqueta: 'Con medida cautelar', tipo: 'r' },
          { etiqueta: 'Sin REC', tipo: 'r' },
          { etiqueta: 'Deuda en cartera', tipo: 'r' },
        ],
      },
    ],
  },
  'coa-exp': {
    instruccion: 'dicte el acto que corresponda. El coste de cada uno se ve antes: es deuda que se añade a la del contribuyente.',
    bloques: [
      {
        titulo: 'Expediente coactivo',
        nota: 'Cada acto tiene su coste tasado, y lo paga el deudor.',
        campos: [
          { etiqueta: 'Nº de expediente', tipo: '' },
          { etiqueta: 'Contribuyente', tipo: '1' },
          { etiqueta: 'Documento', tipo: 'r' },
          { etiqueta: 'Fecha de apertura', tipo: 'd' },
          {
            etiqueta: 'Ejecutor coactivo',
            tipo: 's',
            opciones: ['Ayca Gonzales, Alberto', 'Quispe Peña, Jorge'],
          },
          {
            etiqueta: 'Auxiliar coactivo',
            tipo: 's',
            opciones: ['Ríos Mendoza, María', 'Cárdenas Vega, José'],
          },
          { etiqueta: 'Deuda en el expediente', tipo: 'r' },
          { etiqueta: 'Costas acumuladas', tipo: 'r' },
          {
            etiqueta: 'Etapa',
            tipo: 's',
            opciones: [
              'Importado',
              'REC notificada',
              'Medida cautelar',
              'Ejecución forzada',
              'Concluido',
            ],
          },
          {
            etiqueta: 'Dirección referencial',
            tipo: 'a1',
            ayuda: 'Donde se notifica si el domicilio fiscal falla',
          },
        ],
        tabla: {
          titulo: 'Actos del expediente',
          accion: 'Dictar acto',
          columnas: [
            { rotulo: 'Nº', alineadoDerecha: false },
            { rotulo: 'Acto', alineadoDerecha: false },
            { rotulo: 'Fecha', alineadoDerecha: false },
            { rotulo: 'Costa S/', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
          nota: 'El coste de cada acto se ve antes de dictarlo: es deuda que se añade a la del contribuyente.',
        },
      },
    ],
  },
  'coa-cart': {
    instruccion: 'fije la inicial y las cuotas. Dos cuotas impagas quiebran el convenio y reactivan la ejecución.',
    bloques: [
      {
        titulo: 'Convenio de fraccionamiento',
        nota: 'Dos cuotas impagas quiebran el convenio y reactivan la ejecución.',
        campos: [
          { etiqueta: 'Nº de expediente', tipo: 'r' },
          { etiqueta: 'Deuda a fraccionar', tipo: 'r' },
          { etiqueta: 'Inicial (%)', tipo: '', ayuda: 'No menos del 20 %' },
          { etiqueta: 'Monto de la inicial', tipo: 'r' },
          { etiqueta: 'Número de cuotas', tipo: 's', opciones: ['12', '6', '18', '24'] },
          { etiqueta: 'Interés de fraccionamiento', tipo: 'r' },
          { etiqueta: 'Fecha de la primera cuota', tipo: 'd' },
          {
            etiqueta: 'Garantía',
            tipo: 's',
            opciones: ['Ninguna', 'Carta fianza', 'Hipoteca', 'Prenda'],
          },
          { etiqueta: 'Cuota mensual resultante', tipo: 'r' },
        ],
        tabla: {
          titulo: 'Medidas cautelares vigentes',
          columnas: [
            { rotulo: 'Tipo', alineadoDerecha: false },
            { rotulo: 'Sobre', alineadoDerecha: false },
            { rotulo: 'Dictada', alineadoDerecha: false },
            { rotulo: 'Importe S/', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
          nota: 'Suscribir el convenio suspende la ejecución; no levanta las medidas ya inscritas.',
        },
      },
    ],
  },
  'coa-cost': {
    instruccion: 'las costas se tasan por arancel. Un acto de cobranza interrumpe el plazo de prescripción y lo reinicia.',
    bloques: [
      {
        titulo: 'Costas del procedimiento',
        nota: 'Se tasan por arancel, no se estiman.',
        campos: [
          { etiqueta: 'Nº de expediente', tipo: 'r' },
          { etiqueta: 'Actos dictados', tipo: 'r' },
          { etiqueta: 'Costas tasadas', tipo: 'r' },
          { etiqueta: 'Gastos de notificación', tipo: 'r' },
          { etiqueta: 'Total de costas', tipo: 'r' },
          { etiqueta: 'Reloj de prescripción', tipo: 'r' },
          {
            etiqueta: 'Suspendido por',
            tipo: 's',
            opciones: ['Nada', 'Convenio vigente', 'Reclamación', 'Proceso judicial'],
          },
        ],
        tabla: {
          titulo: 'Costas por acto',
          columnas: [
            { rotulo: 'Acto', alineadoDerecha: false },
            { rotulo: 'Arancel', alineadoDerecha: false },
            { rotulo: 'Cantidad', alineadoDerecha: true },
            { rotulo: 'Costa S/', alineadoDerecha: true },
          ],
          nota: 'La deuda prescribe a los cuatro años; un acto de cobranza interrumpe el plazo y lo reinicia.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
