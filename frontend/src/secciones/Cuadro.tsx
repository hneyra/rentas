import { Esqueleto, Importe } from '../ds/index.ts';
import type { CeldaDeLaMemoria, ColumnaDeLaMemoria, FilaDeLaMemoria } from './determinacion.ts';

/**
 * El cuadro que dibujan las dos secciones de F-6: la memoria de una determinacion y una tabla
 * de valores.
 *
 * Existe una vez y no dos porque las dos tablas son la misma: cabecera con la **marca de
 * alineacion numerica**, filas de celdas y un guion donde ninguna operacion publica nada. Dos
 * copias divergirian, y la que divergiera perderia el `tabular-nums` en silencio — que es
 * justo lo que hace que una columna de cifras se pueda comparar de un vistazo (AC2).
 *
 * <h2>Ningun importe se dibuja sin su fecha</h2>
 *
 * Una celda de dinero llega como `{ importe, actualizadoA }` y se dibuja con `Importe`, que
 * exige la fecha por tipo (regla 9, RNF-075). `fechaImplicita` no la quita: la calla en la
 * celda porque el cuadro ya la dice arriba, una vez, en vez de repetirla en las nueve filas.
 */
export interface CuadroProps {
  readonly columnas: readonly ColumnaDeLaMemoria[];
  readonly filas: readonly FilaDeLaMemoria[];
  /** Lo que lee un lector de pantalla al llegar a la tabla. */
  readonly rotulo: string;
  readonly cargando: boolean;
  /** Modificador de anchura minima, para que la tabla ancha desplace en su marco y no la pagina. */
  readonly variante: string;
}

/** Lo que ninguna operacion publica. Un guion, y no una celda en blanco. */
const SIN_DATO = '—';

function Celda({ celda, derecha, primera }: { celda: CeldaDeLaMemoria; derecha: boolean; primera: boolean }) {
  if (celda.importe !== null) {
    return (
      <td className="kr-tabla__td--cifra">
        <Importe
          valor={celda.importe.importe}
          fechaCalculo={celda.importe.actualizadoA}
          fechaImplicita
        />
      </td>
    );
  }

  const clase = derecha
    ? 'kr-tabla__td--cifra'
    : primera
      ? 'kr-tabla__td--clave'
      : undefined;

  if (celda.texto === null) {
    return (
      <td className={clase}>
        <span className="kr-cuadro__sin-dato" title="Ninguna operación publica este dato">
          {SIN_DATO}
        </span>
      </td>
    );
  }

  return <td className={clase}>{celda.texto}</td>;
}

export function Cuadro({ columnas, filas, rotulo, cargando, variante }: CuadroProps) {
  return (
    <div className="kr-tabla__marco">
      <table
        className={`kr-tabla kr-cuadro kr-cuadro--${variante}`}
        aria-label={rotulo}
        aria-busy={cargando}
      >
        <thead>
          <tr>
            {columnas.map(([etiqueta, derecha], i) => (
              <th
                key={`${etiqueta}-${String(i)}`}
                className={derecha ? 'kr-tabla__th--cifra' : undefined}
              >
                {etiqueta}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {cargando &&
            [0, 1, 2, 3].map((hueco) => (
              <tr key={hueco}>
                {columnas.map(([etiqueta], i) => (
                  <td key={`${etiqueta}-${String(i)}`}>
                    <Esqueleto alto={12} />
                  </td>
                ))}
              </tr>
            ))}
          {!cargando &&
            filas.map((fila) => (
              <tr key={fila.clave}>
                {fila.celdas.map((celda, i) => (
                  <Celda
                    key={`${fila.clave}-${String(i)}`}
                    celda={celda}
                    derecha={columnas[i]?.[1] ?? false}
                    primera={i === 0}
                  />
                ))}
              </tr>
            ))}
        </tbody>
      </table>
    </div>
  );
}
