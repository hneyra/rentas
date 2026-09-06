import { Aviso, Boton, Campo, Icono } from '../ds/index.ts';
import { HOJAS, esPropia } from './arbol.ts';
import { Trazos } from './Trazos.tsx';

/**
 * El lienzo: lo que hay debajo de las pestanas.
 *
 * Tiene **tres estados y ninguno mas**, porque el contenido de las cuatro
 * secciones de Rentas esta fuera del alcance de este issue:
 *
 *   1. **sin pestanas** — no hay nada abierto, y se dice;
 *   2. **una hoja ajena** — la ficha del AC9, que explica en que archivo se
 *      disena esa pantalla y deja la pestana abierta para volver a ella;
 *   3. **una seccion propia** — el hueco declarado, con **un** campo.
 *
 * <h2>Por que el hueco lleva un campo, y por que es «Observación»</h2>
 *
 * El AC5 pide que **editar un campo** marque la pestana. Un lienzo del todo
 * vacio no tiene ninguno, asi que la mecanica del estado sucio no se podria
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
}

export function Lienzo({ activa, observacion, alEscribirObservacion, alCerrar }: LienzoProps) {
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

  return (
    <main className="kr-marco__lienzo">
      <div className="kr-marco__hueco">
        <Aviso
          tipo="vacio"
          titulo={`La pantalla de «${hoja.rotulo}» todavía no está construida`}
          detalle={
            'Este issue entrega el marco: el árbol, las pestañas, el enrutado por hash y el ' +
            'estado sin guardar. El contenido de las cuatro secciones de Rentas llega en su ' +
            'propio issue.'
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
