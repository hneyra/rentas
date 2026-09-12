import type { ClaveDeHoja } from '../arbol.ts';
import type { Pantalla } from '../tipos.ts';

/**
 * Las cuatro pantallas de **Autorizaciones y licencias** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'aut-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const AUTORIZACIONES_Y_LICENCIAS = {
  'aut-panel': {
    instruccion: 'atienda las de plazo agotado: en aprobación automática, la autorización ya se entiende otorgada.',
    bloques: [
      {
        titulo: 'Solicitudes en trámite',
        nota: 'Agotado el plazo del TUPA, la autorización se entiende otorgada.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          { etiqueta: 'En evaluación', tipo: 'r', valor: '188' },
          { etiqueta: 'Con requisitos incompletos', tipo: 'r', valor: '88' },
          { etiqueta: 'Con plazo agotado', tipo: 'r', valor: '42' },
          { etiqueta: 'Otorgadas', tipo: 'r', valor: '474' },
          { etiqueta: 'Denegadas', tipo: 'r', valor: '26' },
        ],
      },
    ],
  },
  'aut-sol': {
    instruccion: 'compruebe los requisitos del TUPA. Un expediente incompleto se admite y el plazo corre igual.',
    bloques: [
      {
        titulo: 'Datos de la solicitud',
        nota: 'El giro decide la modalidad y el plazo.',
        campos: [
          { etiqueta: 'Nº de expediente', tipo: '' },
          {
            etiqueta: 'Trámite',
            tipo: 's',
            opciones: [
              'Licencia de funcionamiento',
              'Licencia de edificación (FUE)',
              'Anuncio y propaganda',
            ],
          },
          { etiqueta: 'Fecha de presentación', tipo: 'd' },
          { etiqueta: 'Plazo del TUPA', tipo: 'r', valor: '15 días hábiles' },
          { etiqueta: 'Días transcurridos', tipo: 'r', valor: '7' },
          { etiqueta: 'Modalidad', tipo: 'r', valor: 'Aprobación automática' },
          { etiqueta: 'Solicitante', tipo: '1' },
          { etiqueta: 'Documento', tipo: '' },
          { etiqueta: 'Denominación comercial', tipo: '1' },
          {
            etiqueta: 'Giro (CIIU)',
            tipo: 's',
            opciones: [
              'D-1549-19 — Restaurante-pollería',
              'G-5211-01 — Venta al por menor',
              'H-5520-02 — Restaurantes a domicilio',
              'I-6023-01 — Transporte de carga',
            ],
          },
          { etiqueta: 'Código catastral del local', tipo: '' },
          { etiqueta: 'Área del establecimiento (m²)', tipo: '' },
          { etiqueta: 'Aforo', tipo: '' },
          {
            etiqueta: 'Horario autorizado',
            tipo: 's',
            opciones: ['De 06:00 a 23:00 horas', 'De 08:00 a 20:00 horas', 'Las 24 horas'],
          },
        ],
      },
      {
        titulo: 'Requisitos del TUPA',
        nota: 'Un expediente incompleto se admite y el plazo corre igual: para detenerlo hay que observarlo.',
        campos: [
          { etiqueta: 'Solicitud-declaración jurada', tipo: 'c', casilla: 'Presentada' },
          { etiqueta: 'Copia del RUC y del documento', tipo: 'c', casilla: 'Presentada' },
          { etiqueta: 'Declaración de condiciones de seguridad', tipo: 'c', casilla: 'Presentada' },
          { etiqueta: 'Pago del derecho de trámite', tipo: 'c', casilla: 'Presentado' },
          {
            etiqueta: 'Compatibilidad de uso y zonificación',
            tipo: 'c',
            casilla: 'Verificada por Catastro',
          },
          {
            etiqueta: 'Inspección técnica de seguridad',
            tipo: 'c',
            casilla: 'Sólo si el riesgo es alto',
          },
        ],
        tabla: {
          titulo: 'Actos del expediente',
          conteo: '3 actos',
          columnas: [
            { rotulo: 'Nº', alineadoDerecha: false },
            { rotulo: 'Acto', alineadoDerecha: false },
            { rotulo: 'Fecha', alineadoDerecha: false },
            { rotulo: 'Documento', alineadoDerecha: false },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          filas: [
            ['1', 'Presentación del expediente', '04/08/2026', 'Expediente 2026-0280', 'Conforme'],
            ['2', 'Pago del derecho de trámite', '04/08/2026', 'Recibo 0003-0041183', 'Conforme'],
            [
              '3',
              'Verificación de requisitos',
              '05/08/2026',
              'Informe de admisibilidad',
              'Por vencer',
            ],
          ],
          columnaDeInsignia: 4,
        },
      },
    ],
  },
  'aut-cat': {
    instruccion: 'consulte el giro: su nivel de riesgo decide la modalidad de la licencia y su plazo.',
    bloques: [
      {
        titulo: 'Catálogo de giros y certificados',
        nota: 'El riesgo del giro decide la modalidad de la licencia.',
        campos: [
          { etiqueta: 'Buscar giro o actividad', tipo: '1' },
          {
            etiqueta: 'Materia',
            tipo: 's',
            opciones: ['Todas', 'Comercialización', 'Alimentos', 'Transporte', 'Industria'],
          },
          {
            etiqueta: 'Nivel de riesgo',
            tipo: 's',
            opciones: ['Todos', 'Bajo', 'Medio', 'Alto', 'Muy alto'],
          },
        ],
        tabla: {
          titulo: 'Giros CIIU',
          conteo: '4 de 1,842',
          accion: 'Añadir giro',
          columnas: [
            { rotulo: 'Código CIIU', alineadoDerecha: false },
            { rotulo: 'Actividad', alineadoDerecha: false },
            { rotulo: 'Materia', alineadoDerecha: false },
            { rotulo: 'Riesgo', alineadoDerecha: false },
          ],
          filas: [
            [
              'G-5211-01',
              'Venta al por menor en almacenes no especializados',
              'Comercialización',
              'Bajo',
            ],
            ['D-1549-19', 'Restaurante-pollería', 'Alimentos', 'Medio'],
            ['H-5520-02', 'Servicio de restaurantes a domicilio', 'Alimentos', 'Bajo'],
            ['I-6023-01', 'Transporte de carga por carretera', 'Transporte', 'Alto'],
          ],
          columnaDeInsignia: 3,
          nota: 'Bajo y medio van por aprobación automática con declaración jurada; alto y muy alto exigen inspección previa.',
        },
      },
    ],
  },
  'aut-tram': {
    instruccion: 'un establecimiento en funcionamiento que no figure en el padrón es la infracción B-118 del CUIS.',
    bloques: [
      {
        titulo: 'Padrón y reportes',
        nota: 'El padrón es la base del cruce con fiscalización.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025', '2024'] },
          {
            etiqueta: 'Tipo de licencia',
            tipo: 's',
            opciones: ['Todas', 'Definitiva', 'Temporal', 'Cesionaria'],
          },
          {
            etiqueta: 'Estado',
            tipo: 's',
            opciones: ['Todos', 'Activa', 'Pendiente', 'Vencida', 'Cesada'],
          },
          {
            etiqueta: 'Agrupado por',
            tipo: 's',
            opciones: ['Giro comercial', 'Año', 'Dirección', 'Titular'],
          },
          { etiqueta: 'Desde', tipo: 'd' },
          { etiqueta: 'Hasta', tipo: 'd' },
        ],
        tabla: {
          titulo: 'Padrón de licencias',
          conteo: '3 de 6,418',
          columnas: [
            { rotulo: 'Nº licencia', alineadoDerecha: false },
            { rotulo: 'Titular', alineadoDerecha: false },
            { rotulo: 'Denominación', alineadoDerecha: false },
            { rotulo: 'Giro', alineadoDerecha: false },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          filas: [
            ['2026-006549', 'Eleodoro Quiroga Ramos', 'Bodega El Sol', 'G-5211-01', 'Activa'],
            [
              '2026-006550',
              'Díaz Madrid, Julio César',
              'Bodega Los Ángeles',
              'G-5211-01',
              'Activa',
            ],
            [
              '2026-000000',
              'Castillo Pascuala, María E.',
              'Restaurant Sabor y Sazón',
              'D-1549-19',
              'Pendiente',
            ],
          ],
          columnaDeInsignia: 4,
          nota: 'Un establecimiento en funcionamiento que no figura aquí es la infracción B-118 del cuadro CUIS.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
