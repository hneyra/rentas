import type { TipoDeCampo } from '../ds/index.ts';
import type {
  BeneficioServido,
  ContactoServido,
  DeudaPorConcepto,
  FichaDelContribuyente,
  PredioServido,
} from '../datos/lecturas.ts';

/**
 * Las seis secciones del expediente del contribuyente, portadas de `PASOS` (`:974`).
 *
 * <h2>Por que esta definicion NO se importa de `datos/prototipo.ts`</h2>
 *
 * Porque `prototipo.ts` es la captura de los DATOS del artboard, y esta pantalla no puede
 * importarla: `arranque.ts` carga el proxy con un `import()` dinamico detras de una bandera
 * para que un `yarn build` de produccion no lleve dentro ni una cifra del prototipo (AC3 de
 * #4, medido en bytes). Un `import` estatico desde una seccion tiraria esa propiedad al suelo
 * en silencio — y con ella, «Rufina Medina Medina» viajaria a la municipalidad.
 *
 * Lo que hay aqui es el FORMULARIO: rotulos, tipos de control, opciones de cada desplegable y
 * las notas de cabecera. Ni un valor. Que siga siendo el del artboard lo comprueba
 * `verificaciones/secciones-del-artboard.test.ts` leyendo el `.dc.html`.
 *
 * <h2>Lo que el contrato publica compuesto, y por que aqui queda vacio</h2>
 *
 * `GET /rentas/contribuyentes/{id}/ficha` publica el nombre y el domicilio **compuestos**
 * —`nombreRazonSocial: 'Suc. Rufina Medina Medina'`, `direccion: 'Calle Santa Rosa 116'`— y
 * este formulario los pide por partes: apellido paterno, apellido materno, nombres; tipo de
 * via, nombre de via, numero. **Partirlos no se puede**: «Suc. Rufina Medina Medina» no dice
 * cual de las dos «Medina» es el apellido paterno, y «Calle Santa Rosa 116» ya perdio el
 * «CA — » del catalogo vial. Adivinarlo escribiria en el expediente un apellido que nadie
 * declaro, que es peor que dejarlo vacio.
 *
 * Asi que esos campos quedan vacios cuando se abre un contribuyente, y se escriben cuando se
 * crea uno. Los que el contrato SI publica llegan llenos.
 */
export interface CampoDelExpediente {
  /** Clave del valor. La misma que usa el artboard, para poder compararlas. */
  readonly clave: string;
  readonly etiqueta: string;
  readonly tipo: TipoDeCampo;
  readonly opciones?: readonly string[];
  /** Ocupa la fila entera de la rejilla. */
  readonly ancho?: boolean;
  readonly opcional?: boolean;
  readonly ph?: string;
  readonly ayuda?: string;
}

/** La tabla que cuelga de una seccion del expediente. */
export interface TablaDelExpediente {
  readonly titulo: string;
  readonly accion: string;
  readonly vacioTexto: string;
  /** Cabeceras: rotulo y si va alineada a la derecha. */
  readonly columnas: readonly (readonly [string, boolean])[];
  readonly nota: string;
}

/** Una de las seis secciones. */
export interface SeccionDelExpediente {
  readonly id: string;
  readonly rotulo: string;
  readonly nota: string;
  readonly campos: readonly CampoDelExpediente[];
  readonly tabla?: TablaDelExpediente;
}

export const SECCIONES_DEL_EXPEDIENTE: readonly SeccionDelExpediente[] = [
  {
    id: 'ident',
    rotulo: 'Identificación',
    nota: 'Quién es y cómo está calificado. La calificación decide el trato de cobranza, no el impuesto: un principal contribuyente paga lo mismo, se le persigue antes.',
    campos: [
      {
        clave: 'tipoPersona',
        etiqueta: 'Tipo de persona',
        tipo: 'sel',
        opciones: ['', 'Natural', 'Jurídica', 'Sucesión indivisa', 'Sociedad conyugal'],
      },
      { clave: 'apPaterno', etiqueta: 'Apellido paterno', tipo: 'text' },
      { clave: 'apMaterno', etiqueta: 'Apellido materno', tipo: 'text' },
      { clave: 'nombres', etiqueta: 'Nombres', tipo: 'text' },
      {
        clave: 'razonSocial',
        etiqueta: 'Razón social',
        tipo: 'text',
        ancho: true,
        opcional: true,
        ph: 'Solo si es persona jurídica',
      },
      { clave: 'nacimiento', etiqueta: 'Fecha de nacimiento', tipo: 'date' },
      { clave: 'sexo', etiqueta: 'Sexo', tipo: 'sel', opciones: ['', 'Masculino', 'Femenino'] },
      {
        clave: 'estadoCivil',
        etiqueta: 'Estado civil',
        tipo: 'sel',
        opciones: ['', 'Soltero(a)', 'Casado(a)', 'Viudo(a)', 'Divorciado(a)', 'Conviviente'],
      },
      { clave: 'conyuge', etiqueta: 'Cónyuge', tipo: 'text', opcional: true },
      {
        clave: 'calificacion',
        etiqueta: 'Calificación',
        tipo: 'sel',
        opciones: [
          '',
          '001 — Principal contribuyente',
          '002 — Mediano contribuyente',
          '003 — Pequeño contribuyente',
        ],
        ayuda: 'Decide el trato de cobranza',
      },
      {
        clave: 'estadoContrib',
        etiqueta: 'Estado',
        tipo: 'sel',
        opciones: ['', 'Activo', 'Inactivo', 'Baja', 'Fallecido', 'No habido'],
      },
    ],
  },
  {
    id: 'domicilio',
    rotulo: 'Domicilio fiscal',
    nota: 'A dónde se notifica. La vía sale del catálogo vial de Catastro: si el domicilio está mal, la notificación no llega y la cobranza no avanza.',
    campos: [
      {
        clave: 'tipoVia',
        etiqueta: 'Tipo de vía',
        tipo: 'sel',
        opciones: ['', 'AV — Avenida', 'CA — Calle', 'JR — Jirón', 'PS — Pasaje', 'CR — Carretera'],
      },
      { clave: 'via', etiqueta: 'Nombre de la vía', tipo: 'text', ancho: true },
      { clave: 'numero', etiqueta: 'Número', tipo: 'text' },
      { clave: 'numAd', etiqueta: 'Número adicional', tipo: 'text', opcional: true },
      { clave: 'habUrbana', etiqueta: 'Habilitación urbana', tipo: 'text', ancho: true },
      { clave: 'dep', etiqueta: 'Departamento', tipo: 'ro' },
      { clave: 'prov', etiqueta: 'Provincia', tipo: 'ro' },
      { clave: 'dist', etiqueta: 'Distrito', tipo: 'ro' },
      { clave: 'mz', etiqueta: 'Manzana', tipo: 'text', opcional: true },
      { clave: 'lt', etiqueta: 'Lote', tipo: 'text', opcional: true },
      { clave: 'telefonos', etiqueta: 'Teléfonos', tipo: 'text' },
      { clave: 'email', etiqueta: 'Correo electrónico', tipo: 'text' },
      {
        clave: 'notifElec',
        etiqueta: 'Notificación electrónica',
        tipo: 'chk',
        ph: 'Autoriza notificar al correo declarado',
      },
    ],
  },
  {
    id: 'unidades',
    rotulo: 'Predios y vehículos',
    nota: 'Las unidades afectas de las que sale el impuesto. El código predial es el mismo de Catastro: no hay dos padrones de predios.',
    campos: [
      { clave: 'nPredios', etiqueta: 'Predios en el distrito', tipo: 'ro' },
      {
        clave: 'autovaluo',
        etiqueta: 'Autovalúo acumulado (S/)',
        tipo: 'ro',
        ayuda: 'Base imponible del predial: se determina por contribuyente, no por predio',
      },
      { clave: 'nVehiculos', etiqueta: 'Vehículos afectos', tipo: 'ro' },
      { clave: 'baseVeh', etiqueta: 'Base imponible vehicular (S/)', tipo: 'ro' },
    ],
    tabla: {
      titulo: 'Predios del contribuyente',
      accion: 'Añadir predio',
      vacioTexto:
        'Todavía no hay predios declarados. Sin unidad afecta no hay impuesto que determinar.',
      columnas: [
        ['Código predial', false],
        ['Ubicación', false],
        ['Uso', false],
        ['Terreno m²', true],
        ['% prop.', true],
        ['Autovalúo S/', true],
      ],
      nota: 'El autovalúo del conjunto es la base imponible del predial: la escala progresiva se aplica a la suma, no a cada predio.',
    },
  },
  {
    id: 'beneficios',
    rotulo: 'Beneficios',
    nota: 'La deducción de 50 UIT para pensionistas y adultos mayores exige predio único destinado a vivienda. Es la que más se solicita y la que más se deniega.',
    campos: [
      {
        clave: 'tipoBen',
        etiqueta: 'Tipo de beneficio',
        tipo: 'sel',
        opciones: [
          '',
          'Pensionista — deducción 50 UIT',
          'Adulto mayor no pensionista',
          'Persona con discapacidad',
          'Inafectación',
          'Amnistía tributaria',
        ],
      },
      { clave: 'benPredio', etiqueta: 'Código predial', tipo: 'text' },
      { clave: 'benExp', etiqueta: 'Nº de expediente', tipo: 'text' },
      { clave: 'benFecha', etiqueta: 'Fecha de solicitud', tipo: 'date' },
      {
        clave: 'benRes',
        etiqueta: 'Nº de resolución',
        tipo: 'text',
        opcional: true,
        ph: 'Se emite al aprobar',
      },
      {
        clave: 'benEstado',
        etiqueta: 'Estado',
        tipo: 'sel',
        opciones: ['', 'Vigente', 'En trámite', 'Denegado', 'Vencido'],
      },
    ],
    tabla: {
      titulo: 'Beneficios del contribuyente',
      accion: 'Solicitar beneficio',
      vacioTexto:
        'Sin beneficios registrados. Compruebe si cumple los requisitos de la deducción de 50 UIT.',
      columnas: [
        ['Expediente', false],
        ['Tipo', false],
        ['Resolución', false],
        ['Vigencia', false],
        ['Deducción', false],
        ['Estado', false],
      ],
      nota: 'Un beneficio vigente reduce la base imponible del ejercicio en curso; no borra la deuda de ejercicios anteriores.',
    },
  },
  {
    id: 'cuenta',
    rotulo: 'Cuenta corriente',
    nota: 'Lo que debe hoy, con reajuste e interés al día. La cifra cambia cada día: no se guarda, se calcula.',
    campos: [
      { clave: 'deudaTotal', etiqueta: 'Deuda al día de hoy (S/)', tipo: 'ro' },
      { clave: 'insoluto', etiqueta: 'Insoluto (S/)', tipo: 'ro' },
      { clave: 'interes', etiqueta: 'Interés y reajuste (S/)', tipo: 'ro' },
      { clave: 'gastos', etiqueta: 'Gastos y costas (S/)', tipo: 'ro' },
    ],
    tabla: {
      titulo: 'Deuda por concepto',
      accion: 'Emitir estado de cuenta',
      vacioTexto: 'Sin deuda pendiente. Se le puede emitir constancia de no adeudo.',
      columnas: [
        ['Año', false],
        ['Concepto', false],
        ['Cuotas', false],
        ['Insoluto S/', true],
        ['Interés S/', true],
        ['Total S/', true],
        ['Situación', false],
      ],
      nota: 'Lo que está en cobranza coactiva se paga igual, pero además acumula costas del procedimiento.',
    },
  },
  {
    id: 'obs',
    rotulo: 'Observaciones',
    nota: 'Lo que hay que saber antes de atenderlo, y quién tocó qué. La bitácora no se edita: es lo que se presenta cuando alguien pregunta por una baja.',
    campos: [
      {
        clave: 'obs',
        etiqueta: 'Observación',
        tipo: 'area',
        ancho: true,
        opcional: true,
        ph: 'Lo que hay que saber antes de atenderlo',
      },
      { clave: 'registrado', etiqueta: 'Registrado por', tipo: 'ro' },
      { clave: 'modificado', etiqueta: 'Última modificación', tipo: 'ro' },
    ],
  },
];

/** Lo que el expediente ha leido del backend, para poner cada campo en su sitio. */
export interface LoLeidoDelExpediente {
  readonly ficha: FichaDelContribuyente | null;
  readonly predios: readonly PredioServido[] | null;
  readonly beneficios: readonly BeneficioServido[] | null;
  readonly deuda: readonly DeudaPorConcepto[] | null;
}

/** El valor declarado de un contacto de ese tipo, o cadena vacia. */
function contacto(contactos: readonly ContactoServido[] | undefined, tipo: string): string {
  return contactos?.find((uno) => uno.tipo === tipo)?.valor ?? '';
}

/**
 * El valor de cada campo del formulario, tomado de lo que el backend publica.
 *
 * **Solo lo que publica.** Un campo que no este en esta tabla queda vacio, y su ausencia esta
 * razonada en el javadoc del archivo: o el contrato lo publica compuesto y no se puede partir,
 * o no lo publica ninguna operacion. Ninguno se compone aqui a base de suponer.
 */
export function valoresDelExpediente(leido: LoLeidoDelExpediente): Readonly<Record<string, string>> {
  const ficha = leido.ficha;
  if (ficha === null) {
    return {};
  }
  const beneficio = leido.beneficios?.[0];

  return {
    tipoPersona: ficha.contribuyente.tipoPersona,
    nacimiento: ficha.datosPersonales.fechaNacimiento,
    estadoCivil: ficha.datosPersonales.estadoCivil,
    calificacion: ficha.contribuyente.condicionEspecial ?? '',
    estadoContrib: ficha.contribuyente.activo ? 'Activo' : 'Baja',
    telefonos: contacto(ficha.contactos, 'TELEFONO'),
    email: contacto(ficha.contactos, 'CORREO'),
    // El contrato publica el ubigeo —«200601»— y no los tres nombres. Traducirlo pide el
    // catalogo de ubigeos, que es de otra operacion y de otro sistema.
    nPredios: leido.predios === null ? '' : String(leido.predios.length),
    tipoBen: beneficio?.tipo ?? '',
    // «Nº de resolución» es el documento que concede el beneficio, y eso SI se publica. El
    // «Nº de expediente» y la «Fecha de solicitud» son del tramite, y el contrato no los trae:
    // `documentoOrigen` es la resolucion, no la solicitud, y `vigenciaDesde` es desde cuando
    // rige, no cuando se pidio. Ponerlos ahi seria escribir una fecha de solicitud inventada.
    benRes: beneficio?.documentoOrigen ?? '',
  };
}
