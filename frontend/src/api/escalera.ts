/**
 * La escalera de identidad, leida desde la pantalla.
 *
 * <h2>Los cinco peldanos medidos son cinco remedios distintos, y por eso no se pueden juntar</h2>
 *
 * Medido con `curl` contra la instalacion, la cadena de seguridad del backend contesta cinco
 * cosas distintas a la misma peticion segun quien la haga:
 *
 * <table>
 *   <tr><td>sin token</td><td><b>401</b> `NO_AUTENTICADO`</td></tr>
 *   <tr><td>token sin el claim `municipalidad_id`</td><td><b>403</b> `SIN_MUNICIPALIDAD`</td></tr>
 *   <tr><td>token sin el permiso que la operacion pide</td><td><b>403</b> `SIN_PRIVILEGIO`</td></tr>
 *   <tr><td>la cuenta no es usuario de esa municipalidad</td><td><b>404</b> `NO_ENCONTRADO`</td></tr>
 *   <tr><td>el cuerpo incumple una regla del dominio</td><td><b>422</b> `VALIDACION`</td></tr>
 * </table>
 *
 * <h2>El quinto peldano lo trajo la primera escritura (I-3)</h2>
 *
 * Hasta #31 esta interfaz solo leia, y un 422 no podia llegar. Con
 * `PUT /seguridad/sesion/ejercicio` llega, y es **la respuesta mas probable del acto**: medido
 * contra la instalacion, una observacion de tres letras contesta «La observacion debe explicar
 * el cambio: al menos 5 caracteres…» y un ejercicio de 1800, «Ejercicio fuera de rango: 1800.
 * Se admite de 1990 a 2100». Sin este peldano las dos caian en `averia`, o sea que escribir
 * «ok» en un campo mandaba a **avisar a soporte** — y con el tono de que algo se rompio.
 *
 * Y se arreglan de maneras que no se parecen: volver a identificarse; que el administrador
 * asigne la municipalidad a la cuenta; pedir el permiso que falta; revisar con que cuenta se
 * esta entrando; y corregir lo que se escribio. Ensenar «no se pudo» para todas obliga a quien
 * atiende a llamar por telefono para averiguar cual es — y llegan ya distinguidas en el
 * `codigo`, que es una extension del contrato y no una frase.
 *
 * A los cinco medidos se suman **dos que no salen de un `curl` y hacen falta igual**: un 403 sin
 * `codigo` —`no-permitido`, que no se hace pasar por ninguno de los dos que si lo llevan— y
 * `averia`, que recoge el corte de red y el 5xx. Siete claves en total, cinco medidas y dos de
 * respaldo; el `clave` de abajo es la cuenta que vale.
 *
 * <h2>Dos de ellas NO son averias, y decirlo importa</h2>
 *
 * `SIN_PRIVILEGIO` y `SIN_MUNICIPALIDAD` son el sistema funcionando: contesto lo que tenia que
 * contestar. Pintarlas de rojo de «algo se rompio» manda a mirar un despliegue cuando lo que
 * falta es una fila en una tabla de permisos. `esAveria` es lo que separa las dos cosas, y desde
 * #283 la pantalla lo usa **de verdad** para elegir el tono: `atencion` solo cuando algo se
 * rompio, `info` cuando el backend contesto lo que tenia que contestar.
 *
 * <h2>Es una funcion pura, y eso es deliberado</h2>
 *
 * Sin React, sin `fetch` y sin reloj: entra un fallo, sale que decir. Los siete peldanos se
 * prueban sin montar nada.
 *
 * <h2>Y DESDE #283 LA DIBUJA UNA PANTALLA. Que cupo, que no, y donde vive lo que falta</h2>
 *
 * Hasta #283 el unico `import` de este archivo era su prueba, y su javadoc lo decia con las dos
 * medidas que lo sostenian. Ahora tiene **un** consumidor de produccion y solo uno:
 * `datos/useDatosDeLaHoja.ts`, cuyo `alFallar` ya no tiene escalera propia —tenia tres respuestas,
 * `SIN_SESION` para el 401, `SIN_PERMISO` para **cualquier** 403 y `FALLO` con el codigo
 * interpolado para todo lo demas— sino que pregunta aqui y traduce el peldano a la `Ausencia` que
 * el interprete sabe dibujar. Lo que se gana es exactamente lo que #262 midio que faltaba: los
 * **dos** 403 dejan de leerse igual —uno lo arregla el administrador del emisor, el otro quien
 * administre los perfiles— y el **422** deja de salir como «fallo (422)», o sea como una averia.
 *
 * <h2>Como cabe un peldano de siete campos en una ausencia de tres</h2>
 *
 * No se ensancho nada. Se midio que **seis de los siete campos ya tenian donde ir**:
 *
 * <table>
 *   <tr><td>`enElHueco`</td><td>→ `Ausencia.enElCampo`</td></tr>
 *   <tr><td>`titulo`, `detalle`, `remedio`</td><td>→ `Ausencia.explicacion`, armada con `t()` y
 *     `FRASE_DEL_PELDANO`: los tres entran por interpolacion, que es lo unico que deja al
 *     traductor decidir el orden</td></tr>
 *   <tr><td>`esAveria`</td><td>→ `Ausencia.tono`: `atencion` si lo es, `info` si no</td></tr>
 *   <tr><td>`clave`</td><td>no se dibuja: es el identificador que las pruebas nombran</td></tr>
 * </table>
 *
 * El que **no** cabe es `pideIdentidad`, porque es un **boton** y el interprete no dibuja ninguno
 * en el hueco de una ausencia. De los siete peldanos lo lleva **uno** —`sin-identidad`—, asi que
 * lo que se pierde hoy es el atajo de ese caso, no su frase: «Vuelva a identificarse para seguir
 * trabajando» se lee igual. Ensanchar `Ausencia` para que lo lleve es de `@kamayuk/ui`, o sea de
 * los **seis** consumidores de `consumidores.json`, y por eso se pide alli y no se hace aqui.
 *
 * <h2>Y por que no se retiro, que era la otra salida</h2>
 *
 * Porque los cinco peldanos medidos son `curl` contra una instalacion levantada —con su realm, su
 * token y su cuenta—, y este puesto no la tiene: borrarlos tira una medida que no se puede
 * rehacer aqui. Es lo contrario de `dominio/aritmetica.ts`, que #262 si retiro: aquello era una
 * suma que cualquiera vuelve a escribir en diez minutos. Que siga habiendo **un** consumidor y no
 * cero ni dos lo vigila `escalera.test.ts`, que sale rojo nombrando los archivos.
 */

import { ErrorDeLaApi } from './cliente.ts';

/** Que decir, y que ofrecer, ante un fallo de la API. */
export interface Peldano {
  /** Identificador estable del peldano. Es lo que las pruebas nombran. */
  readonly clave:
    | 'sin-identidad'
    | 'sin-municipalidad'
    | 'sin-privilegio'
    | 'no-encontrado'
    | 'no-permitido'
    | 'no-valido'
    | 'averia';
  /**
   * **La palabra del hueco de un campo: una o dos, en minuscula.**
   *
   * Es el unico campo que no sale del `curl`: lo pide el sitio donde el peldano se DIBUJA. Una
   * `Ausencia` de `@kamayuk/ui` tiene tres campos, y el primero —`enElCampo`— es «lo corto, dentro
   * del hueco de un campo». Sin esta palabra, quien enchufa la escalera tiene que escribir siete
   * en otro archivo, y siete frases escritas lejos de su peldano son siete que el inventario del
   * locale puede no alcanzar — que es el defecto que #283 vino a cerrar, no a mover de sitio.
   *
   * Vive con las otras dos frases fijas del peldano —`titulo` y `remedio`— y por eso el centinela
   * de `ninguna-ausencia-se-queda-sin-inventariar.test.ts` la barre con ellas.
   */
  readonly enElHueco: string;
  /**
   * **El estado HTTP, cuando lo hubo. `null` si la peticion no llego a contestar.**
   *
   * Va APARTE de las frases y no pegado a ninguna, y esa es la leccion de #246: un numero dentro
   * de una frase la deja fuera del locale para siempre —la cadena es distinta en cada fallo y
   * ninguna clave puede casar con ella—. Aqui es un dato, y quien dibuja lo mete por
   * interpolacion. Es lo que se dicta a soporte.
   */
  readonly estado: number | null;
  readonly titulo: string;
  /** Lo que paso, en una frase. Cuando el backend lo dice, es lo que el backend dijo. */
  readonly detalle: string;
  /** Que hacer para salir de aqui. Nunca «reintente» a secas. */
  readonly remedio: string;
  /** Si la pantalla ofrece el boton que vuelve a la puerta de identidad. */
  readonly pideIdentidad: boolean;
  /**
   * Si esto es el sistema roto o el sistema funcionando.
   *
   * `false` en los tres peldanos de autorizacion: el backend contesto exactamente lo que tenia
   * que contestar. Solo un fallo de transporte o un 5xx son una averia.
   */
  readonly esAveria: boolean;
}

/** Lo que el backend dijo, o el respaldo si esa respuesta no traia texto. */
function loQueDijo(fallo: ErrorDeLaApi, respaldo: string): string {
  return fallo.mensaje ?? fallo.detalle ?? fallo.titulo ?? respaldo;
}

/**
 * En que peldano de la escalera se ha quedado esta peticion.
 *
 * @param fallo lo que lanzo `solicitar()`. No tiene por que ser un `ErrorDeLaApi`: un corte de
 *   red lanza un `TypeError`, y ese caso tambien tiene que contestar algo.
 */
export function peldanoDe(fallo: unknown): Peldano {
  if (!(fallo instanceof ErrorDeLaApi)) {
    return {
      clave: 'averia',
      enElHueco: 'fallo',
      // No hubo respuesta: no hay estado que dictar, y un 0 escrito aqui seria un estado inventado.
      estado: null,
      titulo: 'El sistema no contesta',
      detalle:
        'La peticion no llego a completarse. El backend puede estar apagado, o este puesto no ' +
        'alcanzarlo.',
      remedio: 'Reintente en unos segundos. Si sigue igual, avise a soporte.',
      pideIdentidad: false,
      esAveria: true,
    };
  }

  if (fallo.estado === 401) {
    return {
      clave: 'sin-identidad',
      enElHueco: 'sin sesion',
      estado: fallo.estado,
      titulo: 'Hay que volver a identificarse',
      detalle: loQueDijo(fallo, 'La peticion no trae un token valido.'),
      remedio:
        'La sesion caduco o todavia no se ha abierto. Vuelva a identificarse para seguir ' +
        'trabajando.',
      pideIdentidad: true,
      esAveria: false,
    };
  }

  if (fallo.estado === 403 && fallo.codigo === 'SIN_MUNICIPALIDAD') {
    return {
      clave: 'sin-municipalidad',
      enElHueco: 'sin municipalidad',
      estado: fallo.estado,
      titulo: 'Esta cuenta no tiene municipalidad asignada',
      detalle: loQueDijo(fallo, 'El token no identifica una municipalidad.'),
      remedio:
        'La cuenta existe y entro bien, pero no dice de que municipalidad es, y sin eso no hay ' +
        'padron que ensenar. Lo asigna el administrador del sistema en el emisor de identidad.',
      // No se ofrece volver a la puerta: entrar otra vez con la misma cuenta trae el mismo
      // token y el mismo 403. Lo que falta esta del lado del administrador, no del navegador.
      pideIdentidad: false,
      esAveria: false,
    };
  }

  if (fallo.estado === 403 && fallo.codigo === 'SIN_PRIVILEGIO') {
    return {
      clave: 'sin-privilegio',
      enElHueco: 'sin permiso',
      estado: fallo.estado,
      titulo: 'Falta un permiso para esta operacion',
      detalle: loQueDijo(fallo, 'La cuenta no tiene el privilegio que esta operacion pide.'),
      remedio:
        'No es una averia: el sistema contesto lo que tenia que contestar. Pida el permiso a ' +
        'quien administre los perfiles, indicando que operacion estaba haciendo.',
      pideIdentidad: false,
      esAveria: false,
    };
  }

  if (fallo.estado === 403) {
    return {
      clave: 'no-permitido',
      enElHueco: 'sin acceso',
      estado: fallo.estado,
      titulo: 'La operacion no se permitio',
      detalle: loQueDijo(fallo, 'El backend rechazo la peticion.'),
      remedio: 'No es una averia. Revise con que cuenta esta trabajando.',
      pideIdentidad: false,
      esAveria: false,
    };
  }

  if (fallo.estado === 404) {
    return {
      clave: 'no-encontrado',
      enElHueco: 'no encontrado',
      estado: fallo.estado,
      titulo: 'No se encontro lo solicitado',
      // Tal cual. El 404 de esta escalera es «el token identifica a 'X', que no es un usuario
      // de esta municipalidad», y esa frase nombra la cuenta: resumirla borraria el unico dato
      // con el que se arregla.
      // El RESPALDO —lo que se lee cuando el backend no dijo nada— nombraba la operacion dentro
      // de la frase, y asi **no puede ser una clave**: la cadena era distinta en cada ruta y
      // ninguna entrada del locale podia casar con ella. Es el defecto de #246 un nivel al lado, y
      // el dato no se pierde: `operacion` sigue en el `ErrorDeLaApi` que se recibio (#283).
      detalle: loQueDijo(fallo, 'El backend no encontro lo que esta pantalla le pidio.'),
      remedio:
        'Revise con que cuenta esta entrando: puede ser valida en el emisor de identidad y no ' +
        'estar dada de alta en esta municipalidad.',
      pideIdentidad: false,
      esAveria: false,
    };
  }

  if (fallo.estado === 422) {
    return {
      clave: 'no-valido',
      enElHueco: 'dato rechazado',
      estado: fallo.estado,
      titulo: 'Lo que se mandó no cumple una regla',
      // Tal cual, y esta es la unica respuesta de la escalera donde el texto del backend NO es
      // un respaldo sino el dato: es la regla concreta que se incumplio, con su cifra dentro
      // —«al menos 5 caracteres», «Se admite de 1990 a 2100»—, y es lo unico con lo que quien
      // esta delante puede corregir lo que escribio. Resumirla a «revise los datos» borraria
      // justo eso. Copiar la regla aqui para adelantarla seria peor: seria tener dos verdades.
      detalle: loQueDijo(fallo, 'El backend rechazo el contenido de la peticion.'),
      remedio: 'Corrija lo que dice el mensaje y vuelva a intentarlo.',
      pideIdentidad: false,
      // No es una averia: el backend leyo la peticion, la entendio y la rechazo por una regla
      // suya. Mandar a soporte por esto es mandar a soporte porque alguien escribio «ok».
      esAveria: false,
    };
  }

  return {
    clave: 'averia',
    enElHueco: 'fallo',
    estado: fallo.estado,
    titulo: 'El sistema no pudo contestar',
    // Ni el estado ni la operacion van pegados a la frase: los dos son datos, la frase es una
    // clave, y juntos no pueden ser ninguna de las dos cosas. El estado se dicta a soporte desde
    // `estado`, que quien dibuja mete por interpolacion (#283).
    detalle: loQueDijo(fallo, 'El backend no pudo completar la peticion.'),
    remedio: 'Reintente en unos segundos. Si sigue igual, avise a soporte con este mensaje.',
    pideIdentidad: false,
    esAveria: true,
  };
}
