/**
 * Los tipos del **arbol** de este sistema: sus modulos, sus hojas y las operaciones que cada hoja
 * declara (UI-5, #85).
 *
 * <h2>Aqui ya NO estan los tipos de una pantalla (#153)</h2>
 *
 * Hasta #153 este archivo traia tambien la otra mitad —el campo, la tabla, el bloque y la pantalla
 * como dato— con el interprete que la dibujaba al lado. Esa mitad **subio a `@kamayuk/ui`** con el
 * interprete (`kamayuk-lib`#27) y alli se llama `DefinicionDePantalla`, `DefinicionDeBloque`,
 * `DefinicionDeCampo`, `DefinicionDeTabla` y `ColumnaDeTabla`: `Campo` y `Tabla` ya eran piezas de
 * ese paquete. Las cuarenta definiciones la importan de alli.
 *
 * Lo que se queda es lo que la libreria **no puede saber**: que modulos tiene Rentas, que hojas
 * cuelgan de cada uno y que operaciones de su backend declaran. Es la regla de ADR-0030 §4 —una
 * libreria comun no sabe cuales son los modulos de un sistema— y es lo que #27 AC1 dejo fuera.
 *
 * Que el interprete no vuelva a este arbol lo vigila `verificaciones/el-interprete-es-de-la-libreria.test.ts`.
 */


/**
 * El verbo con que una hoja declara una operacion del backend.
 *
 * `BASE` no es un verbo HTTP: marca una ruta de la que solo se leyo el `@RequestMapping` de la
 * clase. El controlador cuelga de ahi, pero sus metodos no se han verificado — y no se
 * inventan. Lo dice el propio artboard en el comentario que precede a `const ARBOL`.
 */
export type Verbo = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'BASE';

/** Una operacion del backend que la hoja declara servir. */
export interface Operacion {
  readonly verbo: Verbo;
  readonly ruta: string;
  /** De donde sale: el controlador que la publica, o que hace. */
  readonly nota: string;
}

/**
 * Una pieza de shadcn que la hoja declara, con para que.
 *
 * Son **solo las que el formulario no puede delatar**: las derivadas —un `Select` porque hay un
 * desplegable, un `Calendar` porque hay una fecha— las deduce el interprete de la propia
 * definicion, y repetirlas aqui seria una copia que se queda vieja.
 */
export interface PiezaDeclarada {
  readonly pieza: string;
  readonly uso: string;
}

/** Una hoja del arbol: un submodulo, y la pantalla que abre. */
export interface Hoja {
  /** La clave que empareja la hoja con su pantalla, y la que viaja al hash. */
  readonly clave: string;
  readonly rotulo: string;
  readonly operaciones: readonly Operacion[];
  readonly piezasDeclaradas: readonly PiezaDeclarada[];
}

/** Un modulo del arbol, con sus cuatro hojas. */
export interface Modulo {
  readonly rotulo: string;
  /** La linea de debajo del rotulo: de que va el modulo. */
  readonly nota: string;
  /**
   * El segmento del modulo en el hash. El artboard lo llama `clave` en su comentario; el issue,
   * slug. Es la misma posicion y la misma cadena.
   */
  readonly slug: string;
  /**
   * El codigo con que `GET /seguridad/modulos` publica este mismo modulo.
   *
   * Es la llave del empalme con lo que la instalacion publica, y por eso no se empalma por el
   * rotulo: el dia que alguien corrija «Tránsito» a «Tránsito y transporte», empalmar por
   * nombre haria desaparecer el modulo del arbol **en silencio**.
   */
  readonly codigo: string;
  /** Los trazos del icono, tal cual. Un icono redibujado a ojo se nota al lado de los que no. */
  readonly trazos: readonly string[];
  readonly hojas: readonly Hoja[];
}

/**
 * **Los verbos que CAMBIAN datos**, y la unica pregunta que contesta si una hoja escribe (#291).
 *
 * <h2>`BASE` no esta, y no puede estar</h2>
 *
 * `BASE` significa «solo se leyo el `@RequestMapping` de la clase», o sea que el verbo **no se
 * sabe**. Meterlo aqui seria afirmar que escribe, que es justo la clase de invencion que este
 * arbol existe para evitar — y tiene consecuencia medida: `fis-actas` declara tres `BASE` y nada
 * mas, asi que con `BASE` dentro su pie ofreceria «Guardar» por una ruta cuyo verbo nadie ha
 * verificado.
 *
 * <h2>Por que aqui, junto a `Verbo`, y no dos copias</h2>
 *
 * Porque lo leen dos sitios —`catalogo.ts`, para el pie, y `porQueNoHayDato.ts`, para la frase de
 * «solo escribe»— y son la misma pregunta. `porQueNoHayDato.ts` tenia su propio `Set` con un
 * `DELETE` de mas que `Verbo` ni siquiera admite: dos listas de lo mismo, que es el modo de fallo
 * que #254, #277 y #281 vienen persiguiendo. Una.
 */
export const VERBOS_QUE_ESCRIBEN: ReadonlySet<Verbo> = new Set<Verbo>(['POST', 'PUT', 'PATCH']);

/**
 * **Si esta hoja declara alguna operacion que escriba** — lo que decide su pie (#291).
 *
 * <h2>Lo que esta pregunta sustituye, y por que</h2>
 *
 * Hasta #291, `catalogo.ts` contestaba «la hoja se escribe» con **«tiene algun campo que no sea de
 * solo lectura»**. Medido sobre las cuarenta: eso da **39 de 40**, porque en un tablero los campos
 * que «se escriben» son **los filtros** —un desplegable de ejercicio, un «Desde» y un «Hasta», un
 * buscador—, y un filtro no se guarda: se consulta con el. Con esas 39, el armazon ponia
 * «Limpiar»+«Guardar» y el aviso «Nada se escribe hasta que pulse Guardar.» al lado de un
 * «Guardar» **deshabilitado**, porque `ACCIONES` de `aplicacion.tsx` solo atiende `imprimir`.
 *
 * Contestandola con el VERBO salen **15 de 40**, y las diez pantallas de panel —`ini-panel`,
 * `panel`, `fis-panel`, `tra-panel`, `inf-panel`, `con-panel`, `coa-panel`, `aut-panel`,
 * `seg-panel`, `val-panel`— caen todas del lado de consulta, que es donde estaban.
 *
 * <h2>Por que el verbo y no una marca de «esto es un filtro»</h2>
 *
 * Porque **nada del campo distingue un filtro de un dato que se guarda**, y esta medido con
 * parejas del mismo tipo: `d` «Desde» en `ini-flujo` es un filtro y `d` «Fecha de inspección» en
 * `fis-actas` es un dato del acta; `s` «Ejercicio» en `val-panel` es un filtro y `s` «Etapa» en
 * `coa-exp` es un dato del expediente. Tampoco lo distingue el bloque: `ini-flujo` lleva sus
 * filtros sobre una tabla y `fis-actas` lleva su formulario sobre otra, y en la definicion los dos
 * son un bloque con `tabla`. Marcarlo serian **302 decisiones a mano** que el artboard no tiene
 * con que confirmar — y las definiciones existen justamente para poder compararse con el campo por
 * campo.
 *
 * El verbo, en cambio, **ya esta transcrito del artboard** en `arbol.ts`, se compara con el, y ya
 * lo lee otra pieza de este sistema.
 *
 * Y hay un tercer motivo, que aparecio solo: #288 tuvo que **parchear** la regla vieja para que
 * recorriera «solo los bloques», porque una pantalla puede traer ahora una pieza del consumidor
 * —el grafico de `ini-flujo`— que no tiene campos del interprete. Cada forma nueva de contenido
 * obligaba a volver a esa funcion. Preguntar por el verbo no depende de con que se dibuje la
 * pantalla.
 *
 * <h2>Lo que esto NO afirma</h2>
 *
 * Que la pantalla pueda guardar hoy. No puede: de las 15, la unica cuya escritura el backend sirve
 * es `seg-sis` —`PUT /seguridad/sesion/ejercicio`, la unica escritura encendida de esta interfaz—
 * y ni siquiera esa pasa por el pie. Lo que afirma es lo que el artboard dice de la hoja: que lo
 * que se hace ahi es escribir. Que el boton llegue a hacerlo es otro asunto, y `ACCIONES` es quien
 * lo tiene.
 */
export function laHojaEscribe(hoja: Hoja): boolean {
  return hoja.operaciones.some((operacion) => VERBOS_QUE_ESCRIBEN.has(operacion.verbo));
}
