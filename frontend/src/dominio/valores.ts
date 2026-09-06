/**
 * Los tipos que el sistema de disenio necesita del dominio.
 *
 * Son tres, y ninguno es una clase: lo que llega del backend es JSON, y envolverlo
 * aqui obligaria a desenvolverlo antes de cada `fetch`. Lo que si hacen es
 * **nombrar** lo que un `string` suelto no nombra.
 */

/**
 * Un importe, tal como el backend lo sirve: **texto decimal, jamas `number`**.
 *
 * Regla 1 (RNF-055). En coma flotante `0.1 + 0.2` no es `0.30`, y el centimo se
 * pierde antes de llegar a la pantalla. Lo vigilan dos prohibiciones de ESLint
 * —`importe-declarado-number` y `importe-convertido-a-number`— con sus muestras.
 *
 * El punto es el separador decimal y no hay separador de miles: `"1842.60"`. Lo
 * que lleva comas es lo que se MUESTRA, y de eso se encarga `formatearImporte`.
 */
export type Importe = string;

/**
 * Una fecha, en ISO 8601 y sin hora: `"2026-09-06"`.
 *
 * Sin hora a proposito. Una fecha de calculo con hora invita a construir un
 * `Date`, y un `Date` en el navegador arrastra la zona horaria del puesto: el
 * mismo `"2026-09-06T00:00:00Z"` es el 5 de septiembre en Lima. Como texto no
 * hay nada que interpretar mal.
 */
export type Fecha = string;

/**
 * El tono de una insignia: los cuatro de `const INS` del artboard.
 *
 * En castellano, que en el artboard estaban en ingles (`warn`, `bad`). Un tono
 * **nunca** viaja solo: `Insignia` exige tambien el texto, porque un estado que
 * se comunica solo por color no se comunica a quien no distingue ese color.
 */
export type Tono = 'ok' | 'atencion' | 'mal' | 'info';
