import { Aviso, Boton, Icono } from '../ds/index.ts';
import { Contribuyentes } from '../secciones/Contribuyentes.tsx';
import { Determinacion } from '../secciones/Determinacion.tsx';
import { Panel } from '../secciones/Panel.tsx';
import { Valores } from '../secciones/Valores.tsx';
import type { EstadoDelPadron } from '../secciones/estadoDelPadron.ts';
import { HOJAS, esPropia } from './arbol.ts';
import { Trazos } from './Trazos.tsx';

/**
 * El lienzo: lo que hay debajo de las pestanas.
 *
 * Tiene **cuatro estados**:
 *
 *   1. **sin pestanas** — no hay nada abierto, y se dice;
 *   2. **una hoja ajena** — la ficha del AC9 de F-3, que explica en que archivo
 *      se disena esa pantalla y deja la pestana abierta para volver a ella;
 *   3. **`panel`** — el panel del modulo (F-5, AC1);
 *   4. **`predios`, `territorio` y `valores`** — el padron con su expediente
 *      (F-5) y las dos secciones de F-6.
 *
 * <h2>El hueco con su campo «Observación» ya no esta, y por que</h2>
 *
 * Hasta F-6 quedaban dos secciones sin construir, y el lienzo las dibujaba con
 * un aviso y **un** campo de observacion: el AC5 de F-3 pide que *editar un
 * campo* marque la pestana, y un lienzo del todo vacio no tenia ninguno con el
 * que demostrarlo.
 *
 * Con #8 las cuatro secciones existen, y **ninguna de las dos nuevas modifica
 * datos**: «Determinación» ensena la memoria de un calculo y «Valores», un
 * conjunto sellado que el propio artboard rotula «Solo lectura». La regla 10
 * —toda modificacion de datos exige observacion del usuario— no pide un campo
 * donde no se modifica nada, asi que el campo baja con el hueco. Donde si se
 * escribe es en el alta del padron, y ahi esta la mecanica del estado sucio, con
 * sus pruebas (`Contribuyentes.test.tsx`, AC9 de F-5, y `Marco.test.tsx`).
 */
export interface LienzoProps {
  readonly activa: string | null;
  readonly alCerrar: (destino: string) => void;
  /** Abre otra seccion: «Ver todo el padrón» del panel, y cada frente de la cola. */
  readonly alAbrir: (destino: string) => void;
  /** Marca la pestana activa como sucia: es lo que pone el asterisco (AC9). */
  readonly alEnsuciar: () => void;
  readonly alAvisar: (texto: string) => void;
  readonly padron: EstadoDelPadron;
  readonly alCambiarPadron: (cambio: Partial<EstadoDelPadron>) => void;
  /**
   * El ejercicio de la barra global: decide de que conjunto sellado se piden las senas.
   *
   * `null` cuando el backend no ha fijado ninguno, que es lo que contesta hoy la instalacion.
   * No se sustituye por el ano en curso: pedir las senas del conjunto de 2026 porque hoy es
   * 2026 seria decidir aqui el ejercicio de trabajo de la sesion.
   */
  readonly ejercicio: string | null;
}

export function Lienzo({
  activa,
  alCerrar,
  alAbrir,
  alEnsuciar,
  alAvisar,
  padron,
  alCambiarPadron,
  ejercicio,
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

  if (activa === 'territorio') {
    return <Determinacion />;
  }

  if (activa === 'valores') {
    return <Valores ejercicio={ejercicio} />;
  }

  // No puede pasar: `esPropia` deja pasar exactamente las cuatro claves de `SECCIONES`, y las
  // cuatro estan arriba. Se contesta igual, en vez de devolver `undefined` a React.
  return (
    <main className="kr-marco__lienzo">
      <Aviso
        tipo="error"
        titulo="Esa sección no tiene pantalla"
        detalle={`«${activa}» es una sección de este módulo y el lienzo no sabe dibujarla.`}
      />
    </main>
  );
}
