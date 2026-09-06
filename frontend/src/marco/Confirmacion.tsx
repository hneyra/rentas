import { Boton } from '../ds/index.ts';

/**
 * Cerrar una pestana con cambios los descarta: se pregunta antes (AC5).
 *
 * **Las tres salidas estan las tres**, y en el orden del artboard: descartar a la
 * izquierda, separada; seguir editando y guardar a la derecha, con guardar de
 * primaria. La salida por omision es guardar, no perder — quien pulsa Enter sin
 * leer no puede acabar tirando lo que escribio.
 *
 * «Descartar y cerrar» no se puede deshacer, y el texto lo dice con esas
 * palabras en vez de con un «¿Está seguro?», que no informa de nada.
 */
export interface ConfirmacionProps {
  readonly rotulo: string;
  readonly alDescartar: () => void;
  readonly alSeguirEditando: () => void;
  readonly alGuardar: () => void;
}

export function Confirmacion({
  rotulo,
  alDescartar,
  alSeguirEditando,
  alGuardar,
}: ConfirmacionProps) {
  return (
    <>
      {/* Pulsar fuera del dialogo es «seguir editando», la salida que no pierde
          nada. Su rotulo NO puede ser «Seguir editando» a secas: seria el mismo
          nombre accesible que el boton de dentro, y quien navega por lista de
          botones oiria dos veces la misma opcion sin poder distinguirlas. */}
      <button
        type="button"
        onClick={alSeguirEditando}
        aria-label="Cerrar el diálogo y seguir editando"
        className="kr-marco__velo"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Cerrar con cambios sin guardar"
        className="kr-marco__dialogo"
      >
        <div className="kr-marco__dialogo-cuerpo">
          <span className="kr-marco__dialogo-icono">
            {/* El circulo va como `<circle>` y no como un arco dibujado a mano:
                es lo que el artboard escribe, y un arco «equivalente» no lo es —
                se nota en el remate del trazo. `Trazos` solo sabe de `<path>`. */}
            <svg
              width="17"
              height="17"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2.1}
              strokeLinecap="round"
              aria-hidden="true"
              focusable="false"
            >
              <circle cx="12" cy="12" r="9" />
              <path d="M12 7.6V13M12 16.4h.02" />
            </svg>
          </span>
          <span className="kr-marco__dialogo-texto">
            <p className="kr-marco__dialogo-titulo">{rotulo} tiene cambios sin guardar</p>
            <p className="kr-marco__dialogo-detalle">
              Si cierra la pestaña se pierden. Guárdelos primero o ciérrela descartándolos: eso
              no se puede deshacer.
            </p>
          </span>
        </div>
        <div className="kr-marco__dialogo-acciones">
          <Boton onClick={alDescartar} className="kr-marco__descartar">
            Descartar y cerrar
          </Boton>
          <span className="kr-marco__separador" />
          <Boton onClick={alSeguirEditando}>Seguir editando</Boton>
          <Boton variante="primario" onClick={alGuardar}>
            Guardar y cerrar
          </Boton>
        </div>
      </div>
    </>
  );
}
