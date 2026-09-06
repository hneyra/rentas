/**
 * La escalera de identidad, leida desde la pantalla.
 *
 * <h2>Los cuatro peldanos son cuatro remedios distintos, y por eso no se pueden juntar</h2>
 *
 * Medido con `curl` contra la instalacion, la cadena de seguridad del backend contesta cuatro
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
 * Y se arreglan de cuatro maneras que no se parecen: volver a identificarse; que el
 * administrador asigne la municipalidad a la cuenta; pedir el permiso que falta; y revisar con
 * que cuenta se esta entrando. Ensenar «no se pudo» para las cuatro obliga a quien atiende a
 * llamar por telefono para averiguar cual de las cuatro es — y las cuatro llegan ya
 * distinguidas en el `codigo`, que es una extension del contrato y no una frase.
 *
 * <h2>Dos de ellas NO son averias, y decirlo importa</h2>
 *
 * `SIN_PRIVILEGIO` y `SIN_MUNICIPALIDAD` son el sistema funcionando: contesto lo que tenia que
 * contestar. Pintarlas de rojo de «algo se rompio» manda a mirar un despliegue cuando lo que
 * falta es una fila en una tabla de permisos. `esAveria` es lo que separa las dos cosas, y la
 * pantalla lo usa para elegir el tono y para decidir si ofrece reintentar.
 *
 * <h2>Es una funcion pura, y eso es deliberado</h2>
 *
 * Sin React, sin `fetch` y sin reloj: entra un fallo, sale que decir. Los cuatro peldanos se
 * prueban sin montar nada, y la pantalla que los ensena se prueba una vez.
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
      titulo: 'No se encontro lo solicitado',
      // Tal cual. El 404 de esta escalera es «el token identifica a 'X', que no es un usuario
      // de esta municipalidad», y esa frase nombra la cuenta: resumirla borraria el unico dato
      // con el que se arregla.
      detalle: loQueDijo(fallo, `El backend no encontro «${fallo.operacion}».`),
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
    titulo: 'El sistema no pudo contestar',
    detalle: `${loQueDijo(fallo, fallo.operacion)} (${String(fallo.estado)})`,
    remedio: 'Reintente en unos segundos. Si sigue igual, avise a soporte con este mensaje.',
    pideIdentidad: false,
    esAveria: true,
  };
}
