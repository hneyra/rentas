import { Aviso, Boton, Campo, Icono } from '../ds/index.ts';
import { Contribuyentes } from '../secciones/Contribuyentes.tsx';
import { Panel } from '../secciones/Panel.tsx';
import type { EstadoDelPadron } from '../secciones/estadoDelPadron.ts';
import { HOJAS, esPropia } from './arbol.ts';
import { Trazos } from './Trazos.tsx';

/**
 * El lienzo: lo que hay debajo de las pestanas.
 *
 * Tiene **cinco estados**:
 *
 *   1. **sin pestanas** — no hay nada abierto, y se dice;
 *   2. **una hoja ajena** — la ficha del AC9 de F-3, que explica en que archivo
 *      se disena esa pantalla y deja la pestana abierta para volver a ella;
 *   3. **`panel`** — el panel del modulo (F-5, AC1);
 *   4. **`predios`** — el padron y el expediente (F-5, AC2 a AC9);
 *   5. **`territorio` y `valores`** — el hueco declarado, con **un** campo,
 *      hasta que #8 las construya.
 *
 * <h2>Por que el hueco lleva un campo, y por que es «Observación»</h2>
 *
 * El AC5 de F-3 pide que **editar un campo** marque la pestana. Un lienzo del
 * todo vacio no tiene ninguno, asi que la mecanica del estado sucio no se podria
 * demostrar ni usar: habria que creersela hasta que llegara la primera pantalla.
 *
 * Y es la observacion y no un campo cualquiera porque es el unico que toda
 * pantalla de este sistema va a llevar: **regla 10 — toda modificacion de datos
 * exige observacion del usuario, y sin observacion no se guarda** (manual
 * §Auditoria, RNF-052). Cuando la seccion se construya, este campo no sobra:
 * baja con ella.
 */
export interface LienzoProps {
  readonly activa: string | null;
  readonly observacion: string;
  readonly alEscribirObservacion: (observacion: string) => void;
  readonly alCerrar: (destino: string) => void;
  /** Abre otra seccion: «Ver todo el padrón» del panel, y cada frente de la cola. */
  readonly alAbrir: (destino: string) => void;
  /** Marca la pestana activa como sucia: es lo que pone el asterisco (AC9). */
  readonly alEnsuciar: () => void;
  readonly alAvisar: (texto: string) => void;
  readonly padron: EstadoDelPadron;
  readonly alCambiarPadron: (cambio: Partial<EstadoDelPadron>) => void;
}

export function Lienzo({
  activa,
  observacion,
  alEscribirObservacion,
  alCerrar,
  alAbrir,
  alEnsuciar,
  alAvisar,
  padron,
  alCambiarPadron,
}: LienzoProps) {
  if (activa === null) {
    return (
      <main className="kr-marco__lienzo kr-marco__lienzo--vacio">
        <div className="kr-marco__sin-pestanas">
          <Icono nombre="expediente" tamano={30} grosor={1.5} />
          <p className="kr-marco__sin-pestanas-titulo">No hay ningún submódulo abierto</p>
          <p className="kr-marco__sin-pestanas-detalle">
            Elija uno en el menú de la izquierda y se abrirá como pestaña. Puede tener varios
            abiertos y moverse entre ellos.
          </p>
        </div>
      </main>
    );
  }

  const hoja = HOJAS.get(activa);
  if (hoja === undefined) {
    // No puede pasar: `activa` sale siempre del arbol o del hash, y el hash se
    // valida contra el arbol antes de aceptarse. Se contesta igual en vez de
    // dejar la pantalla en blanco sin una linea en la consola.
    return (
      <main className="kr-marco__lienzo">
        <Aviso
          tipo="error"
          titulo="Ese submódulo no existe"
          detalle={`El marco no conoce ningún submódulo con la clave «${activa}».`}
        />
      </main>
    );
  }

  if (!esPropia(activa)) {
    return (
      <main className="kr-marco__lienzo">
        <div className="kr-marco__ficha">
          <div className="kr-marco__ficha-cabecera">
            <span className="kr-marco__ficha-icono">
              <Trazos trazos={hoja.trazos} tamano={16} />
            </span>
            <span className="kr-marco__ficha-quien">
              <span className="kr-marco__ficha-rotulo">{hoja.rotulo}</span>
              <span className="kr-marco__ficha-modulo">
                {hoja.modulo} · {hoja.nota}
              </span>
            </span>
          </div>
          <p className="kr-marco__ficha-texto">
            Este marco abre cada submódulo como pestaña, de cualquier módulo. La pantalla de «
            {hoja.rotulo}» está diseñada en el archivo de {hoja.modulo}: lo que se prueba aquí
            es la navegación entre varias cosas abiertas a la vez.
          </p>
          <div className="kr-marco__ficha-pie">
            <p className="kr-marco__ficha-nota">
              Puede dejarla abierta y volver a ella desde la barra de pestañas.
            </p>
            <Boton
              onClick={() => {
                alCerrar(activa);
              }}
            >
              Cerrar la pestaña
            </Boton>
          </div>
        </div>
      </main>
    );
  }

  if (activa === 'panel') {
    return (
      <Panel
        alIrAlPadron={(chip) => {
          alCambiarPadron({ elegido: null, chip: chip ?? padron.chip });
          alAbrir('predios');
        }}
        alAbrirContribuyente={(codigo) => {
          alCambiarPadron({ elegido: codigo, paso: 0, vals: {}, intento: false });
          alAbrir('predios');
        }}
      />
    );
  }

  if (activa === 'predios') {
    return (
      <Contribuyentes
        estado={padron}
        alCambiar={alCambiarPadron}
        alEnsuciar={alEnsuciar}
        alAvisar={alAvisar}
      />
    );
  }

  return (
    <main className="kr-marco__lienzo">
      <div className="kr-marco__hueco">
        <Aviso
          tipo="vacio"
          titulo={`La pantalla de «${hoja.rotulo}» todavía no está construida`}
          detalle={
            'El marco, el panel y el padrón ya están: esta sección es una de las dos que faltan ' +
            '—Determinación y Valores— y llega en su propio issue.'
          }
        />
        <div className="kr-marco__observacion">
          <Campo
            etiqueta="Observación"
            tipo="area"
            valor={observacion}
            onCambio={alEscribirObservacion}
            ancho
            ph="Por qué se modifica"
            ayuda="Toda modificación de datos exige observación del usuario: sin ella no se guarda (regla 10)."
          />
        </div>
      </div>
    </main>
  );
}
