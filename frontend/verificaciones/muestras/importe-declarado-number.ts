// Viola: un importe se declara «string», nunca «number» (regla 1, RNF-055).
//
// `NUMERIC(14,2)` en la base y `BigDecimal` en el backend no sirven de nada si el ultimo
// tramo lo mete en un `double` de JavaScript: 8 372.15 sobrevive, la suma de mil cuotas no.

export interface CuotaEnPantalla {
  readonly numero: number;
  readonly total: number;
}

export function mostrar(importe: number) {
  return importe.toFixed(2);
}
