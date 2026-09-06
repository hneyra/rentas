/**
 * El icono de un modulo o de una seccion, dibujado desde sus trazos.
 *
 * **No es un noveno componente del sistema de diseno, y por eso no vive en
 * `src/ds/`.** `Icono` tiene un catalogo cerrado —nueve trazos con nombre— y esa
 * es su gracia: quien lo usa no puede meter un SVG dibujado a ojo. Aqui los
 * trazos son DATO, vienen del arbol, y son cuarenta y dos: meterlos en el
 * catalogo de `Icono` lo convertiria en un vertedero de rutas.
 *
 * Igual que `Icono`, es decorativo por definicion: `aria-hidden`, sin etiqueta.
 * El significado lo pone el texto que va al lado, y si no hay texto al lado es
 * que falta el texto.
 */
export interface TrazosProps {
  readonly trazos: readonly string[];
  /** El lado de la caja, en pixeles. El artboard usa 13, 14, 16 y 17. */
  readonly tamano?: number;
}

export function Trazos({ trazos, tamano = 14 }: TrazosProps) {
  return (
    <svg
      width={tamano}
      height={tamano}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {trazos.map((trazo) => (
        <path key={trazo} d={trazo} />
      ))}
    </svg>
  );
}
