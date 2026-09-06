import type { Fecha, Importe } from './valores.ts';

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
