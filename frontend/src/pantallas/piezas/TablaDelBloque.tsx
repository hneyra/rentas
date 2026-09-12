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
 */

export function TablaDelBloque({ tabla }: { readonly tabla: Definicion }) {
  const conteo =
    tabla.conteo ?? `${tabla.filas.length} ${tabla.filas.length === 1 ? 'registro' : 'registros'}`;

  return (
    <div>
      <TarjetaBarraDeTabla>
        <p className="m-0 flex-1 min-w-[140px] text-[13px] font-bold">{tabla.titulo}</p>
        <span className="text-[11.5px] text-tinta-3">{conteo}</span>
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
          {tabla.filas.map((fila, i) => (
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

      {tabla.nota === undefined ? null : <TablaNota>{tabla.nota}</TablaNota>}
    </div>
  );
}
