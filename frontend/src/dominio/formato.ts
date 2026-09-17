import type { Fecha, Importe, Instante } from './valores.ts';

/**
 * Como se escriben un importe y una fecha en la pantalla.
 *
 * Las dos funciones son **puras y trabajan sobre texto**. Ninguna construye un
 * `Number` ni un `Date`, y no es una preferencia de estilo:
 *
 *   · Un `Number` pierde centimos (regla 1). `Number("0.1") + Number("0.2")` no
 *     es `0.3`, y aunque aqui no se sume, convertir y volver a escribir ya
 *     redondea: `String(Number("412880.005"))` da `"412880.005"` hoy y no hay
 *     nada que prometa que seguira dando eso con quince digitos por delante.
 *   · Un `Date` arrastra la zona horaria del puesto. `new Date("2026-09-06")` se
 *     interpreta en UTC y se imprime en local: en Lima sale el **5**. Un estado
 *     de cuenta que cambia de dia segun donde este el navegador no es un detalle
 *     de formato.
 *
 * Y ninguna redondea. Si llega un importe con tres decimales, esta funcion
 * **falla** en vez de recortarlo: recortar es aritmetica, la decide el backend
 * con su `NUMERIC(x,2)`, y un centimo que desaparece al pintarlo no deja rastro
 * en ningun sitio.
 */

/** El separador de miles del artboard: `S/ 1,842.60`. */
const MILES = ',';

/** El separador decimal: el mismo que trae el dato, asi que no se traduce. */
const DECIMAL = '.';

/** Los soles, como el artboard los escribe: simbolo, espacio, cifra. */
const MONEDA = 'S/';

/** Un importe servido por el backend: opcionalmente negativo, con 0..2 decimales. */
const IMPORTE_SERVIDO = /^-?\d+(\.\d{1,2})?$/;

/** Una fecha ISO sin hora. */
const FECHA_SERVIDA = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * `"1842.6"` -> `"S/ 1,842.60"`.
 *
 * Agrupa de tres en tres y completa a dos decimales. Lo hace con texto, asi que
 * un importe de quince digitos sale igual de exacto que uno de tres.
 */
export function formatearImporte(valor: Importe): string {
  const limpio = valor.trim();

  if (!IMPORTE_SERVIDO.test(limpio)) {
    // Falla ruidosamente y nombra el valor. La alternativa —devolver el texto
    // tal cual— pinta «412880.005» en una columna de importes y nadie lo mira
    // dos veces; la otra —recortar— pierde el centimo en silencio.
    throw new Error(
      `Importe con una forma que el backend no sirve: «${valor}». ` +
        'Se espera texto decimal con dos decimales como mucho, sin separador de miles. ' +
        'Redondear aqui seria aritmetica sobre dinero (regla 1, RNF-055).',
    );
  }

  const negativo = limpio.startsWith('-');
  const sinSigno = negativo ? limpio.slice(1) : limpio;
  const [enteraCruda, decimalesCrudos] = sinSigno.split(DECIMAL);

  // `?? ''` y no `!`: con `noUncheckedIndexedAccess` el compilador no da por
  // hecho que `split` devolvio algo, y tiene razon aunque la expresion regular
  // ya lo garantice.
  const entera = (enteraCruda ?? '').replace(/^0+(?=\d)/, '');
  const decimales = `${decimalesCrudos ?? ''}00`.slice(0, 2);
  const agrupada = entera.replace(/\B(?=(\d{3})+(?!\d))/g, MILES);

  return `${negativo ? '-' : ''}${MONEDA} ${agrupada}${DECIMAL}${decimales}`;
}

/**
 * `1842` -> `"1,842"`: un CONTEO, agrupado como el artboard agrupa las cifras (#172).
 *
 * No es `formatearImporte` sin el simbolo, y por eso no lo reutiliza: un importe llega como
 * **texto** desde el backend y esta funcion recibe un `number`, porque lo que cuenta es un conteo
 * —`totalElementos`— y un conteo no es dinero. Sin decimales, por lo mismo: no hay medio
 * expediente.
 *
 * Revienta con lo que no es un entero no negativo en vez de escribirlo tal cual: un `1.5` o un
 * `NaN` en el encabezado de una tabla se lee como un dato del padron.
 */
export function formatearEntero(cuantos: number): string {
  if (!Number.isInteger(cuantos) || cuantos < 0) {
    throw new Error(
      `Un conteo es un entero no negativo, y llego «${String(cuantos)}». ` +
        'Escribirlo tal cual pondria esa cifra en el encabezado de una tabla, donde se lee como ' +
        'el tamano del padron.',
    );
  }
  return String(cuantos).replace(/\B(?=(\d{3})+(?!\d))/g, MILES);
}

/**
 * `"2026-09-06"` -> `"06/09/2026"`, que es como el artboard escribe las fechas.
 */
export function formatearFecha(fecha: Fecha): string {
  const partes = FECHA_SERVIDA.exec(fecha.trim());

  if (partes === null) {
    throw new Error(
      `Fecha con una forma que el backend no sirve: «${fecha}». Se espera ISO 8601 sin hora, «2026-09-06».`,
    );
  }

  const [, anio, mes, dia] = partes;
  return `${dia}/${mes}/${anio}`;
}

/**
 * Un instante ISO 8601 en UTC: `2026-08-13T14:41:12Z`. Los segundos y los milisegundos sobran.
 */
const INSTANTE_SERVIDO = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?Z$/;

/**
 * `"2026-08-13T14:41:12Z"` -> `"13/08/2026 14:41 UTC"` (#181).
 *
 * <h2>Por que dice «UTC» y no lo mueve a la hora de Lima</h2>
 *
 * Porque moverlo es aritmetica sobre un instante, y este archivo entero existe para no hacerla:
 * la unica forma de convertir es construir un `Date` —que arrastra la zona del PUESTO, no la de
 * la municipalidad— o restar cinco horas a mano, que cruza medianoche y cambia el dia. Las dos
 * pintarian una hora distinta de la que el servidor publico **sin que nada lo dijera**, y esta
 * cifra va en una bitacora de auditoria: la hora a la que alguien anulo un recibo es lo que se
 * presenta cuando alguien pregunta.
 *
 * Asi que se escribe lo que llego, con la marca de zona a la vista. Es feo y es cierto. Lo que lo
 * cerraria de verdad es que el backend publique la fecha ya en la zona de la municipalidad —como
 * ya hace `CorridaDelPredial.fechaCalculo`, que llega redactada—, y eso es del dueno de
 * `seguridad`: es **#188**, con sus dos formas medidas. Mientras no lo haga, la alternativa era
 * ensenar una hora equivocada con cara de exacta.
 *
 * Los segundos se dejan fuera porque la columna del artboard escribe `13/08/2026 09:41`.
 */
export function formatearInstante(instante: Instante): string {
  const partes = INSTANTE_SERVIDO.exec(instante.trim());

  if (partes === null) {
    throw new Error(
      `Instante con una forma que el backend no sirve: «${instante}». Se espera ISO 8601 en UTC, ` +
        '«2026-08-13T14:41:12Z». Un `Instant` de Java sale asi; una fecha sin hora es `Fecha`, y ' +
        'la escribe `formatearFecha`.',
    );
  }

  const [, anio, mes, dia, hora, minuto] = partes;
  return `${dia}/${mes}/${anio} ${hora}:${minuto} UTC`;
}

/**
 * Ordena dos importes **sin convertirlos a numero**.
 *
 * Ordenar una lista por deuda es lo que pide el artboard, y la manera obvia —`Number(a) -
 * Number(b)`— es la prohibida (regla 1, y la prohibicion `importe-convertido-a-number` de
 * ESLint): en coma flotante dos importes que se diferencian en un centimo a partir de
 * diecisiete digitos comparan iguales, y ordenar por una comparacion que a veces dice «iguales»
 * cuando no lo son cambia el orden de la lista segun por donde se empiece.
 *
 * Se compara como texto y sale exacto: primero el signo, luego la parte entera **por longitud**
 * —«100» pesa mas que «99» aunque «1» < «9»— y a igual longitud lexicograficamente, que para
 * digitos es el orden numerico; despues los decimales, completados a dos.
 *
 * Devuelve el negativo/cero/positivo que espera `Array.prototype.sort`.
 */
export function compararImportes(a: Importe, b: Importe): number {
  const parteDe = (valor: Importe) => {
    const limpio = valor.trim();
    if (!IMPORTE_SERVIDO.test(limpio)) {
      throw new Error(
        `Importe con una forma que el backend no sirve: «${valor}». No se puede ordenar por el.`,
      );
    }
    const negativo = limpio.startsWith('-');
    const sinSigno = negativo ? limpio.slice(1) : limpio;
    const [entera, decimales] = sinSigno.split(DECIMAL);
    return {
      signo: negativo ? -1 : 1,
      entera: (entera ?? '').replace(/^0+(?=\d)/, ''),
      decimales: `${decimales ?? ''}00`.slice(0, 2),
    };
  };

  const uno = parteDe(a);
  const otro = parteDe(b);

  if (uno.signo !== otro.signo) {
    return uno.signo - otro.signo;
  }
  if (uno.entera.length !== otro.entera.length) {
    return uno.signo * (uno.entera.length - otro.entera.length);
  }
  if (uno.entera !== otro.entera) {
    return uno.signo * (uno.entera < otro.entera ? -1 : 1);
  }
  if (uno.decimales === otro.decimales) {
    return 0;
  }
  return uno.signo * (uno.decimales < otro.decimales ? -1 : 1);
}

/** Los doce meses, en minuscula, como los escribe el artboard: «al 31 de agosto». */
const MESES = [
  'enero',
  'febrero',
  'marzo',
  'abril',
  'mayo',
  'junio',
  'julio',
  'agosto',
  'septiembre',
  'octubre',
  'noviembre',
  'diciembre',
];

/**
 * `"2026-08-31"` -> `"31 de agosto"`.
 *
 * **Sin el ano, y a proposito**: es la fecha de corte de un panel que ya dice de que ejercicio
 * es, y asi la escribe el artboard. Donde haga falta la fecha completa esta `formatearFecha`.
 *
 * Sin `Date` y sin `Intl`, por lo mismo que el resto de este archivo: `Intl.DateTimeFormat`
 * necesita construir un `Date`, y `new Date("2026-08-31")` se interpreta en UTC y se imprime en
 * local — en Lima sale el 30 de agosto—. Una tabla de doce nombres no tiene ese problema.
 */
export function formatearFechaEnPalabras(fecha: Fecha): string {
  const partes = FECHA_SERVIDA.exec(fecha.trim());

  if (partes === null) {
    throw new Error(
      `Fecha con una forma que el backend no sirve: «${fecha}». Se espera ISO 8601 sin hora, «2026-09-06».`,
    );
  }

  const [, , mes, dia] = partes;
  const nombre = MESES[Number(mes) - 1];
  if (nombre === undefined) {
    throw new Error(`Fecha con un mes que no existe: «${fecha}».`);
  }

  // Sin el cero de la izquierda: «1 de enero», no «01 de enero».
  return `${String(Number(dia))} de ${nombre}`;
}
