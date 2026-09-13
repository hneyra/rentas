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
