import { useState } from 'react';

import { peldanoDe } from '../api/escalera.ts';
import { Aviso, Boton, Campo } from '../ds/index.ts';

/**
 * El ejercicio de trabajo de la barra: **un acto, y se ve como tal** (I-3, AC3 a AC5).
 *
 * <h2>Por que deja de ser un desplegable</h2>
 *
 * Hasta #31 esto era un `<select>` con cuatro anos: elegir uno cambiaba una variable de esta
 * pestana y lanzaba un aviso flotante que decia «se recargaron la UIT, la escala y las tablas de
 * arbitrios». **No se recargaba nada y no se guardaba nada.** El ejercicio de trabajo vive en la
 * fila `sesion` del backend, se fija con `PUT /seguridad/sesion/ejercicio`, y esa operacion
 * declara `@RequiereAcceso(acceso = "cambiar_anio", privilegio = Privilegio.ESPECIAL)` y exige
 * una observacion. O sea que es **una escritura auditada con privilegio propio**, y un control
 * que cambia de valor al pasar por encima es la forma exacta de no parecerlo.
 *
 * El javadoc del backend lo dice como decision y no como hueco: lo que hay que poder separar es
 * «el filtro de vista, que es local y no necesita permiso» del «acto registrado con su
 * observacion y su privilegio `ESPECIAL`». Esto es lo segundo. Si algun dia hace falta lo
 * primero —mirar 2025 sin cambiar la sesion— sera otro control, con otro nombre, y no este.
 *
 * <h2>El ano se teclea, y la lista de cuatro anos no se porta</h2>
 *
 * El artboard ofrece `['2026','2025','2024','2023']`. **Esa lista es una invencion**: ninguna de
 * las 181 operaciones publica los ejercicios que la municipalidad admite —`GET
 * /seguridad/parametros/ejercicios/{ejercicio}` contesta por UNO y hay que decirle cual—, asi
 * que las cuatro cifras no salen de ningun sitio y las tres primeras ni siquiera son las de esta
 * instalacion. Lo que si esta medido es el rango que el backend acepta, y lo contesta el:
 *
 * <pre>
 * {"ejercicio":1800,…} -> 422 «Ejercicio fuera de rango: 1800. Se admite de 1990 a 2100»
 * </pre>
 *
 * Asi que se teclea, y el rango lo sostiene quien lo conoce. Copiar aqui 1990 y 2100 pondria en
 * la interfaz dos numeros cuya fuente es el dominio del backend — y el dia que cambiaran habria
 * dos verdades y ninguna que lo dijera. Que este archivo no escriba ningun ano lo comprueba
 * `marco-sin-selector.test.ts`, en el mismo escaner que vigila el conmutador A/B/C.
 *
 * <h2>La observacion se pide ANTES de mandar nada</h2>
 *
 * Regla 10 y RNF-052. Y aqui, ademas, rodea un defecto medido: el cuerpo sin `observacion` hace
 * que el backend conteste **500** con un `NullPointerException` (#30, que no se arregla desde
 * aqui). Lo que este componente NO hace es adelantar la regla de longitud: el backend pide al
 * menos cinco caracteres y lo dice con sus palabras, y esas son las que se ensenan. El boton se
 * bloquea solo con el campo **vacio**, que no es duplicar la regla — es no mandar a proposito la
 * unica peticion que se sabe que revienta.
 */

/** Que esta pasando con el acto. */
type Estado = 'cerrado' | 'abierto' | 'enviando';

export interface EjercicioProps {
  /** El que dice el backend, o `null` si nadie lo ha fijado (AC4). */
  readonly ejercicio: number | null;
  /**
   * Si la cuenta tiene `especial` sobre `cambiar_anio`.
   *
   * Con `false` no se dibuja ningun mando: solo el valor. No es esconder una funcion por
   * cosmetica — es no ofrecer una puerta que contesta 403.
   */
  readonly puedeCambiar: boolean;
  /** Manda el `PUT`. Lanza si el backend no lo acepta, y aqui se convierte en un peldano. */
  readonly alCambiar: (ejercicio: number, observacion: string) => Promise<void>;
}

/** Lo que se ensena cuando el backend no ha fijado ninguno. */
const SIN_FIJAR = 'Sin fijar';

export function Ejercicio({ ejercicio, puedeCambiar, alCambiar }: EjercicioProps) {
  const [estado, fijarEstado] = useState<Estado>('cerrado');
  const [anio, fijarAnio] = useState('');
  const [observacion, fijarObservacion] = useState('');
  const [fallo, fijarFallo] = useState<unknown>(null);

  const rotulo = ejercicio === null ? SIN_FIJAR : String(ejercicio);

  const abrir = () => {
    // El campo arranca en el que ya rige, que es lo que casi siempre se quiere corregir a un
    // digito de distancia. Con la sesion sin fijar arranca vacio: rellenarlo con el ano del
    // reloj del puesto seria la misma afirmacion que el backend se niega a hacer.
    fijarAnio(ejercicio === null ? '' : String(ejercicio));
    fijarObservacion('');
    fijarFallo(null);
    fijarEstado('abierto');
  };

  const cerrar = () => {
    fijarEstado('cerrado');
    fijarFallo(null);
  };

  const mandar = () => {
    // `Number` y no `parseInt`: `parseInt('20a6')` da 20 y mandaria un ejercicio que nadie
    // tecleo. `Number('20a6')` da `NaN` y no se manda nada.
    const pedido = Number(anio.trim());
    if (!Number.isInteger(pedido)) {
      return;
    }
    fijarEstado('enviando');
    fijarFallo(null);
    alCambiar(pedido, observacion).then(
      () => {
        fijarEstado('cerrado');
      },
      (motivo: unknown) => {
        // Se queda ABIERTO a proposito: el 422 mas probable es «la observacion es corta», y
        // cerrar el dialogo obligaria a teclear otra vez lo que ya estaba escrito.
        fijarFallo(motivo);
        fijarEstado('abierto');
      },
    );
  };

  if (!puedeCambiar) {
    return (
      <div className="kr-marco__ejercicio">
        <span className="kr-marco__ejercicio-rotulo">Ejercicio</span>
        <span
          className="kr-marco__ejercicio-valor"
          title={
            ejercicio === null
              ? 'Nadie ha fijado el ejercicio de trabajo de esta sesión, y esta cuenta no puede fijarlo.'
              : 'Cambiar el ejercicio de trabajo pide el privilegio «especial» sobre «cambiar_anio», que esta cuenta no tiene.'
          }
        >
          {rotulo}
        </span>
      </div>
    );
  }

  const peldano = fallo === null ? null : peldanoDe(fallo);

  return (
    <div className="kr-marco__ejercicio">
      <span className="kr-marco__ejercicio-rotulo">Ejercicio</span>
      <button
        type="button"
        onClick={abrir}
        aria-haspopup="dialog"
        aria-expanded={estado !== 'cerrado'}
        title={
          ejercicio === null
            ? 'Nadie ha fijado el ejercicio de trabajo de esta sesión. Fijarlo queda registrado.'
            : `Ejercicio de trabajo: ${rotulo}. Cambiarlo queda registrado.`
        }
        className="kr-marco__ejercicio-boton"
      >
        {rotulo}
      </button>

      {estado !== 'cerrado' && (
        <>
          <button
            type="button"
            onClick={cerrar}
            aria-label="Cerrar sin cambiar el ejercicio"
            className="kr-marco__velo"
          />
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Cambiar el ejercicio de trabajo"
            className="kr-marco__dialogo"
          >
            <div className="kr-marco__dialogo-cuerpo kr-marco__dialogo-cuerpo--forma">
              <p className="kr-marco__dialogo-titulo">Cambiar el ejercicio de trabajo</p>
              <p className="kr-marco__dialogo-detalle">
                El ejercicio es de la sesión, no de esta pestaña: al cambiarlo cambia para los
                módulos que estén abiertos. Queda registrado con su observación y con quién lo
                cambió.
              </p>

              {peldano !== null && (
                <Aviso
                  tipo={peldano.esAveria ? 'error' : 'sin-permiso'}
                  titulo={peldano.titulo}
                  detalle={peldano.detalle}
                >
                  <p className="kr-marco__dialogo-detalle">{peldano.remedio}</p>
                </Aviso>
              )}

              <div className="kr-marco__ejercicio-forma">
                <Campo
                  etiqueta="Ejercicio"
                  tipo="text"
                  valor={anio}
                  // «AAAA» y no «2026». Lo cazo la guarda de `marco-sin-selector.test.ts` al
                  // escribirla: un `placeholder` con un ano dentro **es la lista de cuatro anos
                  // otra vez**, con uno solo — sugiere que ese es el ejercicio de esta
                  // municipalidad, y eso no lo ha dicho nadie.
                  ph="AAAA"
                  // El rango lo sabe el backend y lo dice; aqui solo se recuerda que es un año.
                  ayuda="El año de trabajo. Si no se admite, el sistema lo dirá."
                  onCambio={fijarAnio}
                />
                <Campo
                  etiqueta="Observación"
                  tipo="area"
                  valor={observacion}
                  ancho
                  ph="Por qué se cambia el ejercicio de trabajo"
                  ayuda="Queda en la auditoría junto con la cuenta que lo cambió (regla 10)."
                  onCambio={fijarObservacion}
                />
              </div>
            </div>
            <div className="kr-marco__dialogo-acciones">
              <span className="kr-marco__separador" />
              <Boton onClick={cerrar}>Cancelar</Boton>
              <Boton
                variante="primario"
                onClick={mandar}
                disabled={
                  estado === 'enviando' || observacion.trim() === '' || anio.trim() === ''
                }
              >
                {estado === 'enviando' ? 'Cambiando…' : 'Cambiar el ejercicio'}
              </Boton>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
