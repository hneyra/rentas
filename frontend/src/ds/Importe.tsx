import { formatearFecha, formatearImporte } from '../dominio/formato.ts';
import type { Fecha, Importe as ImporteDecimal } from '../dominio/valores.ts';

/**
 * Un importe, **con la fecha a la que esta calculado**.
 *
 * `fechaCalculo` es obligatoria y NO tiene valor por omision. Es la regla 9
 * llevada al tipo: no existe «la deuda», existe `deudaActualizadaA(fecha)`
 * (RNF-075). En ventanilla eso no es un detalle de formato — es la diferencia
 * entre responder «debe 1,842.60» a alguien que pregunta cuanto debe, y
 * responder «debia 1,842.60 anteayer».
 *
 * **Dos barreras, no una**, y las dos se demuestran:
 *
 *   1. El TIPO. `verificaciones/tipos/barreras-de-tipos.tsx` escribe
 *      `<Importe valor="…" />` bajo un `@ts-expect-error`: el dia que
 *      `fechaCalculo` deje de ser obligatoria, ese error no ocurrira y **`tsc`
 *      fallara por la directiva no usada**.
 *   2. ESLint. La prohibicion `importe-sin-fecha` rechaza cualquier
 *      `<Importe>` sin ese atributo, con su muestra en
 *      `verificaciones/muestras/importe-sin-fecha.tsx`.
 *
 * Hacen falta las dos porque no cubren lo mismo. El tipo no ve un
 * `<Importe {...props} />` donde `props` viene de un `any` que se colo por un
 * `JSON.parse`; ESLint no ve un `createElement(Importe, …)`. Y sobre todo: la
 * regla de ESLint sobrevive a que alguien ponga un valor por omision al tipo, y
 * el tipo sobrevive a que alguien apague la regla — que son las dos formas
 * realistas de perder esto.
 *
 * No hace aritmetica: formatea el texto que envio el backend.
 */
export interface ImporteProps {
  readonly valor: ImporteDecimal;
  /** Obligatoria y sin valor por omision. Ver el javadoc del componente. */
  readonly fechaCalculo: Fecha;
  /**
   * Oculta la fecha cuando **ya la dice** la pantalla o la fila.
   *
   * No la hace opcional: `fechaCalculo` sigue habiendo que pasarla. Lo que se
   * evita es repetir «al 06/09/2026» en las cuarenta filas de una tabla que ya
   * lleva su `FechaDeCalculo` arriba.
   */
  readonly fechaImplicita?: boolean;
}

export function Importe({ valor, fechaCalculo, fechaImplicita = false }: ImporteProps) {
  return (
    <span className="kr-importe">
      <span className="kr-importe__valor">{formatearImporte(valor)}</span>
      {!fechaImplicita && (
        <span className="kr-importe__fecha">al {formatearFecha(fechaCalculo)}</span>
      )}
    </span>
  );
}
