import { solicitar } from '../api/cliente.ts';

/**
 * Lo que las pantallas leen del backend, con la forma que el backend publica.
 *
 * <h2>Por que existe este archivo y no un `solicitar()` suelto en cada pantalla</h2>
 *
 * Porque **la forma se declara una vez**. `docs/50-api/formas-de-la-api.json` dice que publica
 * cada operacion, y `src/datos/formas.test.ts` comprueba campo a campo que el proxy sirve eso
 * mismo; lo que faltaba era que la pantalla lo LEYERA con esos nombres. Un `solicitar<any>` en
 * cada seccion volveria a abrir la puerta que #4 cerro: la pantalla se escribiria contra los
 * campos que alguien recuerde, y el desajuste no aparece hasta que el backend contesta.
 *
 * <h2>Los importes son texto, y ninguno viaja sin su fecha</h2>
 *
 * Regla 1 y regla 9. `ImporteConFecha` es la forma con que el backend publica todo lo que es
 * dinero —`{ importe, actualizadoA }`— y aqui se declara una sola vez para que ninguna pantalla
 * tenga que acordarse. Los que el contrato publica como texto plano —`totalS` de coactiva—
 * llevan su fecha al lado, en `aLaFecha`, y la pantalla los junta al dibujarlos.
 *
 * <h2>Lo que NO hace</h2>
 *
 * No filtra, no ordena y no compone. Filtrar y ordenar es de la pantalla mientras el backend no
 * los admita (el proxy ignora la cadena de consulta a proposito, AC8 de #4), y componer dos
 * respuestas en una es una decision de pantalla que se ve mejor donde se dibuja.
 */

/** Un importe del backend: texto decimal y la fecha a la que esta actualizado. */
export interface ImporteConFecha {
  readonly importe: string;
  readonly actualizadoA: string;
}

/** El envoltorio de paginacion del backend. `tamano`, sin enie. */
export interface Paginado<T> {
  readonly contenido: readonly T[];
  readonly pagina: number;
  readonly tamano: number;
  readonly totalElementos: number;
  readonly totalPaginas: number;
  readonly hayMas: boolean;
}

// ── El padron ───────────────────────────────────────────────────────────────────────────────

/**
 * Un contribuyente del padron, tal como `GET /rentas/contribuyentes` lo publica.
 *
 * **Son ocho campos, y no hay un noveno.** Ni el estado de cobranza ni la deuda estan aqui, que
 * son las dos cosas sobre las que el artboard construye la fila de la lista. No es un olvido de
 * este archivo: es lo que declara `docs/50-api/formas-de-la-api.json`, y lo comprueba
 * `verificaciones/secciones-del-artboard.test.ts` leyendo el propio archivo de formas.
 */
export interface ContribuyenteDelPadron {
  readonly id: number;
  readonly codigo: string;
  readonly tipoDocumento: string;
  readonly numeroDocumento: string;
  readonly tipoPersona: string;
  readonly nombreRazonSocial: string;
  readonly condicionEspecial: string | null;
  readonly activo: boolean;
}

/** Un expediente coactivo abierto, de `GET /coactiva/deudas`. */
export interface DeudaEnCoactiva {
  readonly expediente: string;
  readonly ano: number;
  readonly codContribuyente: string;
  readonly contribuyente: string;
  readonly deudaS: string;
  readonly costasS: string;
  readonly totalS: string;
  readonly aLaFecha: string;
  readonly estado: string;
}

/** Un contribuyente que la emision masiva dejo fuera, con su motivo. */
export interface ObservadoDeLaCorrida {
  readonly codContribuyente: string;
  readonly nombre: string;
  readonly motivo: string;
}

/** Una etapa de la emision masiva, con lo que dejo fuera. */
export interface EtapaDeLaCorrida {
  readonly etapa: string;
  readonly registros: number;
  /**
   * Lo emitido en esa etapa. **Cadena vacia donde la etapa no mueve dinero**, y no un cero:
   * «no se emitio nada» y «esta etapa no emite» no son lo mismo, y el artboard escribe ahi un
   * guion. La pantalla lo dibuja como guion; sumarlo como cero seria decir otra cosa.
   */
  readonly monto: string;
  readonly observados: number;
  readonly estado: string;
}

/** La ultima corrida de emision del predial. */
export interface CorridaDelPredial {
  readonly id: number;
  readonly ejercicio: string;
  readonly alcance: string;
  readonly fechaCalculo: string;
  readonly observados: number;
  readonly etapas: readonly EtapaDeLaCorrida[];
}

// ── Las determinaciones ─────────────────────────────────────────────────────────────────────

/**
 * Un tramo de la escala progresiva **ya aplicado**, de `POST /rentas/predial/calculo-individual`.
 *
 * La alicuota y el limite superior salen del conjunto sellado de `normativa` (ADR-0025) y la
 * porcion gravada y el aporte, del calculo. Ninguno de los cuatro se recompone aqui: la escala
 * es una regla tributaria y las reglas tributarias son del backend (regla 6).
 *
 * `limiteSuperior` es nulo en el ultimo tramo, que no tiene tope.
 */
export interface TramoAplicado {
  readonly orden: number;
  readonly limiteSuperior: string | null;
  readonly alicuota: string;
  readonly porcionGravada: string;
  readonly aporte: string;
}

/** Un predio que entra en la base del predial. */
export interface PredioDeLaBase {
  readonly predioId: number;
  readonly codigoPredial: string;
  readonly ubicacion: string;
  readonly uso: string;
  readonly porcentajePropiedad: string;
  readonly autovaluo: string;
}

/** Una cuota del cronograma. */
export interface CuotaDeterminada {
  readonly numero: number;
  readonly vencimiento: string;
  readonly importe: string;
}

/**
 * La memoria del predial de un contribuyente, de `POST /rentas/predial/calculo-individual`.
 *
 * **Los tres totales viajan juntos y los tres se dibujan** —insoluto, derecho de emision y
 * total—, pero la pantalla no los suma: los pide. Que cuadren con los tramos que ella misma
 * ensena lo comprueba `secciones/determinacion.ts`, y por que se comprueba en vez de calcular
 * esta escrito ahi.
 */
export interface DeterminacionIndividual {
  readonly ejercicio: string;
  readonly codContribuyente: string;
  readonly sujeto: string;
  readonly conjunto: string;
  readonly fechaCalculo: string;
  readonly predios: readonly PredioDeLaBase[];
  readonly valuoTotal: string;
  readonly valuoExonerado: string;
  readonly valuoAfecto: string;
  readonly uit: string;
  readonly tramos: readonly TramoAplicado[];
  readonly minimoImponible: string;
  readonly impuestoInsoluto: string;
  readonly derechoDeEmision: string;
  readonly totalAPagar: string;
  readonly modalidad: string;
  readonly cuotas: readonly CuotaDeterminada[];
  /** La nota de la memoria y el rotulo de cada tramo, tal como el backend los publica. */
  readonly reglasAplicadas: readonly string[];
}

/** La emision masiva del predial, de `POST /rentas/predial/calculo-masivo`. */
export interface CorridaMasiva {
  readonly ejercicio: string;
  readonly alcance: string;
  readonly conjunto: string;
  readonly fechaCalculo: string;
  readonly etapas: readonly EtapaDeLaCorrida[];
  readonly observados: readonly ObservadoDeLaCorrida[];
}

/** Un ejercicio afecto del vehiculo. */
export interface EjercicioVehicular {
  readonly ejercicio: string;
  readonly placa: string;
  readonly baseImponible: string;
  readonly montoDeterminado: string;
}

/** La memoria vehicular, de `POST /rentas/vehicular/calculo`. */
export interface DeterminacionVehicular {
  readonly fechaCalculo: string;
  readonly conjunto: string;
  readonly alicuota: string;
  readonly minimoImponible: string;
  readonly determinaciones: readonly EjercicioVehicular[];
}

/**
 * La alcabala de una transferencia, de `POST /rentas/alcabala`.
 *
 * **Cuatro campos, y ninguno es una fecha.** No es un olvido de este archivo: es lo que
 * declara `docs/50-api/formas-de-la-api.json`, y es lo que impide dibujar sus dos importes
 * (regla 9). Medido y razonado en `secciones/determinacion.ts`.
 */
export interface DeterminacionDeAlcabala {
  readonly id: number;
  readonly ejercicio: string;
  readonly baseImponible: string;
  readonly montoDeterminado: string;
}

/** El impuesto a un espectaculo, de `POST /rentas/espectaculos`. Tampoco lleva fecha. */
export interface DeterminacionDeEspectaculo {
  readonly id: number;
  readonly ejercicio: string;
  readonly ingresoDeclarado: string;
  readonly montoDeterminado: string;
}

/** Un arbitrio determinado, de `GET /rentas/arbitrios`. */
export interface ArbitrioServido {
  readonly id: number;
  readonly ejercicio: string;
  readonly servicio: string;
  readonly periodo: number;
  readonly monto: string;
  readonly fechaCalculo: string;
}

/**
 * Las senas del conjunto sellado del ejercicio, de
 * `GET /seguridad/parametros/ejercicios/{ejercicio}`.
 *
 * **Es lo unico que las 181 operaciones dicen de la tabla de valores del ejercicio**, y no son
 * los valores: son de que ejercicio, de que conjunto, que version y si esta sellado. Los
 * valores los sella `normativa` (ADR-0025) y este sistema los consume de su copia local, sin
 * publicarlos por HTTP.
 */
export interface ConjuntoDelEjercicio {
  readonly ejercicio: number;
  readonly sellado: boolean;
  readonly conjuntoId: number;
  readonly version: number;
}

// ── El expediente del contribuyente ─────────────────────────────────────────────────────────

/** Un domicilio del contribuyente. */
export interface DomicilioServido {
  readonly id: number;
  readonly tipo: string;
  readonly direccion: string;
  readonly referencia: string;
  readonly ubigeo: string;
}

/** Un contacto declarado: telefono o correo. */
export interface ContactoServido {
  readonly tipo: string;
  readonly valor: string;
  readonly vigente: boolean;
}

/** La ficha del contribuyente, de `GET /rentas/contribuyentes/{id}/ficha`. */
export interface FichaDelContribuyente {
  readonly contribuyente: ContribuyenteDelPadron;
  readonly datosPersonales: {
    readonly fechaNacimiento: string;
    readonly estadoCivil: string;
    readonly conyugeId: number | null;
  };
  readonly aLaFecha: string;
  readonly domicilioFiscal: DomicilioServido | null;
  readonly contactos: readonly ContactoServido[];
}

/** Un predio del contribuyente, de `GET /rentas/predios`. */
export interface PredioServido {
  readonly predioId: number;
  readonly codigoReferenciaCatastral: string;
  readonly tipo: string;
  readonly direccion: string;
  readonly uso: string;
  readonly sector: string;
  readonly areaTerreno: string;
  readonly porcentajePropiedad: string;
  readonly condicion: string;
}

/** Un beneficio del contribuyente, de `GET /rentas/beneficios`. */
export interface BeneficioServido {
  readonly id: number;
  readonly tipo: string;
  readonly tributo: string;
  readonly clase: string;
  readonly porcentaje: string | null;
  readonly monto: string | null;
  readonly vigenciaDesde: string;
  readonly vigenciaHasta: string | null;
  readonly baseLegal: string;
  readonly documentoOrigen: string;
}

/** Las cinco partidas con que el backend publica una deuda. */
export interface PartidasDeLaDeuda {
  readonly insoluto: ImporteConFecha;
  readonly reajuste: ImporteConFecha;
  readonly interes: ImporteConFecha;
  readonly gasto: ImporteConFecha;
  readonly total: ImporteConFecha;
}

/** Una obligacion pendiente, de `GET /consultas/deuda`. */
export interface DeudaPorConcepto {
  readonly tributo: string;
  readonly ejercicio: number;
  readonly periodoDesde: number;
  readonly periodoHasta: number;
  readonly fase: string;
  readonly deuda: PartidasDeLaDeuda;
}

// ── El panel ────────────────────────────────────────────────────────────────────────────────

/** Una tarjeta de cabecera del panel. */
export interface KpiDeRecaudacion {
  readonly label: string;
  readonly value: string;
  readonly note: string;
  readonly importe: ImporteConFecha | null;
}

/** Una fila de un panel de avance. */
export interface FilaDeAvance {
  readonly label: string;
  readonly sub: string;
  readonly value: string;
  readonly pct: number;
  readonly avanceConocido: boolean;
}

/** Un panel de avance, con su titulo y su nota. */
export interface PanelDeAvance {
  readonly title: string;
  readonly note: string;
  readonly rows: readonly FilaDeAvance[];
}

/** `GET /indicadores/recaudacion`. */
export interface IndicadorDeRecaudacion {
  readonly ejercicio: number;
  readonly fechaCalculo: string;
  readonly calculadoEn: string;
  readonly kpis: readonly KpiDeRecaudacion[];
  readonly paneles: readonly PanelDeAvance[];
}

/** Un frente parado, de `GET /indicadores/trabajo-parado`. */
export interface FrenteParado {
  readonly frente: string;
  readonly modulo: string;
  readonly queEstaParado: string;
  readonly porQueCuestaDinero: string;
  readonly cuantos: number;
}

/** `GET /indicadores/trabajo-parado`. */
export interface TrabajoParado {
  readonly ejercicio: number;
  readonly fechaCalculo: string;
  readonly calculadoEn: string;
  readonly frentes: readonly FrenteParado[];
}

/** Un movimiento de la bitacora, de `GET /seguridad/auditoria`. */
export interface MovimientoDeLaBitacora {
  readonly id: number;
  readonly ejercicio: number;
  readonly tabla: string;
  readonly clave: string;
  readonly operacion: string;
  readonly usuario: string;
  readonly fecha: string;
  readonly observacion: string;
}

// ── La sesion ──────────────────────────────────────────────────────────────────────

/**
 * Quien esta trabajando, tal como `GET /seguridad/sesion` lo publica.
 *
 * **Son cuatro campos y `ejercicioDeTrabajo` puede ser nulo.** No es una posibilidad teorica:
 * medido contra la instalacion, la cuenta `administrador` contesta hoy
 * `{"usuarioId":2,"cuenta":"administrador","nombre":"Administrador del Sistema","ejercicioDeTrabajo":null}`.
 * El contrato lo declara `entero` porque declara el TIPO del campo, no si viene; quien lee tiene
 * que admitir que no venga, y la barra tiene que decirlo en vez de inventarse un ano (AC8).
 * Fijarlo es `PUT /seguridad/sesion/ejercicio`, y eso es de otro issue.
 */
export interface SesionDeLaVentanilla {
  readonly usuarioId: number;
  readonly cuenta: string;
  readonly nombre: string;
  readonly ejercicioDeTrabajo: number | null;
}

/**
 * De que municipalidad es la sesion, tal como `GET /seguridad/sesion/municipalidad` lo publica.
 *
 * Es la lectura que hace honesta la cabecera. Hasta I-1 el nombre de la entidad era una
 * constante del marco —«Municipalidad Distrital de Catacaos»— sin ninguna interfaz que la
 * cambiara: con el token de otra municipalidad, esa cabecera afirmaba de quien son unas cifras
 * que no son suyas, y lo afirmaba en todas las pantallas a la vez. `../sgtm` no pudo cerrarlo
 * —su `rotuloDeLaEntidad()` acaba diciendo «Municipalidad n.º 9», porque ninguna lectura suya
 * publicaba el nombre—; aqui si, y por eso esta es una de las dos primeras rutas que salen a la
 * red de verdad.
 */
export interface MunicipalidadDeLaSesion {
  readonly id: number;
  readonly ubigeo: string;
  readonly nombre: string;
  readonly tipo: string;
}

/**
 * Un modulo del sistema, tal como `GET /seguridad/modulos` lo publica.
 *
 * **Cinco campos, y ninguno es un icono ni un submodulo.** No es un recorte de este archivo:
 * `ModuloResource` declara exactamente `(long id, String codigo, String nombre, int orden,
 * boolean activo)`, el esquema no tiene `padre_id` ni tabla de submodulos —cero coincidencias
 * de `submodulo|modulo_padre|padre_id` en todo `db/migration/`— y ninguna de las 181
 * operaciones publica una jerarquia. Por eso el arbol es un **empalme** y no una copia: los
 * modulos son de aqui y los cuarenta destinos son del artboard (`marco/arbol.ts`).
 *
 * `activo` viaja como campo porque la consulta no lo filtra —`SELECT id, codigo, nombre,
 * orden, activo FROM modulo_sistema`, sin `WHERE`—, asi que la lista incluye los inactivos y
 * decidir que hacer con ellos es de quien compone el arbol.
 */
export interface ModuloDelSistema {
  readonly id: number;
  readonly codigo: string;
  readonly nombre: string;
  readonly orden: number;
  readonly activo: boolean;
}

/**
 * Una opcion del catalogo de accesos, de `GET /seguridad/accesos`.
 *
 * **`moduloId` es la unica razon por la que esta lectura hace falta.** La matriz de permisos es
 * un objeto plano de codigo a privilegios, y desde ella no hay forma de saber a que modulo
 * pertenece `internamiento`, `certificados` o `papeletas`. Este campo es la clave foranea
 * `acceso_modulo_fk` publicada como escalar, y es lo que ata un permiso a una rama del arbol.
 */
export interface AccesoDelSistema {
  readonly id: number;
  readonly moduloId: number;
  readonly tipo: string;
  readonly codigo: string;
  readonly nombre: string;
  readonly activo: boolean;
}

/**
 * La matriz de permisos efectivos, de `GET /seguridad/sesion/permisos` (ADR-0013).
 *
 * Es `{ "<opcion>": ["lectura", "registro", …] }` con **solo las opciones sobre las que la
 * cuenta tiene algun privilegio**; una cuenta sin ninguno recibe `{}` y no un 403.
 *
 * **El contrato no declara su forma campo a campo, y hay que saberlo**: en
 * `docs/50-api/formas-de-la-api.json` esta operacion vale literalmente `"objeto"`, porque el
 * generador describe el tipo de retorno de cada controlador y este devuelve un
 * `Map<String, List<String>>`. O sea que la comparacion campo a campo del AC5 de #4 **no puede
 * aplicarse aqui**: lo unico que el contrato promete es que es un objeto. Lo que sostiene la
 * lectura son las 134 llaves medidas en `marco/seguridadMedida.ts`.
 */
export type PermisosDeLaSesion = Readonly<Record<string, readonly string[]>>;

/**
 * La sesion tras fijar el ejercicio, de `PUT /seguridad/sesion/ejercicio`.
 *
 * **No es la misma forma que `GET /seguridad/sesion`**, y confundirlas costaria la cabecera:
 * esta publica `id`, `usuarioId`, `inicio` y `ejercicioDeTrabajo`, y **no publica ni `cuenta`
 * ni `nombre`**. Lo unico que se le toma es el ejercicio; quien esta trabajando lo sigue
 * diciendo la lectura que ya se hizo.
 */
export interface SesionTrasElCambio {
  readonly id: number;
  readonly usuarioId: number;
  readonly inicio: string;
  readonly ejercicioDeTrabajo: number | null;
}

// ── Las rutas, escritas una vez ─────────────────────────────────────────────────────────────

/**
 * Las rutas que estas dos secciones piden.
 *
 * Escritas aqui y no en cada `solicitar()`: una ruta repetida en dos pantallas se corrige en
 * una sola el dia que cambie, y la otra se queda pidiendo la vieja hasta que alguien abra esa
 * pantalla. Las que llevan parametro son funciones, para que el parametro no se olvide.
 */
export const RUTAS = {
  sesion: '/seguridad/sesion',
  municipalidadDeLaSesion: '/seguridad/sesion/municipalidad',
  modulos: '/seguridad/modulos',
  // `tamano` a 200 porque el catalogo tiene 134 accesos y el tamano por omision es 20.
  //
  // **Medido contra la instalacion, no supuesto**: sin el, `GET /seguridad/accesos` contesta
  // `{"tamano":20,"totalElementos":134,"totalPaginas":7,"hayMas":true}` y llegan los veinte
  // primeros por codigo alfabetico. Con esos veinte, solo SIETE de los doce modulos tienen
  // algun acceso conocido, y de los diez que este sistema sirve **se caerian cinco**: Inicio,
  // Fiscalización, Tránsito, Consultas y Valores. El sintoma no seria un error — seria un
  // panel con cinco modulos y ninguna pista de que faltan los otros.
  accesos: '/seguridad/accesos?tamano=200',
  permisosDeLaSesion: '/seguridad/sesion/permisos',
  ejercicioDeLaSesion: '/seguridad/sesion/ejercicio',
  padron: '/rentas/contribuyentes',
  ficha: (id: number) => `/rentas/contribuyentes/${String(id)}/ficha`,
  predios: '/rentas/predios',
  beneficios: '/rentas/beneficios',
  deuda: '/consultas/deuda',
  coactiva: '/coactiva/deudas',
  ultimaCorrida: '/rentas/predial/corridas/ultima',
  observados: (corridaId: number) => `/rentas/predial/corridas/${String(corridaId)}/observados`,
  recaudacion: '/indicadores/recaudacion',
  trabajoParado: '/indicadores/trabajo-parado',
  bitacora: '/seguridad/auditoria',
  arbitrios: '/rentas/arbitrios',
  calculoIndividual: '/rentas/predial/calculo-individual',
  calculoMasivo: '/rentas/predial/calculo-masivo',
  calculoVehicular: '/rentas/vehicular/calculo',
  alcabala: '/rentas/alcabala',
  espectaculos: '/rentas/espectaculos',
  conjuntoSellado: (ejercicio: string) => `/seguridad/parametros/ejercicios/${ejercicio}`,
} as const;

/** Pide una operacion paginada y devuelve solo su contenido. */
export async function pedirLista<T>(ruta: string, senal?: AbortSignal): Promise<readonly T[]> {
  const pagina = await solicitar<Paginado<T>>(ruta, senal === undefined ? {} : { senal });
  return pagina.contenido;
}

/** Pide una operacion que contesta un objeto. */
export async function pedirUno<T>(ruta: string, senal?: AbortSignal): Promise<T> {
  return solicitar<T>(ruta, senal === undefined ? {} : { senal });
}

/**
 * Pide un calculo, que en el contrato es un `POST`.
 *
 * **No es un capricho del verbo**: determinar produce un acto —una determinacion con su
 * identificador, su conjunto sellado y su fecha—, y por eso las cuatro memorias del modulo se
 * piden con `POST` y no con `GET`. La peticion **no lleva cuerpo** mientras la pantalla sea de
 * lectura: el proxy lo ignora a proposito (AC8 de #4) y el dia que este backend conteste, el
 * cuerpo sera lo que decida cual contribuyente, cual vehiculo o cual transferencia se
 * determina. Hasta entonces, mandar uno inventado seria escribir aqui esa decision.
 */
export async function pedirCalculo<T>(ruta: string, senal?: AbortSignal): Promise<T> {
  return solicitar<T>(ruta, {
    metodo: 'POST',
    ...(senal === undefined ? {} : { senal }),
  });
}

/**
 * Fija el ejercicio de trabajo de la sesion. **Es la primera escritura de esta interfaz.**
 *
 * <h2>La observacion es del cuerpo, y no es un adorno</h2>
 *
 * Regla 10 y RNF-052: toda modificacion de datos exige observacion del usuario. Aqui no es una
 * convencion que alguien pueda saltarse desde la pantalla, porque **es un parametro obligatorio
 * de esta funcion**: no existe la forma de llamarla sin decir por que. Del lado del backend la
 * sostiene el tipo `Observacion`, que valida en su constructor —minimo 5 caracteres tras
 * `strip()`, maximo 500—.
 *
 * <h2>Lo que NO se comprueba aqui, y por que</h2>
 *
 * Ni la longitud de la observacion ni el rango del ejercicio. Las dos son reglas del backend y
 * las dos las contesta el, medidas contra la instalacion:
 *
 * <pre>
 * {"ejercicio":2025,"observacion":"abc"} -> 422 VALIDACION
 *   «La observacion debe explicar el cambio: al menos 5 caracteres, y no espacios en blanco (ADR-0008)»
 * {"ejercicio":1800,"observacion":"…"}   -> 422 VALIDACION
 *   «Ejercicio fuera de rango: 1800. Se admite de 1990 a 2100»
 * </pre>
 *
 * Copiar aqui el 5, el 1990 o el 2100 seria escribir en la interfaz tres numeros cuya fuente es
 * el dominio del backend, y el dia que cambiaran habria dos verdades y ninguna que lo dijera.
 * La pantalla manda lo que le den y **ensena lo que el backend conteste, con sus palabras**.
 *
 * @param ejercicio el ano de trabajo que se quiere fijar
 * @param observacion por que se cambia. Sin ella la operacion no se puede ni escribir
 */
export async function cambiarElEjercicio(
  ejercicio: number,
  observacion: string,
): Promise<SesionTrasElCambio> {
  return solicitar<SesionTrasElCambio>(RUTAS.ejercicioDeLaSesion, {
    metodo: 'PUT',
    cuerpo: { ejercicio, observacion },
  });
}
