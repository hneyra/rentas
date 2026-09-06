/**
 * Cuantos digitos tiene cada documento de identidad, y como se teclea.
 *
 * <h2>Por que vive en `dominio/` y no en la pantalla</h2>
 *
 * Porque es una REGLA, no un dato del artboard: un DNI tiene ocho digitos en el Perú lo dibuje
 * quien lo dibuje. Aqui es pura y se prueba sin montar nada, que es lo que pide la regla 7 para
 * el dominio del backend y vale igual aqui.
 *
 * Y **no llega del backend**, aunque deberia: ninguna de las 181 operaciones de
 * `docs/50-api/formas-de-la-api.json` publica el catalogo de tipos de documento con su
 * longitud. Mientras no lo publique, la comprobacion de longitud la hace la pantalla para que
 * el usuario no envie un DNI de siete digitos y espere a que el servidor se lo rechace; **la
 * que decide sigue siendo la del servidor**, que es la unica que ve el padron entero.
 *
 * Los tres son los de `DOCS` del artboard (`RentasV6.dc.html:969`), y que sigan siendo esos lo
 * comprueba `verificaciones/secciones-del-artboard.test.ts` leyendo el `.dc.html`.
 */

/** Tipo de documento -> cuantos digitos tiene. */
const LONGITUDES: Readonly<Record<string, number>> = {
  DNI: 8,
  RUC: 11,
  'Carnet de extranjería': 12,
};

/** Los tres tipos, en el orden del artboard. El primero es el que sale por omision. */
export const TIPOS_DE_DOCUMENTO: readonly string[] = Object.keys(LONGITUDES);

/** Lo que ofrece el desplegable cuando no se ha elegido nada. */
export const TIPO_POR_OMISION = 'DNI';

/**
 * Cuantos digitos tiene ese tipo de documento.
 *
 * Revienta con un tipo que no conoce **en vez de suponer ocho**: un tipo desconocido con la
 * longitud del DNI aceptaria un carnet de extranjeria de ocho digitos y lo mandaria al padron.
 */
export function longitudDe(tipo: string): number {
  const largo = LONGITUDES[tipo];
  if (largo === undefined) {
    throw new Error(
      `«${tipo}» no es un tipo de documento conocido. Los que hay: ${TIPOS_DE_DOCUMENTO.join(', ')}.`,
    );
  }
  return largo;
}

/**
 * Lo tecleado, dejado en digitos y recortado a la longitud del tipo.
 *
 * Es la misma limpieza del artboard —`replace(/[^0-9]/g, '').slice(0, largo)`—: teclear letras
 * en un DNI no es un error que haya que anunciar, es una tecla que no cuenta.
 */
export function soloDigitos(tecleado: string, largo: number): string {
  return tecleado.replace(/[^0-9]/g, '').slice(0, largo);
}

/** Si el numero esta completo para su tipo. Ni uno menos ni uno mas. */
export function documentoCompleto(numero: string, tipo: string): boolean {
  return numero.length === longitudDe(tipo);
}
