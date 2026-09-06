/**
 * El vocabulario visual de `rentas-web`: los ocho componentes base.
 *
 * Se importan desde aqui y no de su archivo, para que anadir el noveno sea una
 * linea en este indice y no una ronda de `import` por todas las pantallas.
 *
 * `export type` explicito en cada tipo: con `verbatimModuleSyntax` un tipo
 * reexportado como valor se cuela en el bundle y arrastra su modulo entero.
 */

export { Aviso } from './Aviso.tsx';
export type { AvisoProps, TipoDeAviso } from './Aviso.tsx';

export { Boton } from './Boton.tsx';
export type { BotonProps, VarianteDeBoton } from './Boton.tsx';

export { Campo } from './Campo.tsx';
export type { CampoProps, TipoDeCampo } from './Campo.tsx';

export { Esqueleto } from './Esqueleto.tsx';
export type { EsqueletoProps } from './Esqueleto.tsx';

export { FechaDeCalculo } from './FechaDeCalculo.tsx';
export type { FechaDeCalculoProps } from './FechaDeCalculo.tsx';

export { Icono } from './Icono.tsx';
export type { IconoProps, NombreDeIcono } from './Icono.tsx';

export { Importe } from './Importe.tsx';
export type { ImporteProps } from './Importe.tsx';

export { Insignia } from './Insignia.tsx';
export type { InsigniaProps } from './Insignia.tsx';
