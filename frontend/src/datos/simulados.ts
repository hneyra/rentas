/**
 * Lo que el proxy se INVENTA, apartado de lo que captura.
 *
 * <h2>La regla de este archivo</h2>
 *
 * El artboard no trae todos los campos que el backend publica. Un `predioId` numerico, el
 * identificador del conjunto sellado de normativa o el vencimiento de una cuota no estan en
 * ninguna figura del prototipo, y sin ellos la forma que sirve el proxy no seria la forma que
 * el backend publica — que es lo unico que hace util a este proxy.
 *
 * Asi que se inventan, y **cada invencion nombra la operacion que la sustituira**. Si no se
 * puede nombrar, no pertenece aqui: un valor inventado que no tiene operacion que lo reemplace
 * no es un hueco temporal, es una decision de producto tomada en el frontend — exactamente lo
 * que AC8 prohibe. `simulados.test.ts` comprueba que la operacion de cada entrada existe en
 * `docs/50-api/formas-de-la-api.json`, asi que la regla no depende de que nadie la recuerde.
 *
 * <h2>Por que no viven dentro de `prototipo.ts`</h2>
 *
 * Porque dentro serian indistinguibles de una captura. Una cifra en `prototipo.ts` se puede
 * buscar en `RentasV6.dc.html` y encontrarla igual; una de aqui, no — y el dia que el backend
 * conecte, lo que hay que revisar es exactamente esta lista y no las 600 lineas de la otra.
 *
 * <h2>Lo que NO esta aqui, y por que</h2>
 *
 * Nada que sea una decision de comportamiento. El proxy no filtra, no ordena, no pagina, no
 * valida y no persiste (AC8): fingir el resultado de `?uso=Comercio` no seria inventar un
 * valor, seria inventar una regla del servidor y construir la interfaz encima de ella.
 */

/** Una invencion del proxy, con la operacion que se la llevara por delante. */
export interface Simulado {
  /** Identificador estable. Es como lo pide `simulado()`, y como lo busca su prueba. */
  readonly clave: string;
  /** El valor que el proxy sirve mientras no hay backend. */
  readonly valor: string | number | boolean | readonly unknown[];
  /**
   * La operacion del backend que publicara este dato de verdad.
   *
   * Tiene que ser una clave de `docs/50-api/formas-de-la-api.json`: si el dato no lo publica
   * ninguna operacion, no hay backend que pueda sustituirlo y la invencion es permanente.
   */
  readonly operacion: string;
  /** Por que el prototipo no lo trae. Una linea, para quien venga a borrar la entrada. */
  readonly porQue: string;
}

export const SIMULADOS: readonly Simulado[] = [
  // ── Identificadores tecnicos ────────────────────────────────────────────────────────────
  // El artboard es una pantalla: ensena codigos de negocio —codigo predial, codigo de
  // contribuyente, numero de expediente— y no las claves primarias que el backend publica al
  // lado de cada uno. Sin ellas, la forma no cuadra; con ellas, no significan nada hasta que
  // el backend las asigne.
  {
    clave: 'predioId.deLaFicha',
    valor: [4101, 4102],
    operacion: 'GET /rentas/predios',
    porQue:
      'La ficha del artboard identifica el predio por su codigo de referencia catastral. El identificador numerico lo asigna catastro y lo proyecta rentas: hasta que la operacion conteste, un numero cualquiera.',
  },
  {
    clave: 'beneficioId',
    valor: [8801, 8802],
    operacion: 'GET /rentas/beneficios',
    porQue: 'La tabla de beneficios del artboard se identifica por expediente, no por id.',
  },
  {
    clave: 'arbitrioId',
    valor: [9001, 9002, 9003, 9004],
    operacion: 'GET /rentas/arbitrios',
    porQue: 'La tabla de arbitrios del artboard se identifica por servicio, no por id.',
  },
  {
    clave: 'domicilioId',
    valor: 7701,
    operacion: 'GET /rentas/contribuyentes/{id}/ficha',
    porQue: 'El expediente del artboard dibuja el domicilio, no su identificador.',
  },
  {
    clave: 'contactoId',
    valor: [7801, 7802],
    operacion: 'GET /rentas/contribuyentes/{id}/ficha',
    porQue: 'Gemelo del anterior, para el telefono y el correo declarados.',
  },
  {
    clave: 'determinacionId',
    valor: 30412,
    operacion: 'POST /rentas/predial/calculo-individual',
    porQue: 'La memoria de calculo del artboard no lleva el numero del acto que la respalda.',
  },
  {
    clave: 'vehicularId',
    valor: [30501, 30502, 30503],
    operacion: 'POST /rentas/vehicular/calculo',
    porQue: 'Uno por cada ejercicio afecto; la memoria vehicular del artboard es una sola.',
  },
  {
    clave: 'alcabalaId',
    valor: 30601,
    operacion: 'POST /rentas/alcabala',
    porQue: 'La memoria de alcabala del artboard cita la minuta, no el acto de determinacion.',
  },
  {
    clave: 'espectaculoId',
    valor: 30701,
    operacion: 'POST /rentas/espectaculos',
    porQue: 'La tabla de espectaculos se identifica por expediente, no por id.',
  },
  {
    clave: 'corridaId',
    valor: 118,
    operacion: 'GET /rentas/predial/corridas/ultima',
    porQue: 'El artboard ensena las etapas de la corrida, no su identificador.',
  },

  // ── El conjunto sellado de normativa ────────────────────────────────────────────────────
  // Es de `normativa`, no de aqui (ADR-0025), y llega a rentas por su copia local sellada. El
  // artboard ensena las cifras ya resueltas —UIT, tramos, minimos— y no de que conjunto salen,
  // que es justo lo que hace reproducible un recalculo diez anos despues.
  {
    clave: 'conjuntoId',
    valor: 3,
    operacion: 'GET /seguridad/parametros/ejercicios/{ejercicio}',
    porQue:
      'El artboard ensena la UIT y los tramos ya resueltos, no el conjunto sellado del que salen.',
  },
  {
    clave: 'conjunto',
    valor: 'Conjunto 2026 sellado',
    operacion: 'GET /seguridad/parametros/ejercicios/{ejercicio}',
    porQue: 'Gemelo del anterior: el nombre legible del conjunto tampoco aparece en el artboard.',
  },
  {
    clave: 'conjuntoSellado',
    valor: true,
    operacion: 'GET /seguridad/parametros/ejercicios/{ejercicio}',
    porQue:
      'La seccion «Valores» del artboard lleva la pastilla «Solo lectura» y no dice por que lo es. El motivo es el sello: un conjunto sellado no se edita, se sustituye por otro (ADR-0025).',
  },
  {
    clave: 'versionDelConjunto',
    valor: 1,
    operacion: 'GET /seguridad/parametros/ejercicios/{ejercicio}',
    porQue:
      'La version del conjunto es lo que hace reproducible un recalculo diez anos despues, y el artboard ensena las cifras sin decir de que version salen.',
  },

  // ── Cronograma y modalidad ──────────────────────────────────────────────────────────────
  {
    clave: 'vencimientosDeLasCuotas',
    valor: ['2026-02-27', '2026-05-29', '2026-08-31', '2026-11-30'],
    operacion: 'POST /rentas/predial/calculo-individual',
    porQue:
      'El artboard dice «4 cuotas trimestrales de S/ 147.98» y no en que fecha vence cada una. El cronograma lo fija la ordenanza del ejercicio (D-02b), no esta pantalla.',
  },
  {
    clave: 'modalidad',
    valor: 'Fraccionada',
    operacion: 'POST /rentas/predial/calculo-individual',
    porQue: 'Se deduce de que haya cuatro cuotas, pero el artboard no nombra la modalidad.',
  },

  // ── Campos del padron y de la ficha que el artboard no muestra ──────────────────────────
  {
    clave: 'tipoDelPredio',
    valor: ['Urbano', 'Urbano'],
    operacion: 'GET /rentas/predios',
    porQue:
      'El artboard ensena el USO del predio —«Casa habitacion»—, que no es su tipo. Urbano o rustico lo dice catastro.',
  },
  {
    clave: 'sectorDelPredio',
    valor: ['01', '04'],
    operacion: 'GET /rentas/predios',
    porQue:
      'El sector no aparece en la tabla de predios del expediente. Los dos valores se toman del primer par de posiciones del codigo predial, que es de donde el artboard los sacaria.',
  },
  {
    clave: 'condicionDelPredio',
    valor: ['Propietario unico', 'Copropietario'],
    operacion: 'GET /rentas/predios',
    porQue:
      'El artboard ensena el % de propiedad (100 y 50) y no la condicion. Donde se aplica ese porcentaje sigue abierto en D-21.',
  },
  {
    clave: 'baseLegalDelBeneficio',
    valor: [
      'Art. 19 del TUO de la Ley de Tributacion Municipal',
      'Ordenanza Municipal de amnistia del ejercicio 2025',
    ],
    operacion: 'GET /rentas/beneficios',
    porQue:
      'La tabla del artboard trae la RESOLUCION que concede el beneficio, que es su documento de origen; la norma que lo ampara no sale.',
  },
  {
    clave: 'tributoDelBeneficio',
    valor: ['PREDIAL', 'PREDIAL'],
    operacion: 'GET /rentas/beneficios',
    porQue: 'La tabla del artboard no dice a que tributo alcanza cada beneficio.',
  },

  // ── Las dos partidas de la deuda que la tabla del artboard no separa ────────────────────
  {
    clave: 'reajusteDeLaDeuda',
    valor: '0.00',
    operacion: 'GET /consultas/deuda',
    porQue:
      'La tabla del artboard tiene UNA columna «Interes S/» y su nota la llama «reajuste e interes». El backend las publica separadas, asi que el reajuste va a cero y el interes se lleva la columna entera: repartirlo seria inventar el reparto.',
  },
  {
    clave: 'gastoDeLaDeuda',
    valor: '0.00',
    operacion: 'GET /consultas/deuda',
    porQue:
      'El expediente del artboard lleva S/ 96.00 de «Gastos y costas» como total del contribuyente, y su tabla no los reparte por fila. Sumarlos a la fila coactiva descuadraria la deuda total capturada (3,455.24), asi que van a cero y la cifra del expediente se queda donde el artboard la puso.',
  },

  // ── El vehiculo de la memoria vehicular ─────────────────────────────────────────────────
  {
    clave: 'vehiculoId',
    valor: 6201,
    operacion: 'POST /rentas/vehicular/calculo',
    porQue:
      'La memoria vehicular del artboard calcula sobre un valor de adquisicion y una tabla del MEF, y no nombra el vehiculo.',
  },
  {
    clave: 'placaDelVehiculo',
    valor: 'T2R-418',
    operacion: 'POST /rentas/vehicular/calculo',
    porQue:
      'Gemelo del anterior: la determinacion que devuelve el backend lleva la placa, y el artboard no la ensena en ninguna de las seis memorias.',
  },

  // ── El domicilio fiscal ─────────────────────────────────────────────────────────────────
  {
    clave: 'ubigeo',
    valor: '200601',
    operacion: 'GET /rentas/contribuyentes/{id}/ficha',
    porQue:
      'El expediente del artboard ensena departamento, provincia y distrito por su nombre —Piura, Sullana, Sullana—; el codigo de ubigeo con que el backend los publica no sale.',
  },

  // ── El panel del modulo (F-5) ───────────────────────────────────────────────────────────
  {
    clave: 'calculadoEnDelPanel',
    valor: '2026-08-31T23:59:00',
    operacion: 'GET /indicadores/recaudacion',
    porQue:
      'El panel del artboard dice a que FECHA estan sus cifras —«al 31 de agosto»— y no a que hora se calcularon. El backend publica las dos: la fecha de corte y el instante de la corrida.',
  },
  {
    clave: 'moduloDelFrente',
    valor: 'Rentas · Registro',
    operacion: 'GET /indicadores/trabajo-parado',
    porQue:
      'El contrato dice de que modulo es cada frente parado. El artboard no lo escribe: dibuja los tres dentro del panel de Rentas y los tres enlazan al padron.',
  },

  // ── La bitacora que el panel ensena como «Actividad reciente» ───────────────────────────
  {
    clave: 'auditoriaId',
    valor: [50401, 50402, 50403, 50404],
    operacion: 'GET /seguridad/auditoria',
    porQue: 'La actividad del artboard se identifica por el contribuyente que toco, no por id.',
  },
  {
    clave: 'tablaDeLaBitacora',
    valor: ['determinacion', 'contribuyente', 'deuda', 'corrida_predial'],
    operacion: 'GET /seguridad/auditoria',
    porQue:
      'La bitacora del backend guarda que TABLA se toco. El artboard escribe el tipo de acto —«Determinado», «Alta», «Baja», «Observado»—, que es otra cosa: uno es donde paso y el otro que paso.',
  },
  {
    clave: 'usuarioDeLaBitacora',
    valor: 'MRIOS',
    operacion: 'GET /seguridad/auditoria',
    porQue:
      'Las cuatro filas de actividad del artboard no dicen quien las hizo. El usuario sale del unico que el artboard nombra, en «Registrado por» del expediente.',
  },
  {
    clave: 'instanteDeLaBitacora',
    valor: [
      '2026-08-31T21:59:00',
      '2026-08-30T16:20:00',
      '2026-08-30T11:05:00',
      '2026-08-28T09:40:00',
    ],
    operacion: 'GET /seguridad/auditoria',
    porQue:
      'El artboard escribe una DISTANCIA —«hace 2 h», «ayer», «hace 3 días»— y la bitacora guarda un instante. Los cuatro se colocan a esa distancia de la fecha de corte del panel, que es la unica referencia que el artboard da.',
  },

  // ── El unico expediente coactivo del padron ─────────────────────────────────────────────
  {
    clave: 'costasDelExpedienteCoactivo',
    valor: '0.00',
    operacion: 'GET /coactiva/deudas',
    porQue:
      'El artboard da UN importe para el contribuyente en coactiva (S/ 9,412.15) y el contrato lo publica repartido en deuda y costas. Las costas van a cero y la deuda se lleva el total: repartirlo seria inventar el reparto, y sumarle costas descuadraria la cifra capturada.',
  },
];

/** Indice por clave, para que `simulado()` no recorra la lista en cada llamada. */
const POR_CLAVE = new Map(SIMULADOS.map((s) => [s.clave, s]));

/**
 * El valor inventado de esa clave.
 *
 * Revienta si la clave no esta declarada: un valor inventado que no pasa por esta lista es
 * exactamente lo que el archivo existe para impedir, y fallar aqui es mas barato que
 * descubrirlo cuando el backend conecte y nadie sepa de donde salio la cifra.
 */
export function simulado<T>(clave: string): T {
  const entrada = POR_CLAVE.get(clave);
  if (entrada === undefined) {
    throw new Error(
      `El proxy pidio el simulado «${clave}», que no esta declarado en simulados.ts.\n` +
        'Todo lo que el proxy se inventa se declara ahi, con la operacion que lo sustituira.\n' +
        'Si no puedes nombrar esa operacion, el valor no pertenece al proxy.',
    );
  }
  return entrada.valor as T;
}
