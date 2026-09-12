/**
 * Los tipos de **las cuarenta pantallas de V8 como dato** (UI-5, #85).
 *
 * <h2>Por que dato y no cuarenta componentes</h2>
 *
 * Porque el artboard no dibuja cuarenta pantallas: dibuja **una** que interpreta una tabla.
 * `frontend/diseno/RentasV8.dc.html` tiene un solo `bloques(clave)` —linea ~1278— que toma la
 * definicion de `PANTALLAS[clave]` y la pinta. Cuarenta pantallas escritas a mano divergen a la
 * tercera semana y nadie puede decir cuales; cuarenta definiciones sobre un interprete no
 * pueden, y es lo que hace posible la guarda anti-deriva
 * (`verificaciones/pantallas-del-artboard.test.ts`).
 *
 * <h2>Por que los tipos son ESTRECHOS, y no `string`</h2>
 *
 * El tipo de un campo no es texto libre: son **siete** —y su variante de ancho completo—, y el
 * interprete del artboard no sabe hacer nada con un octavo. Declararlo `string` dejaria que
 * `['Ejercicio', 'select', […]]` compilara y se dibujara como una caja de texto vacia, en
 * silencio. Con la union, un tipo inventado no llega ni al `yarn build`.
 *
 * Y va mas lejos que la union: el **tercer elemento** de un campo significa una cosa distinta
 * segun el tipo —las opciones en un desplegable, el valor que se muestra en uno de solo
 * lectura, la etiqueta de la casilla en una casilla, la ayuda en los demas—, asi que `Campo` es
 * una **union discriminada** por `tipo` y cada rama nombra su tercer elemento por lo que es.
 * Que las tres primeras ramas lo exijan SIEMPRE no es una suposicion: esta medido sobre el
 * artboard —90 desplegables con opciones y ninguno sin ellas, 102 campos de solo lectura con su
 * valor, 14 casillas con su etiqueta—.
 *
 * <h2>Esto no dibuja nada todavia</h2>
 *
 * AC8 del issue: aqui hay dato y guarda. El interprete y las piezas de shadcn llegan aparte
 * (`kamayuk-lib`#11), y hasta entonces la interfaz que se sirve sigue siendo la V6, intacta.
 */

/* ── El campo ──────────────────────────────────────────────────────────────────────────── */

/**
 * Los **siete** tipos de campo del artboard, sin la marca de ancho.
 *
 * - `''` texto · `s` desplegable de lista cerrada · `d` fecha · `r` solo lectura
 * - `c` casilla · `a` area de texto · `t` texto, sinonimo de `''`
 *
 * `t` esta porque el interprete lo acepta —`esTexto: base === '' || base === 't'`—, aunque el
 * artboard de hoy no lo use en ninguno de sus 302 campos. Se declara igualmente: quitarlo
 * convertiria en error de compilacion una definicion que el prototipo dibuja bien.
 */
export type TipoBaseDeCampo = '' | 's' | 'd' | 'r' | 'c' | 'a' | 't';

/**
 * El mismo tipo con la marca de **ancho completo**.
 *
 * Un `1` al final es lo que el interprete busca —`const ancho = t.indexOf('1') >= 0`— para
 * sacar el campo de la rejilla y darle la fila entera. Por eso `''` y `'1'` son el mismo tipo
 * de control con distinto ancho, y no dos tipos.
 */
export type ConAnchoCompleto<T extends string> = T | `${T}1`;

/** Los catorce valores que un `tipo` puede tomar: los siete, con y sin ancho completo. */
export type TipoDeCampo = ConAnchoCompleto<TipoBaseDeCampo>;

/** Un desplegable de lista cerrada. Sus opciones son el dato; sin ellas no dibuja nada. */
export interface CampoDeLista {
  readonly etiqueta: string;
  readonly tipo: ConAnchoCompleto<'s'>;
  /** Las opciones, en su orden. La primera es la que el interprete deja seleccionada. */
  readonly opciones: readonly string[];
}

/** Un campo que solo se muestra: lo calcula el backend y la ventanilla no lo escribe. */
export interface CampoDeSoloLectura {
  readonly etiqueta: string;
  readonly tipo: ConAnchoCompleto<'r'>;
  /** Lo que se muestra. En el artboard es una cifra de ejemplo; en la aplicacion, la de verdad. */
  readonly valor: string;
}

/** Una casilla. Su texto no es ayuda: es lo que se lee AL LADO de la marca. */
export interface CampoDeCasilla {
  readonly etiqueta: string;
  readonly tipo: ConAnchoCompleto<'c'>;
  /** La etiqueta de la marca: «Autoriza notificar al correo declarado». */
  readonly casilla: string;
}

/** Los tipos que se escriben: texto, fecha y area. */
export type TipoDeEntrada = ConAnchoCompleto<'' | 'd' | 'a' | 't'>;

/** Un campo que se escribe, con su ayuda opcional debajo. */
export interface CampoDeEntrada {
  readonly etiqueta: string;
  readonly tipo: TipoDeEntrada;
  /** La linea de ayuda. La mayoria no la lleva. */
  readonly ayuda?: string;
}

/** Un campo de un bloque, discriminado por su `tipo`. */
export type Campo = CampoDeLista | CampoDeSoloLectura | CampoDeCasilla | CampoDeEntrada;

/* ── La tabla ──────────────────────────────────────────────────────────────────────────── */

/** Una columna de la tabla de un bloque. */
export interface Columna {
  readonly rotulo: string;
  /**
   * Si la columna va pegada a la derecha.
   *
   * En el artboard es el `1` de `['Importe S/', 1]`, y no es cosmetico: son las columnas de
   * cifras, y una cifra alineada a la izquierda no se puede comparar de un vistazo con la de
   * la fila de arriba.
   */
  readonly alineadoDerecha: boolean;
}

/** La tabla que acompana a un bloque. Treinta y uno de los 45 bloques la llevan. */
export interface Tabla {
  readonly titulo: string;
  readonly columnas: readonly Columna[];
  /** Las filas, cada una con tantas celdas como columnas. */
  readonly filas: readonly (readonly string[])[];
  /** La linea de debajo: lo que hay que saber para leer la tabla sin equivocarse. */
  readonly nota?: string;
  /**
   * El indice de la columna que se dibuja como insignia, si hay una.
   *
   * Es la columna de situacion —«Conforme», «Vencida», «En coactiva»—, y el interprete le da
   * color por el texto (`tono(t)` del artboard).
   */
  readonly columnaDeInsignia?: number;
  /** El rotulo del boton de alta, si la lista admite anadir una fila. */
  readonly accion?: string;
  /** El conteo del encabezado: «4 de 62,418». Sin el, el interprete cuenta las filas. */
  readonly conteo?: string;
}

/* ── El bloque y la pantalla ───────────────────────────────────────────────────────────── */

/** Un grupo de campos con su titulo, su nota y —a veces— su tabla. */
export interface Bloque {
  readonly titulo: string;
  /** Que ES esta parte de la pantalla. Vacia cuando el titulo ya lo dice todo. */
  readonly nota: string;
  /** Los campos del grupo. Vacio en los bloques que solo traen una tabla. */
  readonly campos: readonly Campo[];
  readonly tabla?: Tabla;
}

/** Una de las cuarenta pantallas. */
export interface Pantalla {
  /**
   * La linea de la barra de instruccion: **que hay que hacer aqui**.
   *
   * Es distinta de la nota del primer bloque, que dice que ES la pantalla. En el artboard vive
   * en `INSTRUCCIONES`, en un objeto aparte; aqui va DENTRO de la pantalla a proposito: dos
   * registros paralelos de cuarenta claves se desincronizan, y una pantalla nueva sin
   * instruccion no daria ningun error.
   */
  readonly instruccion: string;
  readonly bloques: readonly Bloque[];
}

/* ── El arbol ──────────────────────────────────────────────────────────────────────────── */

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

/* ── Reconocer la rama de un campo ─────────────────────────────────────────────────────── */
//
// Por que hacen falta estas tres, y no vale `campo.tipo === 's' || campo.tipo === 's1'`: el
// compilador solo descarta una rama de la union cuando su discriminante es UN literal. Con
// `'s' | 's1'` sabe entrar en la rama, y no sabe salir de ella —comprobado: en la rama negativa
// sigue viendo `CampoDeLista` y se queja de que no tiene `ayuda`—. Reconocerlas por el campo que
// solo ellas traen funciona en las dos direcciones.
//
// Y viven aqui, junto al tipo, y no en quien las use: «solo el desplegable trae opciones» es una
// propiedad de la forma del dato, y una segunda copia suya en otro archivo es una que puede
// decir otra cosa.

/** Un desplegable: es el unico campo que trae `opciones`. */
export const esCampoDeLista = (campo: Campo): campo is CampoDeLista => 'opciones' in campo;

/** Un campo de solo lectura: es el unico que trae `valor`. */
export const esCampoDeSoloLectura = (campo: Campo): campo is CampoDeSoloLectura =>
  'valor' in campo;

/** Una casilla: es la unica que trae `casilla`. */
export const esCampoDeCasilla = (campo: Campo): campo is CampoDeCasilla => 'casilla' in campo;
