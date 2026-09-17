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

/**
 * La ultima corrida de emision del predial.
 *
 * **Ocho campos desde I-4, y no cinco.** `sector`, `simulacion` y `conjunto` los declara el
 * contrato y los contesta la instalacion —medido: `{"id":20,"ejercicio":"2026",
 * "alcance":"TODOS","sector":null,"simulacion":true,"conjunto":"",…}`—, y hasta I-4 este tipo no
 * los nombraba. Un campo que no se declara es un campo que el proveedor puede retirar sin que
 * nada se ponga rojo, y `simulacion` no es un adorno: dice si esa corrida emitio de verdad o fue
 * un ensayo, que es la diferencia entre una deuda que existe y una que no.
 */
export interface CorridaDelPredial {
  readonly id: number;
  readonly ejercicio: string;
  readonly alcance: string;
  /** El sector al que se acoto, o `null` si el alcance fue el padron entero. */
  readonly sector: string | null;
  /** Si fue un ensayo. Una corrida simulada NO emite. */
  readonly simulacion: boolean;
  /** El conjunto sellado de `normativa` con que se calculo. Vacio si no consta. */
  readonly conjunto: string;
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

/**
 * Un predio dentro de una determinacion **guardada**, de `GET /rentas/predial/determinaciones`.
 *
 * **No lleva codigo catastral, ni ubicacion, ni uso**, al reves que `PredioDeLaBase`: esos tres son
 * de `catastro` y se resuelven **a una fecha**. Publicarlos aqui serian los de hoy dentro de una
 * determinacion de hace dos anos, y ni la lectura ni la fila guardada pueden afirmar que sean los
 * de entonces. Lo dice el javadoc de `DeterminacionGuardadaResource.PredioGuardado`.
 */
export interface PredioGuardado {
  readonly predioId: number;
  readonly autovaluo: string;
  readonly valuoExonerado: string;
  readonly valuoAfecto: string;
  readonly porcentajePropiedad: string;
  readonly baseImponible: string;
  readonly origenDelAutovaluo: string;
  readonly valuacionConjuntoId: number | null;
  readonly valuacionHuella: string | null;
}

/**
 * La ultima determinacion predial **guardada** de un contribuyente, de
 * `GET /rentas/predial/determinaciones?codContribuyente=…&ejercicio=…` (#207).
 *
 * <h2>No es `DeterminacionIndividual`, y publicarla con esa forma seria el defecto de #194</h2>
 *
 * Aquella es la respuesta del `POST` que **dispara** el calculo; esta es la fila que se lee
 * despues. Es el mismo reparto que el area predial ya tiene entre `CorridaPredialResource` y
 * `CorridaGuardadaResource`. Lo que cambia no es cosmetico: aqui hay `id`, `conjuntoId`, `estado` y
 * `origen` —que la escritura no tiene—, y **no hay `modalidad`, `cuotas` ni `simulacion`**.
 *
 * <h2>Las cuatro cifras que NO estan guardadas, y de donde salen</h2>
 *
 * `uit`, `tramos`, `minimoImponible` y `derechoDeEmision` salen del **conjunto sellado que esa
 * determinacion fijo** —por su `conjuntoId`, no el vigente de hoy—. Es lo que ARQ-09 §3 promete y
 * por eso no se guardan dos veces; resolverlos con el vigente publicaria unos tramos que esa
 * determinacion nunca uso.
 *
 * <h2>Y lo que no publica, con su motivo</h2>
 *
 * **El cronograma de cuotas**: `determinacion` no guarda su `modalidad` —solo la guarda la corrida
 * masiva— y sin ella los vencimientos no se pueden resolver. Suponer la trimestral publicaria unos
 * vencimientos que el contribuyente puede no haber recibido, que es lo que la regla 5 prohibe. Es
 * #234, y ahi esta escrito ademas que la regla 6 no se cumple del todo para la individual mientras
 * la modalidad no se guarde.
 *
 * **El «Monto deducido»** de la hoja: no lo publica nadie. Lo mas cercano es `valuoExonerado`, que
 * es la parte exonerada del valuo y **no** el importe que una deduccion resta de la base.
 *
 * **Las dos ausencias no son la misma**: 404 es «ese codigo no esta en el padron» y 204 es «existe
 * y todavia no tiene determinacion de ese ejercicio» (#546).
 */
export interface DeterminacionGuardada {
  readonly id: number;
  readonly ejercicio: string;
  readonly codContribuyente: string;
  readonly sujeto: string;
  readonly conjuntoId: number;
  readonly conjunto: string;
  readonly estado: string;
  readonly origen: string;
  readonly predios: readonly PredioGuardado[];
  readonly valuoTotal: string;
  readonly valuoExonerado: string;
  readonly valuoAfecto: string;
  readonly baseImponible: string;
  readonly uit: string;
  readonly tramos: readonly TramoAplicado[];
  readonly minimoImponible: string;
  readonly impuestoInsoluto: string;
  readonly derechoDeEmision: string;
  readonly totalAPagar: string;
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

/**
 * La ficha del contribuyente, de `GET /rentas/contribuyentes/{id}/ficha`.
 *
 * **Los dos datos personales pueden faltar, y desde I-4 el tipo lo dice.** Medido contra la
 * instalacion: `"datosPersonales":{"fechaNacimiento":null,"estadoCivil":null,"conyugeId":null}`.
 * El contrato los declara `fecha` y `texto` porque declara **el tipo del campo, no si viene** —
 * es el mismo caso que `ejercicioDeTrabajo` en la sesion (I-1)—, y una fecha que se lee como
 * `string` cuando llega `null` acaba en la pantalla como «null» o revienta al formatearse.
 */
export interface FichaDelContribuyente {
  readonly contribuyente: ContribuyenteDelPadron;
  readonly datosPersonales: {
    readonly fechaNacimiento: string | null;
    readonly estadoCivil: string | null;
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

/**
 * Una obligacion pendiente, de `GET /consultas/deuda` — y de la constancia de no adeudo.
 *
 * **Es la misma fila en las dos**, y no por parecido: las dos salen del mismo puerto
 * (`ConsultaDeDeudaPublica`), asi que el contrato publica para las dos `tributo`, `ejercicio`,
 * `predioId`, `vehiculoId`, `periodoDesde`, `periodoHasta`, `fase` y el desglose anidado en
 * `deuda`. Declararla dos veces con dos nombres seria dos sitios donde equivocarse el dia que el
 * puerto cambie.
 *
 * Los dos identificadores llegan **anulables**: una obligacion es de un predio o de un vehiculo,
 * nunca de los dos, y las que no son de ninguno —una multa— no traen ninguno.
 */
export interface DeudaPorConcepto {
  readonly tributo: string;
  readonly ejercicio: number;
  readonly predioId: number | null;
  readonly vehiculoId: number | null;
  readonly periodoDesde: number;
  readonly periodoHasta: number;
  readonly fase: string;
  readonly deuda: PartidasDeLaDeuda;
}

// ── La ventanilla de Consultas (#169) ───────────────────────────────────────────────────────

/** Quien es, en la cabecera de la ficha unificada. Sin el identificador interno, a proposito. */
export interface ContribuyenteDeLaFicha {
  readonly codigo: string;
  readonly nombre: string;
  readonly documento: string;
}

/**
 * El «Resumen de saldos» de `GET /consultas/unificada`: **las cinco cifras ya sumadas por el
 * servidor**, y la frase que las explica.
 *
 * Las cinco llegan sumadas sobre TODAS las obligaciones —no sobre la pagina— y
 * `estadoDeLaConsulta` llega redactado (RNF-083). Es literalmente lo que la regla de
 * `conectores.ts` pide: aqui no hay nada que sumar, y por eso esta pantalla no suma.
 */
export interface ResumenDeSaldos {
  readonly insoluto: ImporteConFecha;
  readonly reajuste: ImporteConFecha;
  readonly interes: ImporteConFecha;
  readonly gasto: ImporteConFecha;
  readonly total: ImporteConFecha;
  readonly estadoDeLaConsulta: string;
}

/**
 * La ficha unificada de un contribuyente, de `GET /consultas/unificada?contribuyente={codigo}`.
 *
 * **Se declaran la cabecera y el resumen, y no las seis secciones paginadas** —deudas
 * pendientes, pagos, altas y bajas, fraccionamientos, valores y declaraciones—. No es un olvido:
 * el contrato las publica y `con-panel` **no tiene ni una tabla** donde dibujarlas, asi que
 * declarar aqui seis tipos de fila seria escribir la forma de lo que ninguna pantalla lee. El dia
 * que una pestana las dibuje, se declaran con ella.
 */
export interface FichaUnificada {
  readonly contribuyente: ContribuyenteDeLaFicha;
  /** La fecha de corte con la que se contesto todo lo que depende de hoy. */
  readonly aLaFecha: string;
  readonly resumenDeSaldos: ResumenDeSaldos;
}

/**
 * Lo que produce el acogimiento **cuando hay campana elegida**, y nulo cuando no.
 *
 * `alicuotaAplicada` y no «tasa» (regla 8). Llega como texto en tanto por ciento.
 */
export interface SimulacionDelBeneficio {
  readonly campania: string;
  readonly alicuotaAplicada: string;
  readonly baseDelBeneficio: string;
  readonly baseDelBeneficioImporte: ImporteConFecha;
  readonly ahorro: ImporteConFecha;
  readonly deudaConBeneficio: ImporteConFecha;
}

/** Una campana a la que se puede simular el acogimiento. Sale del conjunto sellado. */
export interface CampaniaAplicable {
  readonly nombre: string;
  readonly alicuota: string;
  readonly base: string;
}

/**
 * La simulacion de acogimiento de un contribuyente, de
 * `GET /consultas/deudas-con-beneficio?contribuyente={codigo}`.
 *
 * **`simulacion` es anulable y hay que tratarlo**: sale nulo cuando no se eligio campana —que es
 * el caso de esta pantalla, que no tiene con que elegirla— o cuando no hay ninguna publicada. No
 * sale con ceros a proposito: «se ahorraria 0,00» es una afirmacion sobre una campana, y sin
 * campana no hay ninguna que hacer.
 */
export interface DeudaConBeneficio {
  readonly contribuyente: ContribuyenteDeLaFicha & { readonly domicilioFiscal: string | null };
  readonly aLaFecha: string;
  readonly deudaTotal: ImporteConFecha;
  readonly deudaAcogida: ImporteConFecha;
  readonly registrosAcogidos: number;
  readonly simulacion: SimulacionDelBeneficio | null;
  readonly campaniasAplicables: readonly CampaniaAplicable[];
  /** La frase que explica lo anterior, redactada por el servidor (RNF-080, RNF-083). */
  readonly estadoDeLaSimulacion: string;
  readonly obligaciones: Paginado<DeudaPorConcepto>;
}

/**
 * La constancia de no adeudo, de
 * `GET /consultas/constancias/no-adeudo?codContribuyente={codigo}`.
 *
 * **`seNiega` es el resultado**: cierto significa que el contribuyente debe algo y que lo que
 * saldria es una constancia de DEUDA, no una de no adeudo. Y `obligaciones` **no viene
 * paginada**: es la lista entera de lo que impide la constancia, que es lo que permite que la
 * tabla de esta pantalla cuente sus propias filas sin mentir.
 */
export interface ConstanciaDeNoAdeudo {
  readonly codigoContribuyente: string;
  readonly fechaDeCorte: string;
  readonly seNiega: boolean;
  readonly obligaciones: readonly DeudaPorConcepto[];
}

// ── Autorizaciones y licencias ──────────────────────────────────────────────────────────────

/**
 * Un giro del catalogo CIIU, tal como `GET /licencias/ciiu` lo publica.
 *
 * **Ocho campos, y la pantalla dibuja cuatro.** Los otros cuatro se declaran igualmente, por lo
 * mismo que `sector`, `simulacion` y `conjunto` en `CorridaDelPredial`: un campo que no se
 * declara es un campo que el proveedor puede retirar sin que nada se ponga rojo. Y dos de ellos
 * —`zonificacionCompatible` y `requiereSectorial`— son justo lo que haria falta el dia que
 * `aut-sol` quiera comprobar la compatibilidad de uso, asi que dejarlos escritos ahorra volver a
 * leer el contrato.
 */
export interface GiroCiiu {
  readonly codigo: string;
  readonly descripcion: string;
  /** La seccion CIIU. Es lo que la columna «Materia» del artboard ensena. */
  readonly seccion: string;
  /** El nivel de riesgo ITSE, que es lo que decide la modalidad de la licencia y su plazo. */
  readonly riesgoItse: string;
  readonly zonificacionCompatible: string;
  readonly requiereSectorial: boolean;
  readonly extendido: boolean;
  readonly activo: boolean;
}

/** Un giro autorizado en una licencia de funcionamiento. */
export interface GiroDeLaLicencia {
  readonly codigo: string;
  readonly descripcion: string;
  /** Si es el giro principal. Es el que la columna «Giro» del padron ensena. */
  readonly principal: boolean;
  readonly activo: boolean;
}

/** Un acto del historial de una licencia de funcionamiento. */
export interface ActoDeLaLicencia {
  readonly tipo: string;
  readonly fecha: string;
  readonly motivo: string;
  readonly resolucion: string;
  readonly observacion: string;
}

/** Un duplicado emitido de una licencia. */
export interface DuplicadoDeLaLicencia {
  readonly numero: number;
  readonly fecha: string;
  readonly motivo: string;
  readonly reimpresion: number;
}

/**
 * Una licencia de funcionamiento, tal como `GET /licencias/funcionamiento` la publica.
 *
 * **Veintiun campos, y el padron dibuja cinco.** Los dieciseis restantes se declaran por lo mismo
 * que los cuatro de `GiroCiiu`; ademas, tres de ellos dicen algo que conviene tener a mano:
 * `estadoALaFecha` es la fecha a la que el estado esta dicho (regla 9), y `fechaDeEmision` y
 * `fechaDeVencimiento` son las dos que un filtro «Desde/Hasta» usaria — **si la operacion
 * admitiera ese filtro, que no lo admite**: ver `conectores/licencias.ts`.
 */
export interface LicenciaDeFuncionamiento {
  readonly nroLicencia: string;
  /** El codigo corto del estado. `estado` es el que se lee. */
  readonly est: string;
  readonly estado: string;
  /** La fecha a la que ese estado esta dicho (regla 9). */
  readonly estadoALaFecha: string;
  /** El titular. Es el nombre, no el codigo: el codigo es `codContribuyente`. */
  readonly contribuyente: string;
  readonly codContribuyente: string;
  readonly denominacionComercial: string;
  readonly direccion: string;
  readonly tipoDeLicencia: string;
  readonly areaDelEstablecimiento: string;
  readonly zonificacion: string;
  readonly zonaDelTerritorio: string;
  readonly ordenanzaDeLaZona: string;
  readonly zonaOrigen: string;
  readonly comprobacionDelTerritorio: string;
  readonly aforo: number;
  readonly fechaDeEmision: string;
  readonly fechaDeVencimiento: string;
  readonly nExpediente: string;
  readonly fechaDeExpediente: string;
  readonly fichaEconomica: number;
  readonly giros: readonly GiroDeLaLicencia[];
  readonly historial: readonly ActoDeLaLicencia[];
  readonly duplicados: readonly DuplicadoDeLaLicencia[];
}

// ── La cobranza coactiva ────────────────────────────────────────────────────────────────────

/** Un valor traido a la cartera coactiva, dentro del expediente. */
export interface ValorImportadoAlExpediente {
  readonly valorId: number;
  readonly fechaDeImportacion: string;
}

/** Un movimiento del expediente: de que estado a cual, con que papel y por que. */
export interface MovimientoDelExpediente {
  readonly tipo: string;
  readonly estado: string;
  readonly estadoCodigo: string;
  readonly direccionReferencial: string;
  readonly fecha: string;
  readonly motivo: string;
  readonly fecDoc: string;
  readonly numDoc: string;
  readonly activo: boolean;
  readonly usuario: string;
  readonly observaciones: string;
}

/**
 * Un expediente coactivo, de `GET /coactiva/expedientes` y de la cabecera del proceso.
 *
 * <h2>La deuda viaja UNA vez y con su fecha</h2>
 *
 * Regla 9 (RNF-075). `insoluto`, `reajuste`, `interes`, `gastos`, `deudaMateriaDeCobranza`,
 * `costas` y `totalExigible` estan **todos** a `deudaAlDia`, que es la fecha a la que el backend
 * proyecto el interes —`?proyectarInteresAl=`, y sin el, hoy—. Por eso `deudaAlDia` no es un
 * campo mas: es lo que hace que las siete cifras se puedan escribir sin mentir.
 *
 * <h2>Y el ejecutor y el auxiliar llegan como TEXTO</h2>
 *
 * No como identificador de una lista cerrada. El artboard dibuja los dos como desplegables con
 * dos opciones cada uno, y son sus nombres de ejemplo: un nombre servido que no este entre ellas
 * dejaria el control **en blanco** —Radix no dibuja un valor que no es ninguna de sus
 * opciones—, que es peor que no ponerlo. Ver `conectores/coactiva.ts`.
 */
export interface ExpedienteCoactivo {
  readonly numero: string;
  readonly ejercicio: number;
  readonly correlativo: number;
  readonly codContribuyente: string;
  readonly ejecutor: string;
  readonly auxiliar: string;
  readonly fechaDeApertura: string;
  readonly asunto: string;
  readonly direccionReferencial: string;
  readonly estado: string;
  readonly estadoCodigo: string;
  /** Cuantos valores se le importaron. Es una cuenta, no un importe. */
  readonly valores: number;
  readonly insoluto: string;
  readonly reajuste: string;
  readonly interes: string;
  readonly gastos: string;
  readonly deudaMateriaDeCobranza: string;
  readonly costas: string;
  readonly totalExigible: string;
  /** La fecha a la que estan las siete cifras de arriba. Ver el javadoc. */
  readonly deudaAlDia: string;
  readonly valoresImportados: readonly ValorImportadoAlExpediente[];
  readonly historial: readonly MovimientoDelExpediente[];
}

/** Una diligencia de notificacion de un acto coactivo. */
export interface DiligenciaDelActo {
  readonly intento: number;
  readonly fecha: string;
  readonly modalidad: string;
  readonly resultado: string;
  readonly surtioEfecto: boolean;
  readonly exigibleDesde: string | null;
  readonly notificador: string;
  readonly domicilio: string;
  readonly receptor: string | null;
  readonly documentoReceptor: string | null;
  readonly vinculo: string | null;
  readonly acuse: string | null;
  readonly usuario: string | null;
  readonly observaciones: string;
}

/**
 * Un acto dictado en el expediente, de `GET /coactiva/expedientes/{numero}/proceso`.
 *
 * **`actoId` es la llave con que se cruza con su costa** (#177). Es el mismo nombre y el mismo
 * tipo que `CostaDelActo.actoId`, porque es la misma llave: la costa se devenga por acto
 * —`costa_acto_uq` lo impide tarifar dos veces— y hasta #177 lo unico comun entre las dos
 * respuestas era `tipo`. Emparejar por tipo se rompe con dos EMBARGO en el mismo expediente, que
 * es lo normal: la costa de uno acabaria en la fila del otro — ver `conectores/coactiva.ts`.
 *
 * `medida` es nula salvo en la REC-2, que es el unico acto que ordena una medida cautelar.
 */
export interface ActoDelExpediente {
  readonly actoId: number;
  readonly tipo: string;
  readonly titulo: string;
  readonly numero: string;
  readonly fecha: string;
  readonly descripcion: string;
  readonly medida: string | null;
  readonly exigibleDesde: string | null;
  readonly usuario: string | null;
  readonly observaciones: string;
  readonly diligencias: readonly DiligenciaDelActo[];
}

/** El seguimiento del expediente: su cabecera y sus actos. */
export interface ProcesoDelExpediente {
  readonly expediente: ExpedienteCoactivo;
  readonly actuaciones: readonly ActoDelExpediente[];
}

/**
 * Una linea de la liquidacion de costas: **un acto, un arancel**.
 *
 * `arancelFuente` es la llave del parametro sellado con su documento fuente, y es lo que explica
 * la cifra: sin el, la pantalla ensenaria un importe que nadie puede justificar (ARQ-09 §3).
 */
export interface CostaDelActo {
  readonly actoId: number;
  readonly acto: string;
  readonly descripcion: string;
  readonly montoS: string;
  readonly arancelFuente: string;
}

/**
 * Una liquidacion de costas, de `GET /coactiva/liquidaciones-costas`.
 *
 * **Dos fechas y no una**, y el backend lo dice en su propio javadoc: `fecha` es de cuando es
 * `totalS` —congelado el dia de la liquidacion— y `aLaFecha` es a que dia esta `pendienteS`,
 * que depende de lo que el libro haya recibido entretanto. Bajo una sola, una liquidacion de
 * marzo pareceria calculada hoy.
 *
 * `pendienteS`, `aLaFecha` y `estado` son nulos en la liquidacion recien registrada: se derivan
 * de la consulta, no de la liquidacion.
 */
export interface LiquidacionDeCostas {
  readonly nroLiquidacion: string;
  readonly expedCoact: string;
  readonly ejercicio: number;
  readonly fecha: string;
  readonly tributo: string;
  /** Lo liquidado, congelado a `fecha`. Es la suma de las lineas de `costas`. */
  readonly totalS: string;
  readonly pendienteS: string | null;
  readonly aLaFecha: string | null;
  readonly estado: string | null;
  readonly conjuntoDeParametros: number;
  readonly observacion: string;
  readonly usuarioRegistro: string | null;
  readonly costas: readonly CostaDelActo[];
}

/**
 * Una declaracion de prescripcion, de `GET /coactiva/prescripcion`.
 *
 * **Es la relacion, no la resolucion**, y la diferencia decide lo que esta interfaz puede
 * dibujar: la fila lleva el `plazo` que se aplico —leido del conjunto sellado, «4 ANIOS»— y como
 * se resolvio el rango, pero **no lleva la fecha en que prescribe** ninguna deuda. Esa sale del
 * computo ejercicio por ejercicio, que solo publica `POST /coactiva/prescripcion`, y el propio
 * backend advierte que no es «el inicio mas el plazo».
 *
 * Sin ninguna cifra de dinero: la prescripcion no extingue un importe, deja sin accion su cobro.
 */
export interface PrescripcionDeclarada {
  readonly id: number;
  readonly codContribuyente: string | null;
  readonly contribuyente: string | null;
  readonly tributo: string;
  readonly ejercicioDesde: number;
  readonly ejercicioHasta: number;
  readonly fechaDePresentacion: string;
  /** Cual de los plazos del art. 43 se aplico. */
  readonly plazoAplicable: string;
  /** El plazo leido del conjunto sellado: cantidad y unidad, «4 ANIOS». */
  readonly plazo: string;
  readonly resultado: string;
  readonly nDeResolucion: string | null;
  readonly ejerciciosPrescritos: readonly number[];
  readonly usuario: string;
  readonly observacion: string;
}

// ── Transito: papeletas, sus actos, el deposito y la ficha del vehiculo (#180) ──────────────

/**
 * Una papeleta de transito, de `GET /transito/papeletas` (RF-060).
 *
 * Los veintiun campos que `PapeletaResource` publica, con la anulabilidad que el controlador
 * declara. **Se declaran todos aunque `tra-pap` lea uno**: un campo declarado es un campo que el
 * proveedor no puede retirar sin poner rojo este build, que es lo que I-4 aprendio al encender la
 * ficha del contribuyente.
 *
 * Los importes son texto decimal (regla 1) y `fechaInfraccion` es ISO 8601 sin hora.
 */
export interface PapeletaDeTransito {
  readonly id: number;
  readonly familia: string;
  readonly numero: string;
  readonly fechaInfraccion: string;
  readonly horaInfraccion: string | null;
  readonly lugar: string;
  readonly placa: string | null;
  readonly vehiculoId: number | null;
  readonly infractorId: number | null;
  readonly propietarioId: number | null;
  readonly contribuyenteId: number | null;
  readonly predioId: number | null;
  readonly notificacionPreviaId: number | null;
  readonly baseImponible: string;
  readonly porcentajeInfraccion: string;
  readonly importeInfraccion: string;
  readonly porcentajeACobrar: string;
  readonly importeAPagar: string;
  readonly importeConBeneficio: string | null;
  readonly estado: string;
  readonly usuarioRegistro: string | null;
}

/**
 * Una diligencia de notificacion de un acto, con su acuse.
 *
 * **Vienen todas, una fila por intento**, y el backend dice por que: «quedarse con la ultima
 * escondería que las dos anteriores no encontraron a nadie, que es justamente lo que hay que poder
 * mostrar cuando el administrado discute la notificación».
 */
export interface AcuseDelActo {
  readonly intento: number;
  readonly fecha: string;
  readonly modalidad: string;
  readonly resultado: string;
  readonly recibidoPor: string | null;
  readonly acuse: string | null;
  readonly exigibleDesde: string | null;
}

/**
 * Un documento emitido por una papeleta, de `GET /transito/papeletas/{numero}/actos`.
 *
 * `clase` dice de que registro sale —`RESOLUCION_GERENCIA` o `ACTA_INTERNAMIENTO`—, `tipo` que
 * documento es dentro de su clase y `numero` el numero impreso. `documentoId` es la fila de
 * `documento_emitido` con que se reimprime (RF-132): un identificador interno, no un numero de
 * documento.
 *
 * **No publica ningun estado del acto.** Lo unico que dice de como quedo son sus `acuses`, y el
 * backend prohibe expresamente resumirlos en el ultimo.
 */
export interface ActoDeLaPapeleta {
  readonly clase: string;
  readonly tipo: string;
  readonly numero: string;
  readonly fecha: string;
  readonly documentoId: number;
  readonly observacion: string;
  /**
   * En que punto de su notificacion esta, **derivado en el backend** de todos sus acuses (#185).
   *
   * `SIN_NOTIFICACION` —el acta del deposito, que se entrega en mano—, `SIN_DILIGENCIAR`,
   * `NO_NOTIFICADO` —hubo intentos y ninguno surtio efecto— y `NOTIFICADO`.
   *
   * **No sustituye a `acuses`**, que sigue llegando entero: lo que este campo resume no es la
   * traza sino el hecho que la traza produce. Y **no dice nada del plazo**: «Conforme» y «Por
   * vencer» son estados del plazo, que vive en el conjunto sellado, y pedirlo dejaria esta
   * operacion contestando 422 en toda municipalidad sin sellar.
   */
  readonly estado: string;
  readonly acuses: readonly AcuseDelActo[];
}

/** Un recurso presentado contra la papeleta. */
export interface DescargoDeLaPapeleta {
  readonly id: number;
  readonly nDeExpediente: string;
  readonly fecha: string;
  readonly tipoDeRecurso: string;
  readonly presentadoHasta: string;
  readonly enPlazo: boolean;
}

/**
 * El expediente de una papeleta: sus recursos y **todos** sus documentos, en orden de fecha.
 *
 * La secuencia es una sola aunque los papeles salgan de tres registros —`resolucion_gerencia`,
 * `internamiento` e `internamiento_movimiento`—: componerla es del backend, y por eso esta
 * interfaz no intercala tres listas a mano.
 */
export interface ExpedienteDeLaPapeleta {
  readonly papeleta: string;
  readonly familia: string;
  readonly estado: string;
  readonly descargos: readonly DescargoDeLaPapeleta[];
  readonly actos: readonly ActoDeLaPapeleta[];
}

/**
 * Una fila de la grilla «Vehiculos en deposito», de `GET /transito/internamientos` (RF-064).
 *
 * <h2>Dias si, importe no — y lo dice el backend, no esta interfaz</h2>
 *
 * `tasaDeCustodia` **no es una tarifa**: es «el concepto del TUPA con que se cobra la custodia».
 * El propio `InternamientoEnConsulta` lo deja escrito: «el prototipo dibuja "Tasa diaria S/" y
 * "Custodia S/" en la grilla. Aqui no estan, y no es un olvido: la tarifa de la custodia vive en
 * `tasa` y su ordenanza es **D-02b, que sigue abierta**. Publicar una cifra compuesta con una
 * tarifa inventada seria peor que no publicarla —el administrado pagaria lo que la pantalla
 * diga—». O sea que el hueco de esta pantalla es el de una decision abierta, y no el de un campo
 * olvidado.
 *
 * `dias` va **con su fecha** (`calculadoA`, regla 9 / RNF-075): los dias en deposito de hoy no son
 * los de manana.
 */
export interface InternamientoEnDeposito {
  readonly id: number;
  readonly placa: string;
  /**
   * La categoria con que el vehiculo esta inscrito en el padron (#185).
   *
   * **Nula** cuando el ingreso no nombro ninguna ficha —se interna lo que se interna, este o no
   * inscrito— o cuando la ficha no declara categoria. Nunca cadena vacia: en una columna se leeria
   * como un dato.
   */
  readonly clase: string | null;
  readonly papeleta: string | null;
  readonly deposito: string;
  readonly fechaDeIngreso: string;
  readonly fechaDeSalida: string | null;
  readonly dias: number;
  /** La fecha con la que se contaron los dias (regla 9, RNF-075). */
  readonly calculadoA: string;
  readonly estado: string;
  /** El **concepto del TUPA**, no un importe. Ver el javadoc de esta interfaz. */
  readonly tasaDeCustodia: string;
  readonly acta: string;
}

/**
 * Una linea de un resumen de papeletas, de `GET /transito/reportes/resumen-*` (#53, #184).
 *
 * <h2>Aqui no hay ni una cifra de recaudacion, y lo dice el backend</h2>
 *
 * Todos los importes son **los del acta** —`papeleta.importe_a_pagar`, congelado al registrar la
 * papeleta—, agrupados por el estado en que esta cada una. `importeDeLasPagadas` es «cuanto sumaban
 * las actas de las que constan pagadas» y **no** «cuanto se cobro»: no cuenta los intereses
 * cobrados, cuenta entero un pago parcial y sigue contando un recibo anulado. Lo recaudado sale del
 * libro y tiene su propia operacion, `GET /transito/reportes/resumen-recaudacion`.
 *
 * `clave` es el valor por el que se agrupo —el estado, el codigo, las dos letras, el mes o el ano—
 * y `ano` sale **solo** cuando el agrupador lo determina (`ANO` y `MES`); con los otros tres va
 * nulo, porque agrupar por estado, por codigo o por iniciales mezcla anos dentro de un grupo.
 */
export interface LineaDelResumenDePapeletas {
  readonly clave: string;
  readonly descripcion: string | null;
  readonly ano: number | null;
  readonly cantidad: number;
  readonly importe: string;
  readonly pagadas: number;
  readonly importeDeLasPagadas: string;
  readonly pendientes: number;
  readonly importeDeLasPendientes: string;
  readonly enCoactiva: number;
  readonly importeEnCoactiva: string;
  /** El dia al que se leyeron los estados (regla 9, RNF-075). */
  readonly actualizadoA: string;
}

/**
 * Un resumen de papeletas entero, de `GET /transito/reportes/resumen-papeletas` (#53, #184).
 *
 * `papeletas` es el total **calculado en el servidor** —la suma de las `cantidad` de las lineas—, y
 * por eso esta interfaz no lo recompone: recomponer una cifra en el cliente es como se acaba
 * mostrando un total que no coincide con el papel exportado.
 *
 * `desde` y `hasta` **viajan dentro** y no son decorativos: un resumen sin fechas no existe (regla
 * 9, RNF-075). Cuando no se mandan, el backend toma el ejercicio en curso de su reloj y **dice
 * cual**, que es lo que permite a la pantalla escribirlo en vez de suponerlo.
 */
export interface ResumenDePapeletas {
  readonly agrupadoPor: string;
  readonly desde: string;
  readonly hasta: string;
  readonly papeletas: number;
  readonly importeTotal: string;
  readonly actualizadoA: string;
  readonly lineas: readonly LineaDelResumenDePapeletas[];
}

/** Un cambio de placa, con quien lo hizo y por que. */
export interface CambioDePlaca {
  readonly anterior: string;
  readonly nueva: string;
  readonly usuario: string;
  readonly fecha: string;
  readonly observacion: string;
}

/**
 * La ficha de un vehiculo, de `GET /rentas/vehiculos/{placa}` (RF-024).
 *
 * La placa se compara **sin el guion**, asi que `T2G-418` y `T2G418` llevan a la misma ficha. Una
 * placa que no esta en el padron de esta municipalidad contesta **404**, y una mal formada **422**:
 * son dos respuestas distintas a proposito, y la pantalla las dice como averia y no como dato.
 */
export interface VehiculoServido {
  readonly id: number;
  readonly placa: string;
  readonly contribuyenteId: number;
  readonly marca: string;
  readonly modelo: string;
  readonly categoria: string | null;
  readonly anioFabricacion: number;
  readonly anioInscripcion: number;
  readonly numeroMotor: string | null;
  readonly numeroSerie: string | null;
  readonly estado: string;
  readonly historialDePlacas: readonly CambioDePlaca[];
}

// ── El panel ────────────────────────────────────────────────────────────────────────────────

/** Una tarjeta de cabecera del panel. */
export interface KpiDeRecaudacion {
  readonly label: string;
  readonly value: string;
  readonly note: string;
  readonly importe: ImporteConFecha | null;
}

/**
 * Una fila de un panel de avance.
 *
 * **Ocho campos, y tres de ellos pueden ser nulos** (#167). `importe`, `cargado` y `pendiente` son
 * las tres cifras que el `sub` ya decia con palabras, publicadas ademas sin redactar; van nulas en
 * las filas que no las tienen —el bloque «Recaudacion por mes» agrupa por el mes del abono y no
 * tiene cargado ni pendiente propios—. Un cero ahi afirmaria que ese mes cargo cero.
 *
 * `avanceConocido` es lo que separa el 0 que se midio del 0 que no se pudo medir: con la base en
 * cero no hay avance que medir, y `pct` vale 0 porque una barra sin numero no se puede pintar.
 * Dibujar ese 0 como «0 %» diria «no se ha cobrado nada» de un tributo que ni siquiera tiene
 * cargos asentados.
 */
export interface FilaDeAvance {
  readonly label: string;
  readonly sub: string;
  readonly value: string;
  readonly pct: number;
  readonly avanceConocido: boolean;
  readonly importe: ImporteConFecha | null;
  readonly cargado: ImporteConFecha | null;
  readonly pendiente: ImporteConFecha | null;
}

/** Un panel de avance, con su titulo y su nota. */
export interface PanelDeAvance {
  readonly title: string;
  readonly note: string;
  readonly rows: readonly FilaDeAvance[];
}

/**
 * `GET /indicadores/recaudacion`.
 *
 * **`cargado` es lo emitido del ejercicio, y es un campo** (#167). Hasta que el backend lo
 * publico, la unica forma de leerlo era sacarlo de la frase del KPI «Avance de cobranza» con una
 * expresion regular; por eso esta declarado aqui y por eso «Emitido del ejercicio» sale de el y no
 * de ninguna nota.
 */
export interface IndicadorDeRecaudacion {
  readonly ejercicio: number;
  readonly fechaCalculo: string;
  readonly calculadoEn: string;
  readonly cargado: ImporteConFecha;
  readonly kpis: readonly KpiDeRecaudacion[];
  readonly paneles: readonly PanelDeAvance[];
}

/**
 * Un frente parado, de `GET /indicadores/trabajo-parado`.
 *
 * **`importe` nulo NO es cero** (#167). De los frentes que la operacion publica, unos se pueden
 * cifrar y otros no: los que no salen con `importe: null` —nunca `"0.00"`— justamente para que la
 * interfaz pueda dibujar «sin cifrar» y «S/ 0.00» distinto. Cuando el frente cifrado no tiene ni
 * una fila, su importe es `"0.00"` de verdad, y esa es la diferencia que hay que poder ver.
 *
 * `frente` es el nombre del enumerado —`TRANSITO`, `COACTIVA`—, para enrutar sin traducir. No es
 * lo que se dibuja: lo que se lee son `modulo` y `queEstaParado`.
 */
export interface FrenteParado {
  readonly frente: string;
  readonly modulo: string;
  readonly queEstaParado: string;
  readonly porQueCuestaDinero: string;
  readonly cuantos: number;
  readonly importe: ImporteConFecha | null;
}

/** `GET /indicadores/trabajo-parado`. */
export interface TrabajoParado {
  readonly ejercicio: number;
  readonly fechaCalculo: string;
  readonly calculadoEn: string;
  readonly frentes: readonly FrenteParado[];
}

/**
 * Un movimiento de la bitacora, de `GET /seguridad/auditoria`.
 *
 * **Son los DOCE campos que el contrato declara, y cuatro de ellos no los lee nadie** (#181). Se
 * declaran igual, por lo mismo que I-4 anadio `sector`, `simulacion` y `conjunto` a la corrida:
 * un campo declarado es un campo que el proveedor no puede retirar sin poner rojo este build. Y
 * aqui ademas se lee de un vistazo lo que la pantalla NO puede sacar de aqui — ver
 * `conectores/seguridad.ts`, que decide columna por columna.
 *
 * **`fecha` es un `Instant` y no una fecha ISO sin hora**, que es la diferencia que decide como se
 * escribe la columna «Fecha y hora»: llega como `2026-08-13T14:41:12Z`, o sea en **UTC**, y
 * `formatearFecha` de `dominio/formato.ts` no lo acepta a proposito. El conector lo explica.
 *
 * **`operacion` es una palabra de un vocabulario cerrado** —`ALTA`, `MODIFICACION`, `BAJA`,
 * `ANULACION`, `REVERSION`, `PERMISO`, `ACCESO`, que son los valores del `CHECK` de
 * `auditoria.operacion`— y no una frase. `ELIMINACION` no existe: la aplicacion no borra
 * (RNF-051, regla 4).
 */
export interface MovimientoDeLaBitacora {
  readonly id: number;
  readonly ejercicio: number;
  readonly tabla: string;
  readonly clave: string;
  readonly operacion: string;
  readonly usuario: string;
  readonly origenEquipo: string | null;
  readonly origenIp: string | null;
  /** Instante en **UTC**, `2026-08-13T14:41:12Z`. No es una fecha ISO sin hora. */
  readonly fecha: string;
  readonly observacion: string;
  readonly datosAnteriores: string | null;
  readonly datosNuevos: string | null;
}

/**
 * Un programa de fiscalizacion, de `GET /fiscalizacion/programas` (#179).
 *
 * **`id` y `codigo` son dos identificadores distintos y ninguno sobra.** `codigo` es el «Nº de
 * programa» que la pantalla teclea —«PF-2026-014»— y `id` es el que la base asigna; el `{id}` de
 * `GET /fiscalizacion/programas/{id}/muestra` es el segundo. Esta operacion es la unica que
 * convierte lo uno en lo otro, y por eso #179 la anadio a la declaracion de la hoja.
 *
 * `fechaFin`, `ejercicio`, `sector`, `criterio` y `fiscalizador` son anulables en el origen:
 * `sector` nulo significa «todo el distrito», y `ejercicio` y `criterio` van nulos en los
 * programas anteriores a `V60`. Se declaran porque un campo declarado es un campo que el
 * proveedor no puede retirar sin poner rojo este build.
 */
export interface ProgramaDeFiscalizacion {
  readonly id: number;
  readonly codigo: string;
  readonly descripcion: string;
  readonly tipo: string;
  readonly fechaInicio: string;
  readonly fechaFin: string | null;
  readonly estado: string;
  readonly ejercicio: string | null;
  readonly sector: string | null;
  readonly criterio: string | null;
  readonly fiscalizador: string | null;
}

/**
 * Un predio sorteado en la muestra de un programa, de `GET /fiscalizacion/programas/{id}/muestra`.
 *
 * **Las areas llegan como texto y SIN unidad** —`"180.50"`—, que es como el backend serializa un
 * `AreaM2`; el rotulo de la columna pone los m².
 *
 * **`condicion` es lo que la deteccion concluyo el dia del sorteo**, congelado: `CONFORME`,
 * `OMISO`, `SUBVALUADOR`, `USO_DISTINTO` o `NO_UBICADO`. No es lo que el fiscalizador anote luego
 * en el acta —eso es `hallazgo`— y los dos pueden discrepar.
 *
 * **`visitado` se DERIVA y no se guarda**: es «este predio ya tiene acta en este programa». El
 * propio backend dice que es de donde sale la columna «Estado» de la grilla.
 *
 * Los tres campos del titular van nulos cuando el predio no tiene ninguno vigente.
 */
export interface FilaDeLaMuestra {
  readonly programaId: number;
  readonly predioId: number;
  readonly codRefCatastral: string;
  readonly contribuyenteId: number | null;
  readonly codContribuyente: string | null;
  readonly titular: string | null;
  readonly sector: string | null;
  readonly condicion: string;
  readonly areaCatastral: string | null;
  readonly areaDeclarada: string | null;
  readonly diferenciaDeArea: string | null;
  readonly visitado: boolean;
  readonly fechaSorteo: string;
}

/**
 * Un acta de inspeccion, de `GET /fiscalizacion/actas` (#179).
 *
 * **Publica LAS DOS MITADES del contraste desde #191**, y eso es lo que decide lo que la pantalla
 * puede dibujar. Hasta entonces sólo había `areaHallada` y `usoHallado`, y las columnas «Declarado»
 * y «Diferencia» salían en raya en todas sus filas; ahora viajan `areaDeclarada`, `usoDeclarado` y
 * `diferenciaDeArea`, resueltos por `ActaConLoDeclarado` desde la versión de ficha que el acta
 * referencia.
 *
 * **`diferenciaDeArea` viaja HECHA, y ese es el punto**: «nunca negativa, nula si falta un lado».
 * Restar aquí dos magnitudes servidas para llenar una celda es calcular lo que nadie publicó, y esa
 * columna es la que sostiene la determinación.
 *
 * **Los tres son nulos y eso NO es «no publicado»: es «no consta»** — un acta **vehicular** no
 * tiene área ni uso declarados contra los que contrastar, y una predial de un predio sin ficha
 * registrada a la fecha de la visita tampoco. Se cierra con una ficha, no publicando un campo.
 *
 * **`usoHallado` nulo es «no se anoto», que no es «coincide con lo declarado»**, y solo un acta
 * predial lo lleva. `hallazgo` es lo que una persona anoto —`CONFORME`, `OMISO`, `SUBVALUADOR`,
 * `USO_DISTINTO`, `NO_UBICADO`— y `estado` el del acta: `ABIERTA` o `ANULADA`, y no mas. Eran
 * cinco hasta #214, y los otros tres —`LIQUIDADA`, `RELIQUIDADA`, `TRANSFERIDA`— no los escribia
 * nadie: se DERIVAN de que el acta tenga liquidacion, de que tenga mas de una version y de que su
 * liquidacion tenga resolucion, asi que se retiraron del enumerado y del `CHECK` en vez de
 * inventarles una escritura.
 *
 * Predial y vehicular comparten forma: cual es cual lo dice cual de `predioId` y `vehiculoId`
 * trae valor.
 *
 * **Y desde #216 dice DE QUIEN es**: junto al identificador interno viajan `contribuyente` —el
 * nombre tal como el padron lo escribe— y `codContribuyente`. Hasta entonces publicaba solo
 * `contribuyenteId`, y un contraste de areas que no nombra al obligado no se puede comprobar contra
 * nada. Los resuelve `ActasController` con **un** `porIds` por pagina, no uno por fila.
 *
 * **Los dos son anulables a la vez, y nulo significa una cosa concreta**: el obligado **ya no esta
 * en el padron**. El acta sigue saliendo —ocultarla esconderia justo el caso que hay que revisar—,
 * asi que la pantalla lo dice con esas palabras y no con las de un campo que nadie publica.
 */
export interface ActaDeFiscalizacion {
  readonly id: number;
  readonly programaId: number;
  readonly version: number;
  readonly contribuyenteId: number | null;
  readonly contribuyente: string | null;
  readonly codContribuyente: string | null;
  readonly predioId: number | null;
  readonly vehiculoId: number | null;
  readonly fichaId: number | null;
  readonly fechaVisita: string;
  readonly fiscalizador: string | null;
  readonly hallazgo: string | null;
  readonly areaDeclarada: string | null;
  readonly areaHallada: string | null;
  readonly diferenciaDeArea: string | null;
  readonly usoDeclarado: string | null;
  readonly usoHallado: string | null;
  readonly detalle: string | null;
  readonly estado: string;
}

/**
 * Una linea de la resolucion de determinacion, por ejercicio.
 *
 * **Los cinco importes son nulos hasta D-02a**, y lo dice el backend en cada uno: sin el cuadro de
 * valores unitarios firmado no hay base que calcular. Nulo es «sin cifra» y **no cero** — un cero
 * en una resolucion se lee como «no debe nada».
 *
 * Y los nombres no son los que parecen: `determinado` es **la base que resulta de lo hallado**,
 * `declarado` **la base que consta declarada**, y `diferencia` **el tributo que se dejo de
 * pagar** —o sea el insoluto omitido, no la resta de las dos bases—. `total` es `diferencia +
 * multa`, y es nulo si falta cualquiera de las dos: el backend se niega a sumar una cifra con una
 * ausencia. `condicion` si se conoce siempre.
 */
export interface LineaDeterminada {
  readonly ejercicio: number;
  readonly determinado: string | null;
  readonly declarado: string | null;
  /**
   * **La base que no se declaró, `determinado − declarado`, RESTADA POR EL BACKEND** (#193).
   *
   * Los dos sumandos ya viajaban y la resta no, así que la columna «Base omitida S/» decía la raya:
   * restarlos aquí sería aritmética sobre dinero en el navegador (regla 1, RNF-055). Nunca
   * negativa, por lo mismo que `diferenciaDeArea`; nula hasta D-02a.
   */
  readonly baseOmitida: string | null;
  readonly diferencia: string | null;
  readonly multa: string | null;
  readonly total: string | null;
  readonly condicion: string;
  readonly areaDeclarada: string | null;
  readonly areaHallada: string | null;
}

/**
 * La resolucion de determinacion, de `GET /fiscalizacion/resoluciones/{numero}` (#179).
 *
 * **Es de UNA resolucion y el numero va en la RUTA**: `RDF-2026-000001`, que es lo que
 * `EmitirDocumento` compone —`%s-%d-%06d` con el tipo `RDF`—. No existe ninguna operacion que
 * publique la relacion de resoluciones, asi que el numero no se puede elegir en pantalla: viaja en
 * la direccion, como el codigo del contribuyente en Consultas (#169). Un numero que no existe da
 * **404** «No hay ninguna resolucion de determinacion con el numero '…'».
 *
 * **`aLaFecha` no es un adorno**: es el dia al que estan las cifras, dicho aparte para no dejarlo
 * implicito (regla 9, RNF-075).
 *
 * **`cargosAsentados` llega nulo en el `GET`**: solo lo trae la respuesta de `POST
 * /fiscalizacion/transferencias`, que es la que asienta.
 *
 * **Y desde #193 publica sus TRES totales**, sumados por `TotalesDeLaDeterminacion` —funcion pura,
 * regla 6— en vez de dejarlos a la pantalla: sumar las lineas aqui daria tres cifras al centimo
 * indistinguibles de unas liquidadas sobre el papel que vuelve una diferencia **deuda exigible**.
 * Con cualquier sumando ausente el total sale **nulo**, nunca parcial, y `esperaSusCifras` dice
 * cual de los dos huecos es el suyo: el campo existe y llego vacio (D-02a), que no es lo mismo que
 * no publicarlo.
 *
 * **Lo que NO publica, y hace falta saberlo antes de leerla**: ningun interes —no lo publica
 * ninguna de las 16 operaciones de fiscalizacion, y es #213—, y ningun **numero de acta**: lo que
 * enlaza hacia atras es `nLiquidacion`, y `actaId` es un identificador **interno** —el propio
 * backend lo dice: «un acta no se numera, asi que esto sirve para enlazar hacia atras, no para
 * escribirlo en un campo rotulado N.º de acta»—.
 */
export interface ResolucionDeDeterminacion {
  readonly numero: string;
  readonly fecha: string;
  readonly aLaFecha: string;
  /**
   * El acta de la que salio la liquidacion. **Identificador interno, no el numero de un
   * documento**: ver el javadoc de arriba y el de `FIS_RES` en `conectores/fiscalizacion.ts`.
   */
  readonly actaId: number;
  readonly nLiquidacion: string;
  readonly versionDeLaLiquidacion: number;
  readonly periodoDesde: number;
  readonly periodoHasta: number;
  readonly codContribuyente: string;
  readonly contribuyente: string;
  readonly predioId: number | null;
  readonly vehiculoId: number | null;
  readonly documentoSustento: string | null;
  readonly sustento: string | null;
  readonly baseLegal: string | null;
  readonly fichaAnteriorId: number | null;
  readonly fichaNuevaId: number | null;
  readonly usuarioRegistro: string | null;
  readonly observacion: string | null;
  /** La suma del tributo dejado de pagar de todas las lineas; nulo hasta D-02a (#193). */
  readonly insolutoOmitido: string | null;
  /** La suma de las multas de todas las lineas; nulo hasta D-02a y D-02c (#193). */
  readonly multaTributaria: string | null;
  /** La suma de los dos anteriores; **nulo si falta cualquiera**, jamas parcial (#193). */
  readonly totalLiquidado: string | null;
  /**
   * **Si los totales siguen pendientes** (#193), publicado justo para que la interfaz pueda
   * escribir «sin cifrar» en vez de un cero — que un contribuyente leeria como «no debe nada»—
   * **sin adivinar** por que el campo llego vacio.
   */
  readonly esperaSusCifras: boolean;
  readonly lineas: readonly LineaDeterminada[];
  readonly cargosAsentados: number | null;
}

/**
 * Una fila de la relacion de resoluciones, de `GET /fiscalizacion/resoluciones` (#192).
 *
 * **Lleva lo que hace falta para ELEGIR una y ni una cifra**: el cuadro de la determinacion y sus
 * tres totales los publica `GET /fiscalizacion/resoluciones/{numero}`, y traerlos aqui obligaria a
 * leer el detalle de cada fila de la pagina para pintar una lista que no los dibuja.
 *
 * Hasta #192 **no existia**, y la consecuencia esta medida: esta era la unica hoja del sistema que
 * no podia tomar «la primera de la relacion» —`coa-exp`, `coa-cost`, `tra-pap`, `fis-prog` y
 * `fis-actas` lo hacen— porque no habia relacion, de modo que abierta desde el menu **no ensenaba
 * una resolucion nunca**.
 *
 * `contribuyente` sale del padron en UNA lectura por pagina, y es nulo —con su codigo— si el padron
 * ya no lo tiene: la fila sale igual, porque ocultarla escondia justo el caso que hay que revisar.
 */
export interface ResolucionEnLaRelacion {
  readonly numero: string;
  readonly fecha: string;
  readonly codContribuyente: string | null;
  readonly contribuyente: string | null;
  readonly predioId: number | null;
  readonly vehiculoId: number | null;
  readonly nLiquidacion: string;
  readonly versionDeLaLiquidacion: number;
  readonly actaId: number;
  readonly periodoDesde: number;
  readonly periodoHasta: number;
  readonly documentoSustento: string;
}

/**
 * El embudo de un programa de fiscalizacion, de `GET /fiscalizacion/programas/{id}/embudo` (#196).
 *
 * **Las cuatro cifras juntas, cuadradas y en UNA lectura**, que es lo que hace que `fis-panel` deje
 * de estar sin conectar. Componerlas con el `totalElementos` de cuatro operaciones distintas es
 * exactamente lo que `datos/conectores.ts` prohibe: cuatro peticiones para cuatro numeros que
 * ninguna operacion afirma que signifiquen eso, y un embudo compuesto en el navegador **se lee
 * igual** que uno publicado sin que ninguno de los dos se pueda cuadrar.
 *
 * **`conActa` cuenta las unidades con acta VIVA —levantada y no anulada—, y el nombre lo dice a
 * proposito.** Un acta *cerrada* no existe aqui: `EstadoDeActa` declara **dos** valores —`ABIERTA`
 * y `ANULADA`— desde #214, que retiro `LIQUIDADA`, `RELIQUIDADA` y `TRANSFERIDA` porque nadie las
 * escribia y las tres se **derivan** —de que exista su liquidacion, de que tenga mas de una version
 * y de que su liquidacion tenga resolucion—.
 *
 * **Y desde #241 esta cifra SI llena la tercera celda de `fis-panel`**, que hasta entonces era un
 * hueco. Lo que cambio no es el campo sino el rotulo: decia «Con acta cerrada» y dice «Con acta
 * levantada». Lo que la corrigio son dos frases del propio artboard —la nota de `fis-panel`, «lo
 * detectado, lo INSPECCIONADO y lo que sostiene una determinacion», y la de `fis-actas`, que
 * situaba el cierre ANTES de liquidar—, o sea que su tercera etapa siempre fue la inspeccion.
 *
 * **Lo que no se publica, y no es por falta de dato**: «con liquidacion», que #231 propuso. Se
 * puede contar —`LiquidarFiscalizacion` escribe la liquidacion y su apertura, y
 * `CambiarEstadoDeLaLiquidacion` escribe la anulacion, asi que no valdria cero—, pero es la
 * **cuarta** etapa del embudo del manual, la que va DESPUES de «Inspeccionados», y `fis-panel` no
 * tiene celda para ella: la suya es «Con diferencia».
 *
 * **`aLaFecha` no es decorativo** (regla 9, RNF-075): las tres ultimas etapas estan congeladas por
 * lo que se sorteo y se visito, y la primera se resuelve contra el padron de HOY.
 *
 * `detectadosPorCruce` es nulo si el programa no declara sus parametros de sorteo, y entonces
 * `parametroQueFalta` dice cual — o sea que la ausencia viene **con su causa dentro**.
 */
export interface EmbudoDelPrograma {
  readonly programaId: number;
  readonly codigo: string;
  readonly ejercicio: number | null;
  readonly aLaFecha: string;
  readonly detectadosPorCruce: number | null;
  readonly parametroQueFalta: string | null;
  readonly programados: number;
  readonly conActa: number;
  readonly conDiferencia: number;
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
  /**
   * Los predios de UN contribuyente, por su codigo (#26).
   *
   * **`?codContribuyente=` no es opcional**: sin el, `GET /rentas/predios` contesta 422 —«Hay
   * que decir de quien son los predios: falta «codContribuyente» (o su otro nombre,
   * «contribuyente»)»—, medido contra la instalacion. Hasta #26 el contrato no publicaba ese
   * parametro, asi que esta ruta se pedia pelada y el proxy la contestaba igual; hoy lo declara
   * `docs/50-api/parametros-de-la-api.json` y el proxy la rechaza como la rechazaria el backend.
   *
   * Se manda el nombre canonico y no `contribuyente`: los dos valen, y elegir el que el
   * contrato declara primero deja una sola forma en la interfaz.
   */
  prediosDe: (codigo: string) =>
    `/rentas/predios?codContribuyente=${encodeURIComponent(codigo)}`,
  /**
   * Los beneficios de UN contribuyente, por su codigo.
   *
   * `?contribuyente=` es un parametro que la operacion **si** publica —el repositorio cruza con
   * `contribuyente` y compara `c.codigo_contribuyente = :codigo`—, al contrario que el
   * `?codContribuyente=` que exigen `/rentas/predios` y `/consultas/deuda` (#26). Por eso esta
   * es la unica de las tres del expediente que I-4 pudo encender.
   */
  beneficiosDe: (codigo: string) => `/rentas/beneficios?contribuyente=${encodeURIComponent(codigo)}`,
  /**
   * La deuda de UN contribuyente (#26). Aqui `codContribuyente` no tiene segundo nombre: es el
   * unico que `GET /consultas/deuda` admite, y sin el contesta 422.
   */
  deudaDe: (codigo: string) =>
    `/consultas/deuda?codContribuyente=${encodeURIComponent(codigo)}`,
  /**
   * La ficha unificada de UN contribuyente (#169). `?contribuyente=` es **obligatorio** en el
   * contrato y en el controlador: sin el, ni siquiera se llega al metodo.
   */
  fichaUnificadaDe: (codigo: string) =>
    `/consultas/unificada?contribuyente=${encodeURIComponent(codigo)}`,
  /**
   * La simulacion de acogimiento de UN contribuyente (#169).
   *
   * El contrato declara `contribuyente` **opcional** —lo derivo del filtro de la pantalla— y el
   * controlador lo exige igual: sin el contesta 422 «la simulacion del acogimiento es de una
   * persona concreta, no del padron entero». Se manda siempre, que es lo unico que hace que la
   * pantalla no dependa de cual de las dos declaraciones gane.
   *
   * **Sin `?benefAplicable=`**: elegir campana es una decision de quien atiende y esta pantalla
   * no tiene el desplegable que la haria; mandar una fija seria simular un descuento que nadie
   * pidio sobre la deuda de alguien.
   */
  deudasConBeneficioDe: (codigo: string) =>
    `/consultas/deudas-con-beneficio?contribuyente=${encodeURIComponent(codigo)}`,
  /**
   * La constancia de no adeudo de UN contribuyente (#169).
   *
   * `?codContribuyente=` y **no `contribuyente`**: aqui el parametro no tiene segundo nombre, al
   * contrario que en las dos de arriba. Sin `?formato=`, que es lo que distingue el JSON que esta
   * pantalla pinta del archivo descargable de RF-132.
   */
  constanciaDeNoAdeudoDe: (codigo: string) =>
    `/consultas/constancias/no-adeudo?codContribuyente=${encodeURIComponent(codigo)}`,
  coactiva: '/coactiva/deudas',
  /**
   * El primer expediente de la cartera coactiva (#170).
   *
   * **`?tamano=1` y no la pagina entera**, porque esta pantalla dibuja UN expediente y todavia no
   * tiene con que elegirlo: pedir veinte para usar uno seria pedir diecinueve que nadie mira. El
   * parametro lo publica el contrato —`docs/50-api/parametros-de-la-api.json`, `tamano` entre los
   * opcionales de `GET /coactiva/expedientes`—, que es la condicion para mandarlo (#26).
   *
   * El dia que la pantalla tenga su caja de busqueda, lo que cambia es esta ruta: el criterio
   * tambien esta publicado (`nroDeExpediente`, `codContribuyente`, `ejecutor`, `estado`).
   *
   * <h2>Y NO pagina, que es lo que #228 midio y dejo escrito</h2>
   *
   * La tabla de esta hoja son los ACTOS de un expediente, **no una relacion**, y este `?tamano=1` sirve para elegir
   * CUAL se dibuja: paginar esta lectura **no paginaria esa tabla**. Asi que lo que le falta no
   * son los mandos —que #186 dejo instalados y que `fis-prog` estrena en #228— sino el
   * **selector**, y un selector es un filtro: `kamayuk-lib`#94 —el interprete guarda lo tecleado
   * en el estado de `<Pantalla>` y no lo publica por ningun lado— y #172. Escrito aqui para que no
   * haya que volver a medirlo.
   */
  expedientesCoactivos: '/coactiva/expedientes?tamano=1',
  /**
   * El seguimiento de UN expediente: su cabecera, su deuda a la fecha y sus actos.
   *
   * `{numero}` es el numero impreso del expediente, y sale de la lista de arriba. No se inventa:
   * sin un expediente elegido no hay proceso que pedir.
   */
  procesoDelExpediente: (numero: string) =>
    `/coactiva/expedientes/${encodeURIComponent(numero)}/proceso`,
  /**
   * La primera liquidacion de costas de la relacion. `tamano` esta publicado, como arriba.
   *
   * <h2>Y NO pagina, que es lo que #228 midio y dejo escrito</h2>
   *
   * La tabla de esta hoja son las LINEAS de una liquidacion, **no una relacion**, y este `?tamano=1` sirve para elegir
   * CUAL se dibuja: paginar esta lectura **no paginaria esa tabla**. Asi que lo que le falta no
   * son los mandos —que #186 dejo instalados y que `fis-prog` estrena en #228— sino el
   * **selector**, y un selector es un filtro: `kamayuk-lib`#94 —el interprete guarda lo tecleado
   * en el estado de `<Pantalla>` y no lo publica por ningun lado— y #172. Escrito aqui para que no
   * haya que volver a medirlo.
   */
  liquidacionesDeCostas: '/coactiva/liquidaciones-costas?tamano=1',
  /**
   * **Las liquidaciones de costas de UN expediente** (#200).
   *
   * `?nroExpedCoact=` lo publica el contrato entre los opcionales de esta operacion
   * (`docs/50-api/parametros-de-la-api.json`), y es lo que separa esta ruta de la de arriba: alli
   * se toma la primera de la relacion entera —de cualquier expediente— y aqui se piden **las de
   * este**, porque la costa de un acto puede estar en cualquiera de ellas.
   *
   * <h2>`?tamano=100` escrito, y por que ese numero y no `1`</h2>
   *
   * Porque lo que se busca no es «una liquidacion» sino **todas las de un expediente**: con
   * `?tamano=1` la costa de un acto liquidado en la segunda tanda diria que no esta liquidado, que
   * es exactamente la clase de hueco falso que #200 existe para no crear. Cien es holgado —un
   * expediente con cien tandas de liquidacion no existe— y **el conector comprueba `hayMas`**: si
   * alguna vez no cupieran, las celdas lo dicen en vez de afirmar que no hay costa.
   *
   * El tope del backend es 500 (`Paginacion.TAMANO_MAXIMO`).
   */
  liquidacionesDelExpediente: (numero: string) =>
    `/coactiva/liquidaciones-costas?nroExpedCoact=${encodeURIComponent(numero)}&tamano=100`,
  /**
   * Las prescripciones declaradas **sobre un tributo**.
   *
   * `?tributo=` es el unico parametro que las dos operaciones de `coa-cost` comparten: la
   * liquidacion publica su `tributo` y la relacion de prescripciones lo admite como filtro. Sin
   * el, lo que llegaria seria la primera declaracion de la relacion entera —de cualquier
   * contribuyente y de cualquier tributo—, que al lado de una liquidacion se leeria como suya.
   *
   * Lo que NO se puede acotar es el contribuyente: `LiquidacionResource` no publica ninguno, asi
   * que `?codContribuyente=` —que el contrato si declara— no tiene de donde salir aqui.
   */
  prescripcionesDe: (tributo: string) =>
    `/coactiva/prescripcion?tributo=${encodeURIComponent(tributo)}&tamano=1`,
  /**
   * El primer programa de fiscalizacion de la relacion (#179).
   *
   * **`?tamano=1` y no la pagina entera**, por lo mismo que `expedientesCoactivos`: esta pantalla
   * dibuja la muestra de UN programa y todavia no tiene con que elegirlo, asi que pedir veinte
   * para usar uno seria pedir diecinueve que nadie mira. El parametro esta publicado
   * —`parametros-de-la-api.json`, entre los opcionales de `GET /fiscalizacion/programas`—, que es
   * la condicion que #26 dejo escrita.
   *
   * El dia que la pantalla tenga su caja de busqueda, lo que cambia es esta ruta: el criterio
   * tambien esta publicado (`nDePrograma`, `ejercicio`).
   */
  programasDeFiscalizacion: '/fiscalizacion/programas?tamano=1',
  /**
   * Los predios sorteados en la muestra de UN programa.
   *
   * `{id}` es el identificador **interno** del programa y sale de la relacion de arriba. No se
   * inventa: con uno que no exista el backend contesta 404, que no es lo mismo que la pagina
   * vacia con que contesta un programa sin muestra sorteada.
   *
   * <h2>Y desde #228 la ventana entra por parametro</h2>
   *
   * Es la unica de las seis tablas que #228 midio que **si es una relacion paginada**, y la unica
   * que tenia sintoma: desde #172 su encabezado dice «2 de 84» y no habia forma de ver los otros
   * 82. `pagina`, `tamano`, `ordenarPor` y `direccion` estan entre los cinco opcionales que
   * `parametros-de-la-api.json` publica para esta operacion, y el tamano **no se escribe aqui**
   * sino que sale de `paginacion.tamano` de su tabla (ver `datos/laVentana.ts`): en dos sitios
   * diverge, y entonces los mandos cuentan paginas de cien sobre respuestas de veinte.
   */
  muestraDelPrograma: (id: number, ventana: Readonly<Record<string, string>> = {}) =>
    conParametros(`/fiscalizacion/programas/${String(id)}/muestra`, ventana),
  /**
   * **El embudo de UN programa: las cuatro cifras de `fis-panel`, juntas y cuadradas** (#196).
   *
   * `{id}` es el mismo identificador interno de la muestra, y sale de la misma relacion. La
   * operacion **no admite ni un parametro** —`parametros-de-la-api.json` la declara con los cuatro
   * grupos vacios—, asi que aqui no hay ventana ni filtro que componer: el embudo es de un programa
   * y ya.
   *
   * <h2>Y por que esta ruta existe en vez de componer el embudo aqui</h2>
   *
   * Porque las cuatro etapas se podian componer con el `totalElementos` de cuatro operaciones
   * distintas —omisos, muestra, actas y resultados— y eso es lo que `datos/conectores.ts` prohibe:
   * serian cuatro peticiones para cuatro numeros que ninguna operacion afirma que signifiquen eso,
   * tres de ellas acotadas a mano al programa. #196 lo publico del lado que puede cuadrarlo.
   */
  embudoDelPrograma: (id: number) => `/fiscalizacion/programas/${String(id)}/embudo`,
  /**
   * La primera acta de inspeccion de la relacion (#179).
   *
   * `?tamano=1` por el mismo motivo que arriba: la tabla de la hoja contrasta los conceptos de UNA
   * acta —no es una relacion de actas— y la pantalla todavia no tiene con que elegirla.
   *
   * **Sin `?programa=`**, que es el unico filtro que la operacion admite: acotarla a un programa
   * exige haberlo elegido, y elegir uno aqui seria decidir por quien atiende cual de las
   * inspecciones se mira.
   *
   * <h2>Y NO pagina, que es lo que #228 midio y dejo escrito</h2>
   *
   * La tabla de esta hoja son las MAGNITUDES de un acta, **no una relacion**, y este `?tamano=1` sirve para elegir
   * CUAL se dibuja: paginar esta lectura **no paginaria esa tabla**. Asi que lo que le falta no
   * son los mandos —que #186 dejo instalados y que `fis-prog` estrena en #228— sino el
   * **selector**, y un selector es un filtro: `kamayuk-lib`#94 —el interprete guarda lo tecleado
   * en el estado de `<Pantalla>` y no lo publica por ningun lado— y #172. Escrito aqui para que no
   * haya que volver a medirlo.
   */
  actasDeFiscalizacion: '/fiscalizacion/actas?tamano=1',
  /**
   * **La primera resolucion de determinacion de la relacion** (#192, #215).
   *
   * Hasta #192 esta operacion **no existia**, y era lo que obligaba a `fis-res` a exigir sujeto:
   * era la unica hoja del sistema que no podia tomar «la primera de la relacion» —`coa-exp` y las
   * otras dos de este modulo lo hacen— porque no habia relacion, de modo que abierta desde el menu
   * **no ensenaba una resolucion nunca**. Esa era la mitad de #215 que mas se nota.
   *
   * <h2>Y NO pagina, que es lo que #228 midio y dejo escrito</h2>
   *
   * La tabla de esta hoja son los EJERCICIOS de una resolucion, **no una relacion**, y este
   * `?tamano=1` sirve para elegir CUAL se dibuja: paginar esta lectura **no paginaria esa tabla**.
   * Lo que le falta no son los mandos sino el **selector**, que es un filtro —`kamayuk-lib`#94 y
   * #172—. Lo que si tiene desde #215 es la otra mitad: si la direccion trae un numero, se pide
   * ESE y esta lectura no se hace (ver `FIS_RES`).
   *
   * **Sin `?contribuyente=`**, que es el unico filtro que la operacion admite: acotarla exige haber
   * elegido a alguien, y elegirlo aqui seria decidir por quien atiende de quien es la resolucion
   * que se mira. Ademas un codigo que no existe es **404** y no una relacion sin filtrar.
   */
  resolucionesDeDeterminacion: '/fiscalizacion/resoluciones?tamano=1',
  /**
   * La resolucion de determinacion de UN numero (#179).
   *
   * El numero es el del documento —`RDF-2026-000001`— y va **en la ruta**, no en la cadena de
   * consulta: la operacion no admite ningun parametro. Se codifica igual que los codigos de
   * contribuyente de Consultas, porque el dominio lo normaliza con `strip().toUpperCase()` y no
   * promete que no lleve nada raro dentro.
   *
   * **Sin `?formato=`**: con el, la misma ruta contesta el PDF, el XLS o el RTF en vez del JSON
   * que esta pantalla dibuja. Es exactamente la distincion que #169 tuvo que hacer con
   * `constancias/no-adeudo`.
   */
  resolucionDeDeterminacion: (numero: string) =>
    `/fiscalizacion/resoluciones/${encodeURIComponent(numero)}`,
  ultimaCorrida: '/rentas/predial/corridas/ultima',
  /**
   * La ultima determinacion predial GUARDADA de un contribuyente, de un ejercicio (#207, #237).
   *
   * **Los dos parametros van siempre**, y los dos los declara `parametros-de-la-api.json` como
   * opcionales de la firma: `codContribuyente` lo exige el controlador igual —sin el es 422, «Hay
   * que decir de que contribuyente se lee la determinacion»— y `ejercicio` ausente significa **el
   * del reloj del backend**, que no es el de trabajo de la sesion. Dejarlo fuera contestaria 200
   * con la determinacion de otro ano, que es la clase de acierto que no se distingue del correcto.
   *
   * Se manda `ejercicio` y no su alias `ano`: los dos valen —`FiltroDeLaConsulta.elCanonicoOSuAlias`—
   * y el canonico deja una sola forma en la interfaz.
   *
   * **No lleva `?modalidad=` ni nada que resuelva el cronograma**: no existe. `determinacion` no
   * guarda la modalidad y por eso esta lectura no publica las cuotas (#234).
   */
  determinacionGuardada: (codigo: string, ejercicio: number) =>
    conParametros('/rentas/predial/determinaciones', {
      codContribuyente: codigo,
      ejercicio: String(ejercicio),
    }),
  observados: (corridaId: number) => `/rentas/predial/corridas/${String(corridaId)}/observados`,
  recaudacion: '/indicadores/recaudacion',
  trabajoParado: '/indicadores/trabajo-parado',
  /**
   * La bitacora de UN ejercicio (#26, #181).
   *
   * Es la unica de las tres que lo declara en la firma —`@RequestParam("ejercicio") int`—, asi
   * que sin el la peticion no llega al metodo: Spring contesta 422 «Falta el parametro
   * obligatorio 'ejercicio'». El ejercicio es el de la barra global, que es el mismo con el que
   * se piden las señas del conjunto sellado.
   *
   * <b>Recibe un `number` y no un `string` desde #181</b>, y no es cosmetico: el ejercicio sale
   * de `SesionDeLaVentanilla.ejercicioDeTrabajo`, que es `number | null`. Con la firma de texto,
   * un `String(sesion.ejercicioDeTrabajo)` sobre el nulo daria la cadena `"null"` y saldria a la
   * red como `?ejercicio=null` — un 422 en vez de un rojo del compilador. Con esta, el nulo no
   * compila y quien decide que hacer con el es `useDatosDeLaHoja`, que no pide nada y lo dice.
   *
   * <b>La ventana entra por `ventana` y ya no esta escrita aqui</b> (#186): la bitacora del
   * artboard lleva <b>84 182</b> movimientos, asi que la tabla es una ventana de verdad —`pagina`,
   * `tamano`, `ordenarPor` y `direccion` salen de la ruta de la hoja y del `paginacion.tamano` que
   * su tabla declara—. Los cuatro estan entre los nueve opcionales que `parametros-de-la-api.json`
   * publica para esta operacion.
   *
   * Los otros cinco —`usuario`, `tabla`, `operacion`, `desde` y `hasta`— <b>siguen sin mandarse</b>:
   * son los mandos del formulario de filtro, y esos son de #172.
   */
  bitacoraDe: (ejercicio: number, ventana: Readonly<Record<string, string>> = {}) =>
    conParametros(`/seguridad/auditoria?ejercicio=${encodeURIComponent(String(ejercicio))}`, ventana),
  arbitrios: '/rentas/arbitrios',
  /**
   * El catalogo CIIU, **una ventana y no la lista entera** (#168, #172, #186).
   *
   * El catalogo tiene **1 842 giros** —lo dice el propio artboard, que por eso elige un Combobox y
   * no un Select—, asi que esta tabla siempre fue una ventana. **Lo contrario seria
   * `?tamano=1842`**: mil ochocientas filas en una tabla que ensena cuatro no las lee nadie.
   *
   * <h2>Y desde #186 el tamano NO se escribe aqui</h2>
   *
   * Lo declara la tabla —`paginacion.tamano` en `definiciones/autorizaciones-y-licencias.ts`— y lo
   * lee `laVentanaQueSePide`. Escrito en los dos sitios, el dia que uno suba, los mandos contarian
   * paginas de cien sobre respuestas de veinte (#186, AC3).
   *
   * Lo que entra por `ventana` son los cuatro del dialecto de paginacion —`pagina`, `tamano`,
   * `ordenarPor`, `direccion`— y **`descripcion`**, que es el buscador que el artboard dibuja
   * («Buscar giro o actividad»). Los cinco los publica el contrato
   * (`docs/50-api/parametros-de-la-api.json`), que es la condicion para mandarlos (#26).
   */
  ciiu: (ventana: Readonly<Record<string, string>> = {}) =>
    conParametros('/licencias/ciiu', ventana),
  /**
   * El padron de licencias de funcionamiento (#168, #186).
   *
   * Se pide **sin criterio**: los seis campos con que el artboard la filtra —ejercicio, tipo de
   * licencia, estado, agrupacion y el par Desde/Hasta— **no son parametros de esta operacion**.
   * Los que admite son otros (`nroLicencia`, `nombreDelContribuyente`, `denominacionComercial`,
   * `direccionDelEstablecimiento`, `nExpediente`, `ordenarPor`, `pagina`, `tamano`, `sentido`), y
   * mandar un `?ejercicio=` que el contrato no declara seria construir sobre un nombre que nada de
   * este repositorio puede comprobar — el mismo motivo por el que `/rentas/predios` estuvo fuera
   * hasta #26.
   *
   * Lo que entra desde #186 es **la ventana**: `?pagina=` y `?tamano=`. **Y desde #226 tambien el
   * orden**: el filtro del domicilio se llamaba `direccion`, igual que el sentido del orden, y
   * hasta que se renombro no habia por donde mandar el sentido sin acotar ademas el padron a las
   * licencias cuya direccion contiene esa palabra. Desde #236 el sentido se llama `sentido` y el
   * choque no puede volver: la palabra del dominio es del dominio.
   */
  licenciasDeFuncionamiento: (ventana: Readonly<Record<string, string>> = {}) =>
    conParametros('/licencias/funcionamiento', ventana),
  /**
   * La papeleta que `tra-pap` dibuja: **la primera de la relacion, sin filtrar** (#180).
   *
   * `?tamano=1` por lo mismo que `expedientesCoactivos`: esta pantalla ensena los actos de UNA
   * papeleta y todavia no tiene con que elegirla —su caja de busqueda entra en #172—, asi que
   * pedir veinte para usar una seria pedir diecinueve que nadie mira.
   *
   * **Sin ningun criterio, y hay que decirlo sin mentir**: lo que llega es la primera del padron
   * de papeletas de transito ordenado por `fechaInfraccion`, no «la papeleta de nadie en
   * concreto». El dia que la pantalla sepa pasarle lo tecleado a su conector, lo que cambia es
   * esta linea: los seis criterios estan publicados en `parametros-de-la-api.json`
   * —`nroPapeleta`, `placa`, `documentoDelInfractor`, `desde`, `hasta` y `estado`—.
   *
   * <h2>Y NO pagina, que es lo que #228 midio y dejo escrito</h2>
   *
   * La tabla de esta hoja son los ACTOS de una papeleta, **no una relacion**, y este `?tamano=1` sirve para elegir
   * CUAL se dibuja: paginar esta lectura **no paginaria esa tabla**. Asi que lo que le falta no
   * son los mandos —que #186 dejo instalados y que `fis-prog` estrena en #228— sino el
   * **selector**, y un selector es un filtro: `kamayuk-lib`#94 —el interprete guarda lo tecleado
   * en el estado de `<Pantalla>` y no lo publica por ningun lado— y #172. Escrito aqui para que no
   * haya que volver a medirlo.
   */
  papeletas: '/transito/papeletas?tamano=1',
  /**
   * Todos los documentos emitidos por UNA papeleta, con sus acuses.
   *
   * `{numero}` es el numero impreso y sale de la relacion de arriba: sin una papeleta elegida no
   * hay expediente que pedir. **Sin `?familia=`**, que es opcional y por omision vale
   * `TRANSITO` — que es justo la familia de esta hoja.
   */
  actosDeLaPapeleta: (numero: string) =>
    `/transito/papeletas/${encodeURIComponent(numero)}/actos`,
  /**
   * La grilla «Vehiculos en deposito», **sin filtrar** (#180, #186).
   *
   * Es el deposito entero y no el del vehiculo de la direccion: la tabla de esta hoja es la del
   * deposito, y acotarla a una placa la convertiria en otra cosa. Los cuatro criterios que la
   * operacion admite —`placa`, `deposito`, `estado`, `aLaFecha`— siguen sin tener por donde
   * entrar: son mandos del formulario, y eso es lo que #172 dejo abierto.
   *
   * El artboard dibuja «3 de 188», o sea que la tabla **siempre** fue una ventana sobre el
   * deposito y no su inventario. Desde #186 lo es de verdad: el tamano lo declara la tabla y la
   * pagina viene de la ruta.
   */
  internamientos: (ventana: Readonly<Record<string, string>> = {}) =>
    conParametros('/transito/internamientos', ventana),
  /**
   * Los internamientos de UNA placa, para los campos que son de **ese** vehiculo (#180).
   *
   * `?placa=` lo publica el contrato (`parametros-de-la-api.json`), y es lo que separa las dos
   * lecturas de esta hoja: la de arriba llena la tabla del deposito y esta dice cuantos dias lleva
   * dentro el vehiculo que nombra la direccion. Buscar esa fila **dentro** de la pagina de arriba
   * seria decir «no publicado» cada vez que el vehiculo no cayera entre las veinte primeras.
   */
  internamientosDe: (placa: string) =>
    `/transito/internamientos?placa=${encodeURIComponent(placa)}&tamano=1`,
  /**
   * La ficha de UN vehiculo, por su placa (#180).
   *
   * La placa va **en la ruta** y no en la cadena de consulta: es la unica de las operaciones
   * encendidas hasta hoy que lo hace asi. Sin placa no se pide nada —la hoja lo dice—, porque lo
   * unico que se podria pedir en su lugar es el padron vehicular entero.
   */
  vehiculoDe: (placa: string) => `/rentas/vehiculos/${encodeURIComponent(placa)}`,
  /**
   * El resumen de papeletas del ejercicio en curso, **agrupado por ano** (#184).
   *
   * <h2>`?agrupadoPor=ANO` escrito, aunque sea el valor por omision</h2>
   *
   * Por lo mismo que el `?tamano=20` de `ciiu` y de `internamientos`: lo que decide la **forma** de
   * la respuesta tiene que estar a la vista de quien lea esta linea, y no escondido en un valor por
   * omision del backend que puede cambiar sin avisar —de hecho ya cambio una vez: hasta #398 el
   * agrupador por omision era `ESTADO`—. El parametro esta entre los tres opcionales que
   * `parametros-de-la-api.json` publica para esta operacion.
   *
   * <h2>Y es el agrupador que hace que la respuesta traiga UNA linea</h2>
   *
   * No se mandan `desde` ni `hasta`, asi que el backend acota al **ejercicio en curso de su reloj**
   * —del 1 de enero al 31 de diciembre— y lo dice dentro de la respuesta. Un rango de un ano
   * natural agrupado por ano da **exactamente un grupo**, y de esa linea salen «Canceladas» y «En
   * coactiva», que son cuentas del ejercicio entero y no de un trozo suyo. Es una invariante de lo
   * que se pide, no una suposicion sobre lo que llega: el conector la **comprueba**, y si alguna
   * vez no se cumple dice «no publicado» en vez de dibujar un subconjunto (ver `conectores/transito.ts`).
   *
   * El dia que el desplegable «Ejercicio» de la pantalla sepa pasarle lo elegido a su conector, lo
   * que cambia es esta linea: `desde` y `hasta` estan publicados, y el hueco es #172.
   */
  resumenDePapeletas: '/transito/reportes/resumen-papeletas?agrupadoPor=ANO',
  calculoIndividual: '/rentas/predial/calculo-individual',
  calculoMasivo: '/rentas/predial/calculo-masivo',
  calculoVehicular: '/rentas/vehicular/calculo',
  alcabala: '/rentas/alcabala',
  espectaculos: '/rentas/espectaculos',
  conjuntoSellado: (ejercicio: string) => `/seguridad/parametros/ejercicios/${ejercicio}`,
} as const;

/**
 * **Una ruta con sus parametros, escritos una sola vez y siempre codificados** (#172).
 *
 * Hace falta desde que lo que se manda no es fijo: la pagina, el campo de orden y lo tecleado en un
 * buscador salen de la ruta de la hoja, asi que la cadena de consulta se COMPONE en vez de estar
 * escrita. Componerla a mano en cada conector es como se llega a un `?` de mas, a un `&` de menos y
 * a un valor sin codificar — y un codigo con una barra dentro ya rompio esto una vez (#26).
 *
 * Un parametro vacio **no viaja**: `?descripcion=` no es «buscar la cadena vacia», es no buscar.
 */
export function conParametros(
  ruta: string,
  parametros: Readonly<Record<string, string>>,
): string {
  const partes = Object.entries(parametros)
    .filter(([, valor]) => valor !== '')
    .map(([nombre, valor]) => `${nombre}=${encodeURIComponent(valor)}`);
  if (partes.length === 0) return ruta;
  return `${ruta}${ruta.includes('?') ? '&' : '?'}${partes.join('&')}`;
}

/** Pide una operacion paginada y devuelve solo su contenido. */
export async function pedirLista<T>(ruta: string, senal?: AbortSignal): Promise<readonly T[]> {
  const pagina = await solicitar<Paginado<T>>(ruta, senal === undefined ? {} : { senal });
  return pagina.contenido;
}

/**
 * Pide una operacion paginada y devuelve **el envoltorio entero**.
 *
 * Los cinco campos que `pedirLista` tira son los que hacen falta en cuanto la lista no cabe en
 * una respuesta: `totalElementos` es la cuenta del backend —y no se recalcula—, `totalPaginas` y
 * `hayMas` dicen si «Siguiente» lleva a alguna parte, y `pagina` y `tamano` son el eco de lo que
 * se pidio, que es lo unico que permite comprobar que la ventana servida es la pedida.
 */
export async function pedirPagina<T>(ruta: string, senal?: AbortSignal): Promise<Paginado<T>> {
  return solicitar<Paginado<T>>(ruta, senal === undefined ? {} : { senal });
}

/** Pide una operacion que contesta un objeto. */
export async function pedirUno<T>(ruta: string, senal?: AbortSignal): Promise<T> {
  return solicitar<T>(ruta, senal === undefined ? {} : { senal });
}

/**
 * **Pide una operacion que contesta un objeto O un 204** (#237).
 *
 * Es `pedirUno` con el tipo que dice la verdad. Dos de las lecturas servidas contestan **204 sin
 * cuerpo** cuando la respuesta es «todavia no» —`GET /rentas/predial/corridas/ultima` desde #523 y
 * `GET /rentas/predial/determinaciones` desde #207—, y eso no es un fallo ni una lista vacia: es un
 * hecho del negocio que `useDatosDeLaHoja` dice con su propia frase.
 *
 * <h2>Por que hace falta la puerta y no basta con que `solicitar` devuelva `null`</h2>
 *
 * Porque `pedirUno<CorridaDelPredial>` promete una corrida, y con un 204 devuelve `null`: el
 * conector reparte campo a campo sobre `null` y el compilador **no lo ve**. Declarado
 * `T | null`, quien pide un 204 tiene que decidir que hace con el vacio — que es exactamente la
 * decision que esta hoja tiene que tomar.
 */
export async function pedirUnoOVacio<T>(ruta: string, senal?: AbortSignal): Promise<T | null> {
  return solicitar<T | null>(ruta, senal === undefined ? {} : { senal });
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
