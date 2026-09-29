import type { ClaveDeHoja } from '../arbol.ts';
import {
  FORMAS_DE_NOTIFICACION,
  LARGO_DE_LA_OBSERVACION,
  LO_QUE_CONTESTO_LA_DILIGENCIA,
  NOTIFICAR_LA_RESOLUCION,
  RESULTADOS_DE_LA_DILIGENCIA,
  opcionQueLoPide,
  puedeHacerlo,
} from '../actos.ts';
import { EN_LA_RUTA, hayMasDe, paginasDe } from '../tablas.ts';
import type { DefinicionDePantalla as Pantalla, PiezaDeLaPantalla as Pieza } from '@kamayuk/ui';

/**
 * El nombre del sujeto en la ruta: el mismo `EL_SUJETO` de `@kamayuk/ui`, atado por el TIPO y no
 * importado como VALOR. Las definiciones las carga tambien el arnes de Playwright en Node
 * (`e2e/los-cuarenta.spec.ts`), y un valor de `@kamayuk/ui` arrastra su indice entero —hasta
 * `boton.tsx` y `class-variance-authority`—, que en la CI no resuelve: alli la libreria se clona sin
 * dependencias. Medido en `rentas`#635: `Error: Cannot find package 'class-variance-authority'` y
 * «Total: 0 tests in 0 files». Si la libreria cambia el nombre, `satisfies` no compila.
 */
const EL_SUJETO = 'sujeto' satisfies typeof import('@kamayuk/ui').EL_SUJETO;

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
          // **«Con multa notificada», y hasta #222 decia «Notificadas»** (y su gemelo en el
          // artboard). El rotulo prometia cuantas papeletas se notificaron, y eso NO CONSTA:
          // `EstadoDePapeleta` declara `NOTIFICADA` y ningun codigo de produccion lo escribe —el
          // unico `UPDATE papeleta` de `src/main` es `SET numero`, y el estado se escribe una vez,
          // en el `INSERT`, y siempre `IMPUESTA`—. Lo que si consta es la diligencia de la
          // resolucion de gerencia de esa papeleta, que el resumen publica desde #222. Se cambia el
          // rotulo y no la cifra: una papeleta notificada en la calle sin resolucion emitida no
          // entra en este recuento, y el rotulo ya no promete que si.
          { etiqueta: 'Con multa notificada', tipo: 'r' },
          // «Canceladas» conserva su rotulo y deja de traer cifra (#243): `pagadas` cuenta
          // `p.estado = 'PAGADA'` y NADIE lo escribe. No se deriva de nada —el libro no cruza a
          // una papeleta—, asi que la celda dice «no publicado» y nombra lo que falta.
          { etiqueta: 'Canceladas', tipo: 'r' },
          { etiqueta: 'Caducadas sin notificar', tipo: 'r' },
          // **«Con resolucion de multa», y hasta #243 decia «En coactiva»** (y su gemelo en el
          // artboard). Mismo defecto y misma salida que «Notificadas» en #222: `enCoactiva` cuenta
          // `p.estado = 'COACTIVA'`, que nadie escribe. Lo que SI consta de esa etapa es que la
          // resolucion de multa este emitida —la fila `GENERADO` de `papeleta_masivo_item`—, que
          // es el predicado con que el backend define `transito_padron_coactiva`. Se cambia el
          // rotulo y la cifra pasa a ser un hecho.
          { etiqueta: 'Con resolución de multa', tipo: 'r' },
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
        // **La resolucion de un recurso se notifica desde aqui** (#638). El backend la sirve desde
        // #629 —`POST /transito/descargos/{nDeExpediente}/resolucion/notificacion`— y ninguna
        // pantalla la ofrecia. Esta es la hoja que dibuja el recurso: declara `/transito/descargos`
        // y su tabla son los actos de la papeleta, con la resolucion que lo resuelve. Sin el
        // privilegio de registro sobre los descargos sale IMPEDIDO y dice por que —nunca
        // `disabled`—: no se ofrece una puerta que contesta 403.
        acciones: [
          {
            // No se llama como el acto, por lo mismo que el de `aut-sol`: dos botones con el mismo
            // nombre a la vista no se distinguen con un lector de pantalla.
            rotulo: 'Notificar la resolución de un recurso',
            abre: NOTIFICAR_LA_RESOLUCION,
            impedida: [
              {
                si: { dato: puedeHacerlo(NOTIFICAR_LA_RESOLUCION), hay: false },
                motivo: {
                  plantilla: `Notificar la resolución de un recurso pide el privilegio de registro sobre «{${opcionQueLoPide(NOTIFICAR_LA_RESOLUCION)}}», que esta cuenta no tiene.`,
                },
              },
            ],
          },
        ],
      },
      /**
       * **El acto de notificar la resolucion de un recurso** (#638). No es un bloque del artboard, y
       * por eso `pantallas-del-artboard` no lo compara: el artboard declara la operacion —en
       * `const ARBOL`, junto a esta hoja— y el acto es lo que la ofrece. Es la forma del de
       * `aut-sol` (#455, #629).
       *
       * · **Pide lo que la peticion pide, y como lo pide**: `PeticionDeNotificacionDeResolucion`
       *   exige la fecha, la forma, el resultado y el notificador, y admite en blanco la direccion,
       *   quien recibio, su documento, su vinculo y el acuse. Aqui son obligatorios los mismos cuatro
       *   —el interprete no deja enviar sin ellos— y opcionales los mismos cinco.
       * · **La forma y el resultado se leen con sus rotulos y viajan con el nombre del enumerado**:
       *   las opciones salen de los mapas de `pantallas/actos.ts`, de los que `datos/actos.ts` saca
       *   lo que manda.
       * · **La observacion es obligatoria EN EL TIPO** (regla 10), con el largo del backend.
       * · **No se corrige**, y por eso lleva `advertencia`: cada diligencia es un intento con su
       *   numero, sin `UPDATE` ni `DELETE`, y si surte efecto abre el plazo para impugnar.
       * · **Lo hecho dice si el plazo corre**, segun el `resultado` que contesto el backend: solo
       *   «No ubicado» lo deja sin abrir. La cifra del plazo no se escribe: vive en el conjunto
       *   sellado (regla 5).
       */
      {
        tipo: 'acto',
        clave: NOTIFICAR_LA_RESOLUCION,
        titulo: 'Registrar la diligencia',
        nota: 'Registra la diligencia de la resolución que resolvió un recurso. Si surte efecto, abre el plazo para impugnarla.',
        campos: [
          { nombre: 'expediente', etiqueta: 'Expediente del recurso', tipo: '' },
          { nombre: 'fechaDeNotificacion', etiqueta: 'Fecha de notificación', tipo: 'd' },
          {
            nombre: 'modalidad',
            etiqueta: 'Forma de notificación',
            tipo: 's',
            opciones: Object.keys(FORMAS_DE_NOTIFICACION),
          },
          {
            nombre: 'resultado',
            etiqueta: 'Resultado',
            tipo: 's',
            opciones: Object.keys(RESULTADOS_DE_LA_DILIGENCIA),
            ayuda: 'Solo «No ubicado» deja el plazo sin abrir: la negativa a recibir también surte efecto.',
          },
          { nombre: 'notificador', etiqueta: 'Notificador', tipo: '' },
          {
            nombre: 'direccion',
            etiqueta: 'Dirección',
            tipo: '1',
            opcional: true,
            ayuda: 'Sin ella, el domicilio fiscal vigente del obligado a la fecha de la diligencia.',
          },
          { nombre: 'recibidoPor', etiqueta: 'Recibido por', tipo: '', opcional: true },
          { nombre: 'documentoDelReceptor', etiqueta: 'Documento del receptor', tipo: '', opcional: true },
          { nombre: 'vinculo', etiqueta: 'Vínculo con el administrado', tipo: '', opcional: true },
          { nombre: 'acuse', etiqueta: 'Acuse', tipo: '', opcional: true, ayuda: 'La constancia del cargo.' },
        ],
        observacion: {
          etiqueta: 'Observación',
          ayuda: 'Por qué se registra. Queda en la auditoría, con la cuenta que la registró.',
          largo: LARGO_DE_LA_OBSERVACION,
        },
        advertencia:
          'La diligencia no se corrige ni se borra: queda como un intento más de la resolución, y si surte efecto abre el plazo para impugnarla.',
        errores: 'trasElPrimerIntento',
        hecho: {
          titulo: {
            segun: LO_QUE_CONTESTO_LA_DILIGENCIA.resultado,
            casos: {
              NOTIFICADO: 'Resolución notificada: corre el plazo para impugnarla',
              RECHAZADO: 'Negativa a recibir certificada: corre el plazo para impugnarla',
              NO_UBICADO: 'No se ubicó a nadie: el plazo no corre, y hay que volver a diligenciar',
            },
            otro: 'Diligencia registrada',
          },
          texto: {
            plantilla: `Diligencia {${LO_QUE_CONTESTO_LA_DILIGENCIA.numero}} de la resolución {${LO_QUE_CONTESTO_LA_DILIGENCIA.resolucion}}, en {${LO_QUE_CONTESTO_LA_DILIGENCIA.direccion}}.`,
          },
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
          // **La placa escrita aqui es la que se abre** (`kamayuk-lib`#97, #629): al salir del campo
          // o con Intro, lo tecleado pasa a ser el SUJETO de la ruta —`#/tra-veh/T2G-418`—, que es
          // lo que el conector ya pedia. Hasta #629 la unica forma de elegir un vehiculo era
          // escribir la direccion a mano. Los otros cinco campos no escriben la ruta, y el motivo
          // esta en `datos/conectores/transito.ts`.
          { etiqueta: 'Placa', tipo: '', eleccion: { enLaRuta: EL_SUJETO } },
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
            sentidoEnLaRuta: EN_LA_RUTA.sentido,
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
} satisfies Partial<Record<ClaveDeHoja, Pantalla<Pieza>>>;
