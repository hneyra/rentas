/**
 * La captura del prototipo: los datos del artboard `RentasV6.dc.html`, copiados enteros.
 *
 * <h2>Por que estan aqui y no dentro del proxy</h2>
 *
 * Es la CAPTURA, y se separa de la INVENCION a proposito. Todo lo de este archivo sale del
 * artboard tal cual —cada cifra se puede buscar en `RentasV6.dc.html` y encontrarla igual—;
 * lo que el proxy tiene que anadir porque el prototipo no lo trae vive en `simulados.ts`, con
 * el nombre de la operacion que lo sustituira. Mezclarlos haria indistinguible una cifra del
 * manual de una que alguien escribio para que la pantalla no saliera vacia, que es justo la
 * distincion que hay que poder hacer el dia que esto se apague.
 *
 * <h2>AVISO — los literales tributarios de este archivo NO se quedan (regla 5)</h2>
 *
 * `VAL` trae la **UIT**, los **tres tramos de la escala progresiva**, sus **alicuotas**, los
 * dos **minimos imponibles**, la deduccion de pensionista y el derecho de emision; y
 * `DETERMINACIONES` trae el resultado de aplicarlos. La regla 5 del producto prohibe que una
 * cifra tributaria viva en el codigo: UIT, tramos, alicuotas, valores unitarios, aranceles y
 * tablas de depreciacion viven en datos versionados, porque recalcular 2027 en 2037 tiene que
 * dar el mismo centimo (RNF-053) y porque cambiarlas no puede exigir un despliegue.
 *
 * Aqui estan **solo mientras no hay backend al que preguntarle**, y son literales de un
 * artboard, no de una regla: nada de este archivo calcula nada. El dia que la operacion
 * conecte, estas cifras llegan de la API —`POST /rentas/predial/calculo-individual` publica
 * `uit`, `tramos[].alicuota` y `minimoImponible` ya resueltos por el conjunto sellado de
 * `normativa` (ADR-0025)— y **este archivo se borra entero**, no se actualiza. Una pantalla
 * que lea la UIT de aqui en vez de la respuesta esta escribiendo el defecto que la regla 5
 * existe para impedir.
 *
 * <h2>Procedencia</h2>
 *
 * Artboard `RentasV6.dc.html`, bloque `<script type="text/x-dc">`. Las constantes se
 * extrajeron con un parser sobre ese bloque, no a ojo:
 *
 *   · `DOCS` y `DOC_EN_USO`  — :969
 *   · `PASOS`                — :974
 *   · `PREDIOS`              — :1069
 *   · `NODOS`                — :1089
 *   · `DETERMINACIONES`      — :1095
 *   · `VAL`                  — :1163
 *   · `EXPEDIENTE`           — del metodo `datos()` del componente, que es donde el artboard
 *     guarda los VALORES del expediente; `PASOS` solo trae sus etiquetas y sus tipos de
 *     campo. Sin el, la ficha del contribuyente seria invencion de punta a punta.
 */

/** Un campo del formulario del expediente, tal como lo declara el artboard. */
export interface CampoDelPaso {
  /** Clave del valor en `EXPEDIENTE`. */
  readonly k: string;
  /** Etiqueta que ve el usuario. */
  readonly l: string;
  /** Tipo de control: `sel`, `date`, `ro`, `chk`, `area`; texto si falta. */
  readonly t?: string;
  /** Opciones del desplegable. */
  readonly o?: readonly string[];
  readonly ancho?: number;
  readonly opcional?: boolean;
  readonly ph?: string;
  readonly ayuda?: string;
}

/** Una tabla dentro de una seccion del expediente. */
export interface TablaDelPaso {
  readonly titulo: string;
  readonly min: string;
  readonly accion: string;
  readonly vacioTexto: string;
  /** Cabeceras: `[rotulo, alineadaALaDerecha]`. */
  readonly cols: readonly (readonly [string, number])[];
  readonly filas: readonly (readonly string[])[];
  readonly nota: string;
}

/** Una de las seis secciones del expediente del contribuyente. */
export interface PasoDelExpediente {
  readonly id: string;
  readonly label: string;
  readonly nota: string;
  readonly campos: readonly CampoDelPaso[];
  readonly tabla?: TablaDelPaso;
}

/** Un contribuyente del padron. */
export interface ContribuyenteDelPadron {
  readonly cod: string;
  readonly titulo: string;
  readonly titular: string;
  readonly uso: string;
  readonly autovaluo: string;
  readonly estado: string;
  readonly tono: string;
  /**
   * La deuda como numero de coma flotante, tal como la escribio el artboard.
   *
   * Se copia porque la captura se copia entera, y **el proxy no la sirve jamas**: el importe
   * que viaja sale de `autovaluo`, que es texto (regla 1, RNF-055). Un `1842.6` aqui es la
   * clave de ordenacion del prototipo, no una cifra de dinero — y en cuanto se sirviera como
   * tal, el centimo dejaria de estar garantizado.
   */
  readonly valor: number;
  readonly contexto: string;
}

/** Una determinacion, con la memoria de su calculo. */
export interface DeterminacionDelPrototipo {
  readonly titulo: string;
  readonly nota: string;
  readonly cols: readonly (readonly [string, number])[];
  readonly filas: readonly (readonly string[])[];
}

/** Una tabla de valores del ejercicio. */
export interface TablaDeValores {
  readonly label: string;
  readonly nota: string;
  readonly cols: readonly (readonly [string, number])[];
  readonly filas: readonly (readonly string[])[];
  readonly pie: string;
}

/*
 * El código de contribuyente lo asigna el sistema; lo que tiene que ser
 * único es el documento. Por eso la compuerta del alta comprueba el
 * documento, no un código compuesto como en Catastro.
 */
export const DOCS: Readonly<Record<string, number>> = {
  DNI: 8,
  RUC: 11,
  'Carnet de extranjería': 12,
};

/**
 * El documento que el artboard usa para demostrar la compuerta del alta.
 *
 * **No es un dato suelto: es el DNI de `PREDIOS[1]`.** Que este «en uso» no lo decide esta
 * constante, lo decide el padron — y por eso el proxy no lo consulta para nada. Comprobar si
 * un documento esta tomado es una decision del servidor (AC8) y la respuesta esta en
 * `GET /rentas/contribuyentes`, donde este mismo numero sale como `numeroDocumento`.
 */
export const DOC_EN_USO = '44218937';

/*
 * Las seis secciones del expediente. Conservan las etiquetas del manual:
 * lo que se va son las nueve pestañas, que eran navegación, no contenido.
 */
export const PASOS: readonly PasoDelExpediente[] = [
  {
    id: 'ident',
    label: 'Identificación',
    nota: 'Quién es y cómo está calificado. La calificación decide el trato de cobranza, no el impuesto: un principal contribuyente paga lo mismo, se le persigue antes.',
    campos: [
      {
        k: 'tipoPersona',
        l: 'Tipo de persona',
        t: 'sel',
        o: ['', 'Natural', 'Jurídica', 'Sucesión indivisa', 'Sociedad conyugal'],
      },
      { k: 'apPaterno', l: 'Apellido paterno' },
      { k: 'apMaterno', l: 'Apellido materno' },
      { k: 'nombres', l: 'Nombres' },
      {
        k: 'razonSocial',
        l: 'Razón social',
        ancho: 1,
        opcional: true,
        ph: 'Solo si es persona jurídica',
      },
      { k: 'nacimiento', l: 'Fecha de nacimiento', t: 'date' },
      { k: 'sexo', l: 'Sexo', t: 'sel', o: ['', 'Masculino', 'Femenino'] },
      {
        k: 'estadoCivil',
        l: 'Estado civil',
        t: 'sel',
        o: ['', 'Soltero(a)', 'Casado(a)', 'Viudo(a)', 'Divorciado(a)', 'Conviviente'],
      },
      { k: 'conyuge', l: 'Cónyuge', opcional: true },
      {
        k: 'calificacion',
        l: 'Calificación',
        t: 'sel',
        o: [
          '',
          '001 — Principal contribuyente',
          '002 — Mediano contribuyente',
          '003 — Pequeño contribuyente',
        ],
        ayuda: 'Decide el trato de cobranza',
      },
      {
        k: 'estadoContrib',
        l: 'Estado',
        t: 'sel',
        o: ['', 'Activo', 'Inactivo', 'Baja', 'Fallecido', 'No habido'],
      },
    ],
  },
  {
    id: 'domicilio',
    label: 'Domicilio fiscal',
    nota: 'A dónde se notifica. La vía sale del catálogo vial de Catastro: si el domicilio está mal, la notificación no llega y la cobranza no avanza.',
    campos: [
      {
        k: 'tipoVia',
        l: 'Tipo de vía',
        t: 'sel',
        o: ['', 'AV — Avenida', 'CA — Calle', 'JR — Jirón', 'PS — Pasaje', 'CR — Carretera'],
      },
      { k: 'via', l: 'Nombre de la vía', ancho: 1 },
      { k: 'numero', l: 'Número' },
      { k: 'numAd', l: 'Número adicional', opcional: true },
      { k: 'habUrbana', l: 'Habilitación urbana', ancho: 1 },
      { k: 'dep', l: 'Departamento', t: 'ro' },
      { k: 'prov', l: 'Provincia', t: 'ro' },
      { k: 'dist', l: 'Distrito', t: 'ro' },
      { k: 'mz', l: 'Manzana', opcional: true },
      { k: 'lt', l: 'Lote', opcional: true },
      { k: 'telefonos', l: 'Teléfonos' },
      { k: 'email', l: 'Correo electrónico' },
      {
        k: 'notifElec',
        l: 'Notificación electrónica',
        t: 'chk',
        ph: 'Autoriza notificar al correo declarado',
      },
    ],
  },
  {
    id: 'unidades',
    label: 'Predios y vehículos',
    nota: 'Las unidades afectas de las que sale el impuesto. El código predial es el mismo de Catastro: no hay dos padrones de predios.',
    campos: [
      { k: 'nPredios', l: 'Predios en el distrito', t: 'ro' },
      {
        k: 'autovaluo',
        l: 'Autovalúo acumulado (S/)',
        t: 'ro',
        ayuda: 'Base imponible del predial: se determina por contribuyente, no por predio',
      },
      { k: 'nVehiculos', l: 'Vehículos afectos', t: 'ro' },
      { k: 'baseVeh', l: 'Base imponible vehicular (S/)', t: 'ro' },
    ],
    tabla: {
      titulo: 'Predios del contribuyente',
      min: '760px',
      accion: 'Añadir predio',
      vacioTexto:
        'Todavía no hay predios declarados. Sin unidad afecta no hay impuesto que determinar.',
      cols: [
        ['Código predial', 0],
        ['Ubicación', 0],
        ['Uso', 0],
        ['Terreno m²', 1],
        ['% prop.', 1],
        ['Autovalúo S/', 1],
      ],
      filas: [
        ['02-014-D-14-01', 'Calle Santa Rosa 116', 'Casa habitación', '210.00', '100.00', '132,196.75'],
        ['04-021-B-07-00', 'Mz. B Lt. 7 — Bellavista', 'Terreno sin construir', '184.00', '50.00', '38,420.00'],
      ],
      nota: 'El autovalúo del conjunto es la base imponible del predial: la escala progresiva se aplica a la suma, no a cada predio.',
    },
  },
  {
    id: 'beneficios',
    label: 'Beneficios',
    nota: 'La deducción de 50 UIT para pensionistas y adultos mayores exige predio único destinado a vivienda. Es la que más se solicita y la que más se deniega.',
    campos: [
      {
        k: 'tipoBen',
        l: 'Tipo de beneficio',
        t: 'sel',
        o: [
          '',
          'Pensionista — deducción 50 UIT',
          'Adulto mayor no pensionista',
          'Persona con discapacidad',
          'Inafectación',
          'Amnistía tributaria',
        ],
      },
      { k: 'benPredio', l: 'Código predial' },
      { k: 'benExp', l: 'Nº de expediente' },
      { k: 'benFecha', l: 'Fecha de solicitud', t: 'date' },
      { k: 'benRes', l: 'Nº de resolución', opcional: true, ph: 'Se emite al aprobar' },
      {
        k: 'benEstado',
        l: 'Estado',
        t: 'sel',
        o: ['', 'Vigente', 'En trámite', 'Denegado', 'Vencido'],
      },
    ],
    tabla: {
      titulo: 'Beneficios del contribuyente',
      min: '700px',
      accion: 'Solicitar beneficio',
      vacioTexto:
        'Sin beneficios registrados. Compruebe si cumple los requisitos de la deducción de 50 UIT.',
      cols: [
        ['Expediente', 0],
        ['Tipo', 0],
        ['Resolución', 0],
        ['Vigencia', 0],
        ['Deducción', 0],
        ['Estado', 0],
      ],
      filas: [
        ['2026-0281', 'Pensionista', 'RES-0412-2026-MPS', '2026 — indefinida', '50 UIT', 'Vigente'],
        ['2025-1102', 'Amnistía 2025', 'ORD-018-2025-MPS', '2025', '100 % interés', 'Vencido'],
      ],
      nota: 'Un beneficio vigente reduce la base imponible del ejercicio en curso; no borra la deuda de ejercicios anteriores.',
    },
  },
  {
    id: 'cuenta',
    label: 'Cuenta corriente',
    nota: 'Lo que debe hoy, con reajuste e interés al día. La cifra cambia cada día: no se guarda, se calcula.',
    campos: [
      { k: 'deudaTotal', l: 'Deuda al día de hoy (S/)', t: 'ro' },
      { k: 'insoluto', l: 'Insoluto (S/)', t: 'ro' },
      { k: 'interes', l: 'Interés y reajuste (S/)', t: 'ro' },
      { k: 'gastos', l: 'Gastos y costas (S/)', t: 'ro' },
    ],
    tabla: {
      titulo: 'Deuda por concepto',
      min: '760px',
      accion: 'Emitir estado de cuenta',
      vacioTexto: 'Sin deuda pendiente. Se le puede emitir constancia de no adeudo.',
      cols: [
        ['Año', 0],
        ['Concepto', 0],
        ['Cuotas', 0],
        ['Insoluto S/', 1],
        ['Interés S/', 1],
        ['Total S/', 1],
        ['Situación', 0],
      ],
      filas: [
        ['2026', 'Impuesto predial', '3 y 4', '293.72', '0.00', '293.72', 'Por vencer'],
        ['2026', 'Arbitrios municipales', '1 a 8', '291.60', '18.44', '310.04', 'Vencida'],
        ['2024', 'Impuesto predial', '1 a 4', '1,842.60', '212.44', '2,055.04', 'Vencida'],
        ['2024', 'Patrimonio vehicular', '1', '614.00', '182.44', '796.44', 'En coactiva'],
      ],
      nota: 'Lo que está en cobranza coactiva se paga igual, pero además acumula costas del procedimiento.',
    },
  },
  {
    id: 'obs',
    label: 'Observaciones',
    nota: 'Lo que hay que saber antes de atenderlo, y quién tocó qué. La bitácora no se edita: es lo que se presenta cuando alguien pregunta por una baja.',
    campos: [
      {
        k: 'obs',
        l: 'Observación',
        t: 'area',
        ancho: 1,
        opcional: true,
        ph: 'Lo que hay que saber antes de atenderlo',
      },
      { k: 'registrado', l: 'Registrado por', t: 'ro' },
      { k: 'modificado', l: 'Última modificación', t: 'ro' },
    ],
  },
];

/* Los contribuyentes del padrón. La deuda es a la fecha de hoy. */
export const PREDIOS: readonly ContribuyenteDelPadron[] = [
  {
    cod: '00000025673',
    titulo: 'Suc. Rufina Medina Medina',
    titular: 'DNI 03593174 · sucesión indivisa',
    uso: 'Sin conciliar',
    autovaluo: 'S/ 1,842.60',
    estado: 'Con deuda',
    tono: 'warn',
    valor: 1842.6,
    contexto:
      '2 predios y 1 vehículo · autovalúo acumulado S/ 170,616.75 · pequeño contribuyente · beneficio de pensionista vigente',
  },
  {
    cod: '00000003541',
    titulo: 'Castillo Pascuala, María Elena',
    titular: 'DNI 44218937 · persona natural',
    uso: 'Al día',
    autovaluo: 'S/ 591.94',
    estado: 'Al día',
    tono: 'ok',
    valor: 591.94,
    contexto:
      '2 predios y 2 vehículos · autovalúo acumulado S/ 151,406.75 · pequeño contribuyente · sin beneficios',
  },
  {
    cod: '00000006550',
    titulo: 'Díaz Madrid, Julio César',
    titular: 'DNI 02718844 · persona natural',
    uso: 'En coactiva',
    autovaluo: 'S/ 9,412.15',
    estado: 'En coactiva',
    tono: 'bad',
    valor: 9412.15,
    contexto:
      '3 predios · autovalúo acumulado S/ 412,880.00 · mediano contribuyente · expediente coactivo 2026-0418 con medida cautelar',
  },
  {
    cod: '00000006551',
    titulo: 'Noblecilla Arismendiz S.A.C.',
    titular: 'RUC 20525118447 · persona jurídica',
    uso: 'Observado',
    autovaluo: 'S/ 412.00',
    estado: 'Observado',
    tono: 'bad',
    valor: 412.0,
    contexto:
      '1 predio sin arancel de vía · sin emisión 2026: la inconsistencia impide determinar el impuesto',
  },
  {
    cod: '00000152614',
    titulo: 'Valdez Ríos, Oliver Fabián',
    titular: 'DNI 41182844 · sociedad conyugal',
    uso: 'Al día',
    autovaluo: 'S/ 0.00',
    estado: 'Al día',
    tono: 'ok',
    valor: 0,
    contexto:
      '1 predio con licencia de obra · autovalúo acumulado S/ 24,483.20 · sin deuda pendiente',
  },
];

/*
 * Las seis determinaciones. Comparten anatomía: sujeto, memoria del
 * cálculo y acto. Lo que cambia es la cuenta, no la pantalla.
 */
export const NODOS: readonly (readonly [string, string])[] = [
  ['Predial — individual', '1 contribuyente'],
  ['Predial — masivo', '62,418 cuentas'],
  ['Arbitrios municipales', '4 servicios'],
  ['Patrimonio vehicular', '3 ejercicios'],
  ['Alcabala', '1 transferencia'],
  ['Espectáculos públicos', '84 eventos'],
];

export const DETERMINACIONES: readonly DeterminacionDelPrototipo[] = [
  {
    titulo: 'Predial — individual',
    nota: 'Escala progresiva acumulativa sobre el autovalúo de todos los predios del contribuyente en el distrito, con el mínimo imponible de 0.6 % de la UIT.',
    cols: [
      ['', 0],
      ['Concepto', 0],
      ['Detalle', 0],
      ['S/', 1],
    ],
    filas: [
      ['', 'Valuo total del conjunto', '2 predios, al 100 % y al 50 %', '170,616.75'],
      ['−', 'Valuo exonerado', 'Sin beneficio aplicado este ejercicio', '0.00'],
      ['=', 'Valuo afecto', 'Base imponible del predial', '151,406.75'],
      ['×', 'Tramo 1 — hasta 15 UIT · 0.2 %', 'S/ 80,250.00 del afecto', '160.50'],
      ['×', 'Tramo 2 — de 15 a 60 UIT · 0.6 %', 'S/ 71,156.75 del afecto', '426.94'],
      ['×', 'Tramo 3 — más de 60 UIT · 1.0 %', 'S/ 0.00 del afecto', '0.00'],
      ['=', 'Impuesto insoluto anual', 'Suma de los tres tramos', '587.44'],
      ['+', 'Derecho de emisión', 'Tasa del TUPA por cuponera', '4.50'],
      ['=', 'Total a pagar', 'En 4 cuotas trimestrales de S/ 147.98', '591.94'],
    ],
  },
  {
    titulo: 'Predial — masivo',
    nota: 'Proceso de emisión anual. Recalcula el padrón completo y deja constancia de los contribuyentes observados que quedan fuera de la emisión.',
    cols: [
      ['Etapa', 0],
      ['Registros', 1],
      ['Monto S/', 1],
      ['Observados', 1],
      ['Estado', 0],
    ],
    filas: [
      ['Lectura del padrón', '62,418', '—', '0', 'Completa'],
      ['Valuación de predios', '78,204', '1,842,116,420.00', '412', 'Completa'],
      ['Determinación del impuesto', '61,884', '9,418,204.60', '534', 'Completa'],
      ['Determinación de arbitrios', '61,884', '5,884,110.20', '188', 'Completa'],
      ['Generación de cuponeras', '61,350', '—', '534', 'Con observados'],
    ],
  },
  {
    titulo: 'Arbitrios municipales',
    nota: 'Limpieza pública, parques y serenazgo. La tasa depende del uso del predio, la zona y los metros de frontis declarados en la ficha catastral.',
    cols: [
      ['Servicio', 0],
      ['Criterio de distribución', 0],
      ['Frecuencia', 0],
      ['Mensual S/', 1],
      ['Anual S/', 1],
    ],
    filas: [
      ['Limpieza pública — barrido', 'Metros lineales de frontis', 'Diaria', '8.40', '100.80'],
      ['Limpieza pública — recolección', 'Área construida y uso', 'Interdiaria', '14.20', '170.40'],
      ['Parques y jardines', 'Ubicación del predio', 'Permanente', '6.10', '73.20'],
      ['Serenazgo', 'Uso y peligrosidad de zona', 'Permanente', '11.80', '141.60'],
      ['Total del ejercicio', 'Con descuento de pronto pago', '12 cuotas', '36.45', '437.40'],
    ],
  },
  {
    titulo: 'Patrimonio vehicular',
    nota: 'El 1 % sobre la base imponible, con un mínimo del 1.5 % de la UIT, por los tres ejercicios en que el vehículo permanece afecto.',
    cols: [
      ['', 0],
      ['Concepto', 0],
      ['Detalle', 0],
      ['S/', 1],
    ],
    filas: [
      ['', 'Valor de adquisición', 'Declarado por el titular', '112,400.00'],
      ['', 'Tabla referencial MEF', 'Publicada para el año de fabricación', '112,800.00'],
      ['=', 'Base imponible', 'El mayor de los dos', '112,800.00'],
      ['×', 'Tasa', '1.0 %', '1,128.00'],
      ['=', 'Impuesto anual', 'En 4 cuotas de S/ 282.00', '1,128.00'],
      ['', 'Mínimo imponible — 1.5 % UIT', 'Comprobación: el impuesto lo supera', '80.25'],
    ],
  },
  {
    titulo: 'Alcabala',
    nota: 'El 3 % sobre el exceso de las primeras 10 UIT, tomando como base el mayor valor entre el de transferencia y el autovalúo ajustado por el IPM.',
    cols: [
      ['', 0],
      ['Concepto', 0],
      ['Detalle', 0],
      ['S/', 1],
    ],
    filas: [
      ['', 'Valor de transferencia', 'Según minuta EP-2218-2026', '95,000.00'],
      ['', 'Autovalúo ajustado por IPM', 'Índice 1.0206 sobre S/ 76,840.00', '78,420.00'],
      ['=', 'Base de cálculo', 'El mayor de los dos', '95,000.00'],
      ['−', 'Tramo inafecto — 10 UIT', 'S/ 5,350.00 × 10', '53,500.00'],
      ['=', 'Base imponible', '', '41,500.00'],
      ['×', 'Tasa', '3.0 %', '1,245.00'],
      ['=', 'Alcabala a pagar', 'Vence el 31/08/2026', '1,245.00'],
    ],
  },
  {
    titulo: 'Espectáculos públicos',
    nota: 'Grava el monto que se abona por presenciar el espectáculo. El organizador actúa como agente perceptor: retiene y entrega.',
    cols: [
      ['Expediente', 0],
      ['Organizador', 0],
      ['Espectáculo', 0],
      ['Aforo', 1],
      ['Recaudación S/', 1],
      ['Tasa', 0],
      ['Impuesto S/', 1],
    ],
    filas: [
      ['2026-0884', 'Producciones del Norte E.I.R.L.', 'Concierto de cumbia', '2,400', '84,000.00', '10 %', '8,400.00'],
      ['2026-0912', 'Asoc. Taurina Sullana', 'Corrida de toros', '1,800', '126,000.00', '10 %', '12,600.00'],
      ['2026-0918', 'Cine Plaza S.A.C.', 'Función de cine', '320', '4,800.00', '0 %', '0.00'],
    ],
  },
];

export const VAL: readonly TablaDeValores[] = [
  {
    label: 'UIT y escala progresiva',
    nota: 'La UIT del ejercicio y los tres tramos de la escala del predial. Cambiar la UIT recalcula mínimos, tramos y multas en todo el sistema.',
    cols: [
      ['Concepto', 0],
      ['Base', 0],
      ['Tasa o valor', 0],
      ['Equivalente S/', 1],
    ],
    filas: [
      ['UIT 2026', 'Aprobada por el MEF', 'S/ 5,350.00', '5,350.00'],
      ['Tramo 1 del predial', 'Hasta 15 UIT', '0.2 %', '80,250.00'],
      ['Tramo 2 del predial', 'De 15 a 60 UIT', '0.6 %', '321,000.00'],
      ['Tramo 3 del predial', 'Más de 60 UIT', '1.0 %', 'sin tope'],
      ['Mínimo imponible predial', '0.6 % de la UIT', '—', '32.10'],
      ['Mínimo imponible vehicular', '1.5 % de la UIT', '—', '80.25'],
      ['Deducción de pensionista', '50 UIT', '—', '267,500.00'],
      ['Derecho de emisión', 'Tasa del TUPA', '—', '4.50'],
    ],
    pie: 'La escala es acumulativa: cada tramo se aplica solo a la porción del autovalúo que le corresponde, no al total.',
  },
  {
    label: 'Arbitrios por servicio',
    nota: 'Tasa mensual por metro de frontis o metro construido, según el servicio, la zona y el uso del predio.',
    cols: [
      ['Servicio', 0],
      ['Zona 1', 1],
      ['Zona 2', 1],
      ['Zona 3', 1],
      ['Zona 4', 1],
      ['Criterio', 0],
    ],
    filas: [
      ['Barrido de calles', '11.20', '8.40', '6.10', '4.20', 'Metro de frontis'],
      ['Recolección de residuos', '18.60', '14.20', '10.40', '7.20', 'Metro construido'],
      ['Parques y jardines', '8.40', '6.10', '4.20', '2.80', 'Ubicación'],
      ['Serenazgo', '16.20', '11.80', '8.40', '5.60', 'Uso y peligrosidad'],
    ],
    pie: 'Los arbitrios se determinan por predio, no por contribuyente: cada uno tiene su frontis y su zona.',
  },
  {
    label: 'Intereses y reajustes',
    nota: 'Interés moratorio, reajuste por índice de precios y las tasas del fraccionamiento. Se aplican día a día sobre el insoluto vencido.',
    cols: [
      ['Concepto', 0],
      ['Vigencia', 0],
      ['Tasa mensual', 1],
      ['Tasa diaria', 1],
    ],
    filas: [
      ['Interés moratorio (TIM)', 'Desde 01/2026', '0.90 %', '0.0300 %'],
      ['Interés de fraccionamiento', 'Desde 01/2026', '0.80 %', '0.0267 %'],
      ['Reajuste por IPM', 'Trimestral', '—', '—'],
      ['Costas del procedimiento coactivo', 'Arancel vigente', '—', '—'],
    ],
    pie: 'El interés corre desde el día siguiente al vencimiento de la cuota. Una amnistía puede condonarlo, nunca el insoluto.',
  },
];

/**
 * Los valores del expediente abierto, del metodo `datos()` del componente.
 *
 * Es el expediente de `PREDIOS[0]` —Suc. Rufina Medina Medina—: `benExp` es el `2026-0281`
 * de la tabla de beneficios y `deudaTotal` (3,455.24) es la suma exacta de la columna «Total
 * S/» de la tabla de deuda. Sin este objeto, `GET /rentas/contribuyentes/{id}/ficha` no
 * tendria un solo valor capturado: `PASOS` declara los campos, no lo que llevan dentro.
 */
export const EXPEDIENTE: Readonly<Record<string, string | boolean>> = {
  tipoPersona: 'Sucesión indivisa',
  apPaterno: 'Medina',
  apMaterno: 'Medina',
  nombres: 'Rufina',
  razonSocial: '',
  nacimiento: '1948-08-30',
  sexo: 'Femenino',
  estadoCivil: 'Viudo(a)',
  conyuge: '',
  calificacion: '003 — Pequeño contribuyente',
  estadoContrib: 'Activo',
  tipoVia: 'CA — Calle',
  via: 'Santa Rosa',
  numero: '116',
  numAd: '',
  habUrbana: 'Urb. Santa Rosa — El Alto',
  dep: 'Piura',
  prov: 'Sullana',
  dist: 'Sullana',
  mz: '015',
  lt: '001',
  telefonos: '969032194',
  email: 'fruiz159@gmail.com',
  notifElec: true,
  nPredios: '2',
  autovaluo: '170,616.75',
  nVehiculos: '1',
  baseVeh: '61,400.00',
  tipoBen: 'Pensionista — deducción 50 UIT',
  benPredio: '02-014-D-14-01',
  benExp: '2026-0281',
  benFecha: '2026-03-04',
  benRes: 'RES-0412-2026-MPS',
  benEstado: 'Vigente',
  deudaTotal: '3,455.24',
  insoluto: '3,041.92',
  interes: '413.32',
  gastos: '96.00',
  obs: '',
  registrado: 'MRIOS — 12/08/2026 09:14',
  modificado: 'MRIOS — 03/07/2026 16:02',
};

/**
 * La fecha a la que esta capturado todo lo de este archivo.
 *
 * **No es «hoy», y eso es deliberado (regla 9).** Toda cifra de deuda viaja con la fecha a la
 * que esta actualizada; si el proxy pusiera la fecha del dia, un estado de cuenta de agosto de
 * 2026 diria estar calculado hoy y la pantalla ensenaria una cifra vieja con fecha nueva, que
 * es la unica manera de que la regla 9 mienta.
 *
 * Sale del propio artboard: `EXPEDIENTE.registrado` es «MRIOS — 12/08/2026 09:14».
 */
export const FECHA_DE_CAPTURA = '2026-08-12';

/** El ejercicio del artboard. `VAL[0]` publica la UIT como «UIT 2026». */
export const EJERCICIO_DE_CAPTURA = 2026;
