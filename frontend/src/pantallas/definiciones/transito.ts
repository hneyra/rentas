import type { ClaveDeHoja } from '../arbol.ts';
import { EN_LA_RUTA, hayMasDe, paginasDe } from '../tablas.ts';
import type { DefinicionDePantalla as Pantalla } from '@kamayuk/ui';

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
          { etiqueta: 'Levantadas', tipo: 'r' },
          { etiqueta: 'Notificadas', tipo: 'r' },
          { etiqueta: 'Canceladas', tipo: 'r' },
          { etiqueta: 'Caducadas sin notificar', tipo: 'r' },
          { etiqueta: 'En coactiva', tipo: 'r' },
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
          // Con `clave`, las filas llegan por `DatosDeLaPantalla.tablas` y sus celdas pueden decir
          // que NO hay dato —y por que— en vez de una raya muda (`kamayuk-lib`#87, #180).
          clave: 'actos-de-la-papeleta',
          sinDato: { texto: '—', nota: 'Ninguna operacion publica este dato.' },
          titulo: 'Actos de la papeleta',
          columnas: [
            { rotulo: 'Nº', alineadoDerecha: false },
            { rotulo: 'Acto', alineadoDerecha: false },
            { rotulo: 'Fecha', alineadoDerecha: false },
            { rotulo: 'Documento', alineadoDerecha: false },
            { rotulo: 'Estado', alineadoDerecha: false },
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
          { etiqueta: 'Días de custodia', tipo: 'r' },
          { etiqueta: 'Tasa diaria', tipo: 'r' },
          { etiqueta: 'Total de custodia', tipo: 'r' },
          { etiqueta: 'Grúa', tipo: 'c', casilla: 'Se usó grúa para el traslado' },
        ],
        tabla: {
          clave: 'vehiculos-internados',
          sinDato: { texto: '—', nota: 'Ninguna operacion publica este dato.' },
          // El deposito son 188 internamientos y el artboard escribe «3 de 188»: la tabla siempre
          // fue una ventana, y desde #186 lo es de verdad.
          paginacion: {
            en: 'servidor',
            enLaRuta: EN_LA_RUTA.pagina,
            tamano: 20,
            tamanos: [20, 50, 100],
            tamanoEnLaRuta: EN_LA_RUTA.tamano,
            hayMas: hayMasDe('vehiculos-internados'),
            paginas: paginasDe('vehiculos-internados'),
          },
          // La lista blanca es la de `InternamientoRepositoryJdbc`; el primero es el
          // `ORDEN_POR_OMISION` de `InternamientosController` (`fechaIngreso`).
          orden: {
            campos: [
              { valor: 'fechaIngreso', rotulo: 'Ingreso' },
              { valor: 'placa', rotulo: 'Placa' },
            ],
            enLaRuta: EN_LA_RUTA.ordenarPor,
            sentidoEnLaRuta: EN_LA_RUTA.direccion,
            ascendente: 'ASCENDENTE',
            descendente: 'DESCENDENTE',
          },
          titulo: 'Vehículos internados',
          columnas: [
            { rotulo: 'Placa', alineadoDerecha: false },
            { rotulo: 'Clase', alineadoDerecha: false },
            { rotulo: 'Ingreso', alineadoDerecha: false },
            { rotulo: 'Días', alineadoDerecha: true },
            { rotulo: 'Custodia S/', alineadoDerecha: true },
            { rotulo: 'Situación', alineadoDerecha: false },
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
          { etiqueta: 'UIT vigente', tipo: 'r' },
          { etiqueta: 'Buscar código', tipo: '1' },
        ],
        tabla: {
          titulo: 'Códigos y escala',
          columnas: [
            { rotulo: 'Código', alineadoDerecha: false },
            { rotulo: 'Infracción', alineadoDerecha: false },
            { rotulo: 'Tipo', alineadoDerecha: false },
            { rotulo: '% UIT', alineadoDerecha: true },
            { rotulo: 'Multa S/', alineadoDerecha: true },
            { rotulo: 'Medida', alineadoDerecha: false },
          ],
          nota: 'La multa se recalcula cada año con la UIT: la tabla guarda el porcentaje, no el importe.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
