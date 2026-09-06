// Viola: un importe se muestra con la fecha a la que esta calculado (regla 9, RNF-075).
//
// La declaracion local existe para que la muestra se pueda linkar sola, sin resolver
// `../../src/ds`. Lo que la regla mira es el JSX, no de donde sale el componente — que es
// justamente lo que la hace util: caza tambien un `<Importe>` que alguien haya declarado
// por su cuenta.
function Importe(_props: { valor: string; fechaCalculo?: string }) {
  return null;
}

export function FilaDeCuentaCorriente() {
  return <Importe valor="1842.60" />;
}
