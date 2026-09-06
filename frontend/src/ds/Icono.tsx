/**
 * Iconografia: SVG de linea, sin libreria y sin emoji.
 *
 * Los trazos **estan copiados de `frontend/diseno/RentasV6.dc.html`**, uno a uno
 * y con la linea donde vive cada uno. No se redibujaron: un icono redibujado a
 * ojo se nota al lado de los que no lo estan.
 *
 * La reja del artboard: `viewBox` 24x24, `stroke` de 1.7, extremos y uniones
 * redondeados, y el color heredado con `currentColor`. Que herede el color es lo
 * que hace que un icono dentro de un boton primario salga blanco y el mismo
 * icono dentro de un aviso salga desvaido, sin que nadie le pase un color.
 *
 * **Un icono es decorativo por definicion.** Lleva `aria-hidden` y no admite
 * etiqueta: el significado lo pone el texto que va al lado, y si no hay texto al
 * lado es que falta el texto, no que falte un `aria-label`.
 */

/**
 * Los trazos, tal como el artboard los escribe. Cada entrada del array es un
 * `<path>`; cuando el artboard mete dos subtrazos en una misma `d`, se conserva
 * asi — separarlos cambiaria el SVG que se pinta.
 */
const TRAZOS = {
  /** El icono del modulo «Consultas» (`MODULOS`, artboard). */
  lupa: ['M17.4 11a6.4 6.4 0 1 1-12.8 0 6.4 6.4 0 0 1 12.8 0', 'M15.8 15.8 20.6 20.6'],
  /** El expediente: es el icono del estado vacio de la ficha. */
  expediente: ['M6.5 3.5h7.5l4 4v13h-11.5z', 'M14 3.5v4h4', 'M9.5 12.5h5'],
  /** El triangulo de «Infracciones administrativas», con su admiracion. */
  alerta: ['M12 4.2 20.8 19.6H3.2z', 'M12 7.6V13M12 16.4h.02'],
  /** El candado del artboard (la contrasenia). Es el icono de «sin permiso». */
  candado: ['M7 11V8a5 5 0 0 1 10 0v3', 'M5.5 11h13v9.5h-13z'],
  visto: ['M5 12.5l4.5 4.5L19 7'],
  mas: ['M12 5v14M5 12h14'],
  cerrar: ['M6 6l12 12M18 6L6 18'],
  chevronDerecha: ['M10 6l6 6-6 6'],
  chevronAbajo: ['M6 9.5l6 6 6-6'],
} as const;

export type NombreDeIcono = keyof typeof TRAZOS;

export interface IconoProps {
  readonly nombre: NombreDeIcono;
  /** El lado de la caja, en pixeles. El artboard usa 14, 15, 16, 24 y 30. */
  readonly tamano?: number;
  /** El grosor del trazo. El artboard lo afina a 1.5 en los iconos grandes. */
  readonly grosor?: number;
}

export function Icono({ nombre, tamano = 16, grosor = 1.7 }: IconoProps) {
  return (
    <svg
      width={tamano}
      height={tamano}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={grosor}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {TRAZOS[nombre].map((trazo) => (
        <path key={trazo} d={trazo} />
      ))}
    </svg>
  );
}
