import type { ClaveDeHoja } from '../arbol.ts';
import {
  ANULAR_LA_LICENCIA,
  LARGO_DE_LA_OBSERVACION,
  LO_QUE_CONTESTO_LA_ANULACION,
  opcionQueLoPide,
  puedeHacerlo,
} from '../actos.ts';
import { EN_LA_RUTA, hayMasDe, paginasDe } from '../tablas.ts';
import type { DefinicionDePantalla as Pantalla, PiezaDeLaPantalla as Pieza } from '@kamayuk/ui';

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
          { etiqueta: 'En evaluación', tipo: 'r' },
          { etiqueta: 'Con requisitos incompletos', tipo: 'r' },
          { etiqueta: 'Con plazo agotado', tipo: 'r' },
          { etiqueta: 'Otorgadas', tipo: 'r' },
          { etiqueta: 'Denegadas', tipo: 'r' },
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
          { etiqueta: 'Plazo del TUPA', tipo: 'r' },
          { etiqueta: 'Días transcurridos', tipo: 'r' },
          { etiqueta: 'Modalidad', tipo: 'r' },
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
        // **La anulacion de la licencia de edificacion tiene boton** (#455, #629). El backend la
        // sirve desde #455 —`POST /licencias/edificacion/{expediente}/anulacion`— y ninguna
        // pantalla la ofrecia. Abre el acto del final de esta hoja; sin el privilegio de registro
        // sobre el FUE sale IMPEDIDO y dice por que —nunca `disabled`—: no se ofrece una puerta
        // que contesta 403.
        acciones: [
          {
            // El boton que ABRE no se llama como el acto, a proposito: el primario del acto lleva su
            // titulo, y dos botones con el mismo nombre a la vista no se distinguen con un lector
            // de pantalla —uno abre el formulario y el otro anula—.
            rotulo: 'Anular una licencia de edificación',
            abre: ANULAR_LA_LICENCIA,
            impedida: [
              {
                si: { dato: puedeHacerlo(ANULAR_LA_LICENCIA), hay: false },
                motivo: {
                  plantilla: `Anular una licencia de edificación pide el privilegio de registro sobre «{${opcionQueLoPide(ANULAR_LA_LICENCIA)}}», que esta cuenta no tiene.`,
                },
              },
            ],
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
          columnas: [
            { rotulo: 'Nº', alineadoDerecha: false },
            { rotulo: 'Acto', alineadoDerecha: false },
            { rotulo: 'Fecha', alineadoDerecha: false },
            { rotulo: 'Documento', alineadoDerecha: false },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
        },
      },
      /**
       * **El acto de anular la licencia de edificacion** (#455, #629). No es un bloque del
       * artboard, y por eso `pantallas-del-artboard` no lo compara: el artboard declara la
       * operacion —en `const ARBOL`, junto a esta hoja— y el acto es lo que la ofrece.
       *
       * · **La observacion es obligatoria EN EL TIPO** (regla 10): `@kamayuk/ui` no deja escribir
       *   un acto sin ella, y su largo es el del backend (`LARGO_DE_LA_OBSERVACION`).
       * · **No se deshace**, y por eso lleva `advertencia`: el primario abre la confirmacion en vez
       *   de enviar. Una segunda anulacion de la misma licencia el backend la rechaza con 409.
       * · **`fecha` y `formato` no se piden**: sin ellos el backend anula con la fecha de hoy y saca
       *   la resolucion en PDF. Ver `anularLicenciaDeEdificacion`.
       */
      {
        tipo: 'acto',
        clave: ANULAR_LA_LICENCIA,
        titulo: 'Anular la licencia',
        nota: 'Deja sin efecto la licencia del expediente con una resolución que dice el motivo. No la borra: queda en su historial, y a una fecha anterior la licencia sigue vigente.',
        campos: [
          { nombre: 'expediente', etiqueta: 'Expediente del FUE', tipo: '' },
          {
            nombre: 'motivo',
            etiqueta: 'Motivo de la anulación',
            tipo: '1',
            ayuda: 'Es lo que dirá la resolución.',
          },
        ],
        observacion: {
          etiqueta: 'Observación',
          ayuda: 'Por qué se registra. Queda en la auditoría, con la cuenta que anuló.',
          largo: LARGO_DE_LA_OBSERVACION,
        },
        advertencia:
          'La anulación no se deshace: la resolución queda emitida, y la licencia no se puede volver a anular.',
        errores: 'trasElPrimerIntento',
        hecho: {
          titulo: 'Licencia anulada',
          texto: {
            plantilla: `Resolución {${LO_QUE_CONTESTO_LA_ANULACION.resolucion}}: la licencia {${LO_QUE_CONTESTO_LA_ANULACION.licencia}} del expediente {${LO_QUE_CONTESTO_LA_ANULACION.expediente}} queda sin efecto.`,
          },
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
          // Lo tecleado viaja a la ruta de la hoja al salir del campo o con Intro (#172,
          // `kamayuk-lib`#94), y de ahi a `?descripcion=` de `GET /licencias/ciiu`.
          { etiqueta: 'Buscar giro o actividad', tipo: '1', eleccion: { enLaRuta: 'descripcion' } },
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
          // Con `clave`, las filas llegan por `DatosDeLaPantalla.tablas` — que es el unico camino
          // por el que viaja el TOTAL publicado, «20 de 1 842» (#172).
          clave: 'giros-ciiu',
          // El catalogo son 1 842 giros: esta tabla es una ventana, y sin mandos no lo diria
          // (#186). `hayMas` y `paginas` los dice el SERVIDOR, y el conector los pone con esos
          // nombres — derivados del de la tabla, ver `pantallas/tablas.ts`.
          paginacion: {
            en: 'servidor',
            enLaRuta: EN_LA_RUTA.pagina,
            tamano: 20,
            tamanos: [20, 50, 100],
            tamanoEnLaRuta: EN_LA_RUTA.tamano,
            hayMas: hayMasDe('giros-ciiu'),
            paginas: paginasDe('giros-ciiu'),
          },
          // La lista blanca es la de `CiiuRepositoryJdbc`, medida y no supuesta; el PRIMERO es el
          // `ORDEN_POR_OMISION` de `CiiuController` (`codigo`), que es lo que hace que la barra no
          // mienta cuando la ruta no trae ninguno. Ver `datos/laVentana.ts`.
          orden: {
            campos: [
              { valor: 'codigo', rotulo: 'Código CIIU' },
              { valor: 'descripcion', rotulo: 'Actividad' },
              { valor: 'seccion', rotulo: 'Materia' },
              { valor: 'riesgoItse', rotulo: 'Riesgo' },
            ],
            enLaRuta: EN_LA_RUTA.ordenarPor,
            sentidoEnLaRuta: EN_LA_RUTA.sentido,
            ascendente: 'ASCENDENTE',
            descendente: 'DESCENDENTE',
          },
          titulo: 'Giros CIIU',
          accion: 'Añadir giro',
          columnas: [
            { rotulo: 'Código CIIU', alineadoDerecha: false },
            { rotulo: 'Actividad', alineadoDerecha: false },
            { rotulo: 'Materia', alineadoDerecha: false },
            { rotulo: 'Riesgo', alineadoDerecha: false },
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
          clave: 'padron-de-licencias',
          paginacion: {
            en: 'servidor',
            enLaRuta: EN_LA_RUTA.pagina,
            tamano: 20,
            tamanos: [20, 50, 100],
            tamanoEnLaRuta: EN_LA_RUTA.tamano,
            hayMas: hayMasDe('padron-de-licencias'),
            paginas: paginasDe('padron-de-licencias'),
          },
          // **Dos de las cinco columnas**, y no es un recorte: `GET /licencias/funcionamiento`
          // admite ordenar por `numero`, `fechaEmision`, `nombreComercial`, `direccion` y
          // `expediente`, y de esos cinco solo dos son columnas de esta tabla. Ofrecer «Fecha de
          // emisión» pondría la flecha sobre un dato que la tabla no enseña. «Titular», «Giro» y
          // «Estado» no están en la lista blanca: los tres se resuelven al leer —el titular lo
          // trae otro contexto, el estado se deriva a la fecha y el giro es una lista— y pedirlos
          // sería un 422.
          orden: {
            campos: [
              { valor: 'numero', rotulo: 'Nº licencia' },
              { valor: 'nombreComercial', rotulo: 'Denominación' },
            ],
            enLaRuta: EN_LA_RUTA.ordenarPor,
            sentidoEnLaRuta: EN_LA_RUTA.sentido,
            ascendente: 'ASCENDENTE',
            descendente: 'DESCENDENTE',
          },
          titulo: 'Padrón de licencias',
          columnas: [
            { rotulo: 'Nº licencia', alineadoDerecha: false },
            { rotulo: 'Titular', alineadoDerecha: false },
            { rotulo: 'Denominación', alineadoDerecha: false },
            { rotulo: 'Giro', alineadoDerecha: false },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
          nota: 'Un establecimiento en funcionamiento que no figura aquí es la infracción B-118 del cuadro CUIS.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla<Pieza>>>;
