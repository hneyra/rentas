// Viola: la interfaz no hace aritmetica con importes (regla 1, regla 9).
//
// El problema no es solo la precision: es que la cifra sale de una suma que el backend no
// puede sustentar. Cuando el ciudadano pregunte «por que 153.82», la respuesta esta en una
// pantalla y no en el libro de asientos, y no lleva fecha de calculo.

interface Cuota {
  readonly insoluto: string;
  readonly interes: string;
}

export function totalDeLaCuota(cuota: Cuota) {
  return cuota.insoluto + cuota.interes;
}

export function totalDeLaDeuda(deuda: { montos: number[] }) {
  return deuda.montos.reduce((izquierda, derecha) => izquierda + derecha, 0);
}
