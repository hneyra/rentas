import {
  Boton,
  Insignia,
  Tabla,
  TablaCabecera,
  TablaCelda,
  TablaCuerpo,
  TablaFila,
  TablaNota,
  TablaRotulo,
  TarjetaBarraDeTabla,
} from '@kamayuk/ui';

import type { Ausencia } from '../datos.ts';
import type { Tabla as Definicion } from '../tipos.ts';
import { tonoDe } from '../tono.ts';

/**
 * La tabla de un bloque: su barra, la rejilla y la nota de debajo.
 *
 * <h2>El ancho minimo sale de las columnas, y no de un numero escrito</h2>
 *
 * 130 px por columna, que es lo que hace el artboard. Escribir un `min-width` fijo obligaria a
 * tocarlo cada vez que una tabla gane o pierda una columna, y la que se quedara corta apretaria
 * sus celdas hasta partir las cifras.
 *
 * <h2>La nota va FUERA de la tabla, a proposito</h2>
 *
 * Dentro seria una fila mas, y un lector de pantalla la contaria como dato. Fuera es lo que es:
 * lo que hay que saber para leer la tabla sin equivocarse.
 *
 * <h2>Las FILAS entran por parametro, y sin ellas la tabla lo dice (#97)</h2>
 *
 * Hasta #97 venian dentro de la definicion —113 filas de cifras de ejemplo que viajaban en el
 * paquete servido—. Lo que la definicion conserva es la FORMA: que columnas hay, cual va a la
 * derecha, cual es la insignia. Con eso la tabla se dibuja igual de bien con dato o sin el.
 *
 * **Sin filas se pinta la cabecera y una linea que dice por que.** No se esconde la tabla: que
 * columnas tiene una lista es informacion, y esconderla obligaria a adivinar que se va a ver
 * cuando haya datos. Y el conteo **no se inventa**: sin filas no dice «0 registros» —que es una
 * afirmacion— sino nada.
 */

export interface TablaDelBloqueProps {
  readonly tabla: Definicion;
  /** Las filas que se sepan. Ausente: no hay dato, y se dice. */
  readonly filas?: readonly (readonly string[])[];
  /** El conteo del encabezado, si quien pide los datos lo sabe. */
  readonly conteo?: string;
  readonly ausencia: Ausencia;
}

export function TablaDelBloque({ tabla, filas, conteo, ausencia }: TablaDelBloqueProps) {
  // El conteo se cuenta solo cuando HAY filas. Sin ellas no se escribe «0 registros»: contar cero
  // sobre una lista que nadie ha pedido es afirmar que esta vacia, y no se sabe.
  const rotuloDelConteo =
    filas === undefined
      ? null
      : (conteo ?? `${filas.length} ${filas.length === 1 ? 'registro' : 'registros'}`);

  return (
    <div>
      <TarjetaBarraDeTabla>
        <p className="m-0 flex-1 min-w-[140px] text-[13px] font-bold">{tabla.titulo}</p>
        {rotuloDelConteo === null ? null : (
          <span className="text-[11.5px] text-tinta-3">{rotuloDelConteo}</span>
        )}
        {tabla.accion === undefined ? null : (
          <Boton type="button" tamano="menudo">
            {tabla.accion}
          </Boton>
        )}
      </TarjetaBarraDeTabla>

      <Tabla style={{ minWidth: `${tabla.columnas.length * 130}px` }}>
        <TablaCabecera>
          <TablaFila>
            {tabla.columnas.map((c) => (
              <TablaRotulo key={c.rotulo} cifra={c.alineadoDerecha}>
                {c.rotulo}
              </TablaRotulo>
            ))}
          </TablaFila>
        </TablaCabecera>
        <TablaCuerpo>
          {(filas ?? []).map((fila, i) => (
            // La clave es la fila entera y no el indice: dos filas nunca son iguales en estas
            // tablas —llevan su identificador en la primera celda— y con el indice, reordenar
            // deja a React reusando la fila equivocada.
            <TablaFila key={fila.join('|')} impar={i % 2 === 1}>
              {fila.map((celda, j) =>
                j === tabla.columnaDeInsignia ? (
                  <TablaCelda key={tabla.columnas[j]?.rotulo ?? j}>
                    <Insignia tono={tonoDe(celda)}>{celda}</Insignia>
                  </TablaCelda>
                ) : (
                  <TablaCelda
                    key={tabla.columnas[j]?.rotulo ?? j}
                    cifra={tabla.columnas[j]?.alineadoDerecha === true}
                    identifica={j === 0}
                  >
                    {celda}
                  </TablaCelda>
                ),
              )}
            </TablaFila>
          ))}
        </TablaCuerpo>
      </Tabla>

      {filas === undefined ? (
        <p
          data-sin-dato=""
          className="m-0 px-[15px] py-[10px] bg-sup text-[12px] leading-[1.5] text-tinta-3 italic text-pretty"
        >
          {ausencia.enElCampo}
        </p>
      ) : null}

      {tabla.nota === undefined ? null : <TablaNota>{tabla.nota}</TablaNota>}
    </div>
  );
}
