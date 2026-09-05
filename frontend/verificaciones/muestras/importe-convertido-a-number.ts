// Viola: un importe es texto y pierde centimos como number (regla 1, RNF-055).
//
// La conversion es la forma educada de romper la regla anterior: el tipo dice `string`, y
// tres lineas mas abajo alguien lo convierte «solo para ordenar».

export function comoNumero(cuota: { importe: string }) {
  return Number(cuota.importe);
}

export function tambienProhibido(recibo: { total: string }) {
  return parseFloat(recibo.total);
}
