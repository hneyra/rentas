/**
 * El marcador de carga.
 *
 * **El artboard no lo dibuja.** Lo que declara es `hint-placeholder-count` en
 * cada `sc-for`, que le dice al editor cuantas filas fingir mientras se disena;
 * no es un estado de la aplicacion. Asi que este componente es nuestro, y hace
 * lo minimo: ocupar el sitio que ocupara el dato, con el barrido que
 * `prefers-reduced-motion` apaga desde `base.css`.
 *
 * Lleva `aria-hidden` y no `role="progressbar"`: quien usa lector de pantalla no
 * necesita oir cuarenta rectangulos: necesita que la region que se esta cargando
 * lo diga una vez, y eso lo declara la pantalla con `aria-busy`, no cada barra.
 */
export interface EsqueletoProps {
  /** El alto en pixeles. Por omision, el de una linea de texto de tabla. */
  readonly alto?: number;
  /** El ancho, como medida CSS: `'100%'`, `'12ch'`, `'80px'`. */
  readonly ancho?: string;
}

export function Esqueleto({ alto = 14, ancho = '100%' }: EsqueletoProps) {
  return (
    <span className="kr-esqueleto" style={{ height: alto, width: ancho }} aria-hidden="true" />
  );
}
