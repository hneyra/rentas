import type { SesionDeLaVentanilla } from '../datos/lecturas.ts';
import { Icono } from '../ds/index.ts';
import { ARBOL, MODULO_PROPIO } from './arbol.ts';
import { Trazos } from './Trazos.tsx';

/**
 * La barra global de V6: del mismo azul que tenia el riel de modulos.
 *
 * A la izquierda, el control que muestra u oculta el arbol y la entidad; a la
 * derecha, y en este orden desde el borde, el usuario, el lanzador de modulos,
 * el buscador, el ejercicio y el aviso de servicio.
 *
 * **El escudo no se porta**: el artboard dibuja `escudo-catacaos.png` y anota que
 * es un marcador de posicion a la espera del archivo real. Un `<img>` a un
 * archivo que no existe deja un icono roto en la barra de todas las pantallas,
 * asi que hasta que llegue el archivo va el nombre solo. El hueco esta escrito
 * aqui y no en un tablero aparte.
 *
 * **El lanzador abre el Panel del modulo elegido**, y no un aviso flotante. El
 * artboard contestaba con «Abriria el modulo X» porque en el prototipo cada
 * modulo era otro archivo; aqui los diez estan en el arbol, sus destinos existen
 * y abrirlos es lo que el gesto promete.
 *
 * <h2>Desde I-1, quien aparece aqui lo dice el backend (AC7)</h2>
 *
 * La entidad y el usuario eran dos constantes del artboard —«Municipalidad
 * Distrital de Catacaos» y «J. Cárdenas Vega»—, o sea que la cabecera de TODAS
 * las pantallas afirmaba de quien son unas cifras sin haberselo preguntado a
 * nadie: con el token de otra municipalidad decia lo mismo. Ahora llegan de
 * `GET /seguridad/sesion` y `GET /seguridad/sesion/municipalidad`, y llegan como
 * **props obligatorias**: no hay forma de montar esta barra sin decir quien esta
 * dentro, que es mas fuerte que acordarse de pasarlas.
 *
 * <h2>El papel del usuario NO se dibuja, y no es un olvido</h2>
 *
 * El artboard escribe «Rentas · ventanilla» debajo del nombre. **Ninguna de las
 * 181 operaciones del contrato publica un papel, un perfil ni un rol para la
 * sesion** —medido sobre `docs/50-api/formas-de-la-api.json`, cero coincidencias
 * de «rol», «roles» o «perfil» en las 181—: `GET /seguridad/sesion` publica
 * `usuarioId`, `cuenta`, `nombre` y `ejercicioDeTrabajo`, y nada mas. Escribir
 * «ventanilla» aqui seria afirmar en pantalla un permiso que nadie ha
 * concedido, que en un sistema de recaudacion es la peor clase de invencion. En
 * su sitio va **la cuenta**, que si llega y es lo que hay que dictar por
 * telefono cuando algo no se puede hacer.
 */

/** Las opciones del menu de sesion, con sus trazos tal como el artboard los escribe. */
const OPCIONES_DE_SESION = [
  {
    rotulo: 'Mi perfil',
    trazos: ['M12 7.4a3 3 0 1 1-6 0 3 3 0 0 1 6 0', 'M3.6 20c0-3 2.4-4.6 5.4-4.6s5.4 1.6 5.4 4.6'],
    salida: false,
  },
  {
    rotulo: 'Cambiar contraseña',
    trazos: ['M7 11V8a5 5 0 0 1 10 0v3', 'M5.5 11h13v9.5h-13z'],
    salida: false,
  },
  {
    rotulo: 'Cerrar sesión',
    trazos: [
      'M9.5 20H6A1.5 1.5 0 0 1 4.5 18.5v-13A1.5 1.5 0 0 1 6 4h3.5',
      'M14 8l4 4-4 4',
      'M18 12H9',
    ],
    salida: true,
  },
] as const;

/** Los nueve puntos del lanzador: tres filas por tres columnas. */
const PUNTOS = [0, 1, 2].flatMap((fila) =>
  [0, 1, 2].map((columna) => ({ x: 6 + columna * 6, y: 6 + fila * 6 })),
);

/** Los ejercicios que la sesion puede tomar. */
export const EJERCICIOS = ['2026', '2025', '2024', '2023'] as const;

/**
 * Las iniciales del avatar, sacadas del nombre que contesta el backend.
 *
 * Dos letras como en el artboard, y de las dos PRIMERAS palabras: «Administrador del Sistema»
 * da «AS» y no «AD». Sin nombre no se inventa nada — se ensena un guion, porque un avatar con
 * dos letras al azar es peor que un avatar vacio.
 */
export function inicialesDe(nombre: string): string {
  const palabras = nombre.trim().split(/\s+/).filter((p) => p.length > 0);
  if (palabras.length === 0) {
    return '—';
  }
  const primera = palabras[0] ?? '';
  const segunda = palabras.length > 1 ? (palabras[palabras.length - 1] ?? '') : '';
  return (primera.charAt(0) + segunda.charAt(0)).toUpperCase();
}

export interface BarraGlobalProps {
  readonly entidad: string;
  /** Quien esta trabajando, tal como lo contesta `GET /seguridad/sesion`. */
  readonly usuario: SesionDeLaVentanilla;
  /**
   * El ejercicio de trabajo, o `null` si el backend no ha fijado ninguno.
   *
   * `null` no es un caso raro: es lo que contesta hoy la instalacion. Ver el `<select>`.
   */
  readonly ejercicio: string | null;
  readonly alCambiarEjercicio: (ejercicio: string) => void;
  readonly panelAbierto: boolean;
  readonly alAlternarPanel: () => void;
  readonly lanzadorAbierto: boolean;
  readonly alAlternarLanzador: () => void;
  readonly alAbrirPaleta: () => void;
  readonly sesionAbierta: boolean;
  readonly alAlternarSesion: () => void;
  readonly alCerrarSesion: () => void;
  /** Cierra la sesion de verdad: aqui y en el emisor de identidad. */
  readonly alSalir: () => void;
  readonly hayAviso: boolean;
  readonly alVerAviso: () => void;
  readonly cuantasSucias: number;
  readonly alAbrir: (destino: string) => void;
  readonly alAvisar: (mensaje: string) => void;
}

export function BarraGlobal({
  entidad,
  usuario,
  ejercicio,
  alCambiarEjercicio,
  panelAbierto,
  alAlternarPanel,
  lanzadorAbierto,
  alAlternarLanzador,
  alAbrirPaleta,
  sesionAbierta,
  alAlternarSesion,
  alCerrarSesion,
  alSalir,
  hayAviso,
  alVerAviso,
  cuantasSucias,
  alAbrir,
  alAvisar,
}: BarraGlobalProps) {
  return (
    <header className="kr-marco__barra">
      <button
        type="button"
        onClick={alAlternarPanel}
        aria-label="Mostrar u ocultar las secciones de Rentas"
        aria-expanded={panelAbierto}
        title={panelAbierto ? 'Ocultar las secciones' : 'Mostrar las secciones'}
        className={`kr-marco__control${panelAbierto ? ' kr-marco__control--activo' : ''}`}
      >
        <svg
          width="19"
          height="19"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={1.9}
          strokeLinecap="round"
          aria-hidden="true"
          focusable="false"
        >
          <path d="M4 7h16M4 12h16M4 17h16" />
        </svg>
      </button>

      <span className="kr-marco__entidad">
        <span className="kr-marco__entidad-nombre">{entidad}</span>
        <span className="kr-marco__entidad-nota">Sistema de gestión tributaria municipal</span>
      </span>

      {hayAviso && (
        <button
          type="button"
          onClick={alVerAviso}
          aria-label="1 aviso del sistema"
          title="1 aviso del sistema"
          className="kr-marco__control kr-marco__control--filo"
        >
          <Trazos
            trazos={['M18 15.6V10.5a6 6 0 0 0-12 0v5.1L4.4 18h15.2z', 'M9.8 18a2.2 2.2 0 0 0 4.4 0']}
            tamano={17}
          />
          <span className="kr-marco__globo">1</span>
        </button>
      )}

      <div className="kr-marco__ejercicio">
        <span className="kr-marco__ejercicio-rotulo">Ejercicio</span>
        <select
          value={ejercicio ?? ''}
          onChange={(evento) => alCambiarEjercicio(evento.target.value)}
          aria-label="Ejercicio de trabajo"
          title={
            ejercicio === null
              ? 'El backend no ha fijado el ejercicio de trabajo de esta sesión.'
              : `Ejercicio de trabajo: ${ejercicio}`
          }
          className="kr-marco__ejercicio-selector"
        >
          {/* La opcion vacia existe SOLO mientras el backend no haya fijado ninguno (AC8). El
              artboard pone «2026» fijo; si el backend contesta `ejercicioDeTrabajo: null` —que
              es lo que contesta hoy— ensenar un ano cualquiera afirmaria sobre que ejercicio se
              esta trabajando, y todas las cifras de la pantalla se leerian como suyas. En
              cuanto hay uno, la opcion desaparece: desde aqui no se puede DESfijar, porque
              fijarlo y desfijarlo es `PUT /seguridad/sesion/ejercicio` y eso es de otro issue. */}
          {ejercicio === null && <option value="">Sin fijar</option>}
          {EJERCICIOS.map((anio) => (
            <option key={anio} value={anio}>
              {anio}
            </option>
          ))}
        </select>
      </div>

      <button
        type="button"
        onClick={alAbrirPaleta}
        aria-label="Buscar"
        title="Buscar — Ctrl K"
        className="kr-marco__control kr-marco__control--filo"
      >
        <Icono nombre="lupa" tamano={16} grosor={1.8} />
      </button>

      <div className="kr-marco__relativo">
        <button
          type="button"
          onClick={alAlternarLanzador}
          aria-label="Ver todos los módulos"
          aria-expanded={lanzadorAbierto}
          title="Todos los módulos"
          className={`kr-marco__control${lanzadorAbierto ? ' kr-marco__control--activo' : ''}`}
        >
          <svg
            width="19"
            height="19"
            viewBox="0 0 24 24"
            fill="currentColor"
            aria-hidden="true"
            focusable="false"
          >
            {PUNTOS.map((punto) => (
              <circle key={`${String(punto.x)}-${String(punto.y)}`} cx={punto.x} cy={punto.y} r={1.9} />
            ))}
          </svg>
        </button>

        {lanzadorAbierto && (
          <>
            <button
              type="button"
              onClick={alAlternarLanzador}
              aria-label="Cerrar la lista de módulos"
              className="kr-marco__velo kr-marco__velo--claro"
            />
          <div role="dialog" aria-label="Módulos del sistema" className="kr-marco__lanzador">
            <div className="kr-marco__lanzador-cabecera">
              <p className="kr-marco__lanzador-titulo">Módulos</p>
              <p className="kr-marco__lanzador-nota">Los diez comparten este marco</p>
            </div>
            <div className="kr-marco__lanzador-rejilla">
              {ARBOL.map((modulo) => {
                const propio = modulo.rotulo === MODULO_PROPIO;
                const primero = modulo.submodulos[0];
                return (
                  <button
                    key={modulo.clave}
                    type="button"
                    onClick={() => {
                      if (primero !== undefined) {
                        alAbrir(primero.clave);
                      }
                    }}
                    aria-current={propio}
                    className={`kr-marco__lanzador-modulo${
                      propio ? ' kr-marco__lanzador-modulo--actual' : ''
                    }`}
                  >
                    <span
                      className={`kr-marco__lanzador-icono${
                        propio ? ' kr-marco__lanzador-icono--actual' : ''
                      }`}
                    >
                      <Trazos trazos={modulo.trazos} tamano={17} />
                    </span>
                    <span className="kr-marco__lanzador-rotulo">{modulo.rotulo}</span>
                  </button>
                );
              })}
            </div>
            <p className="kr-marco__lanzador-pie">
              El ejercicio de trabajo es global a la sesión: al cambiarlo, cambia para los diez
              módulos.
            </p>
          </div>
          </>
        )}
      </div>

      <div className="kr-marco__sesion">
        <button
          type="button"
          onClick={alAlternarSesion}
          aria-expanded={sesionAbierta}
          aria-label={`Sesión de ${usuario.nombre}`}
          className={`kr-marco__sesion-boton${
            sesionAbierta ? ' kr-marco__sesion-boton--activo' : ''
          }`}
        >
          <span className="kr-marco__avatar">{inicialesDe(usuario.nombre)}</span>
          <span className="kr-marco__sesion-quien">
            <span className="kr-marco__sesion-nombre">{usuario.nombre}</span>
            {/* La CUENTA, no un papel: el contrato no publica ninguno. Ver la cabecera. */}
            <span className="kr-marco__sesion-papel">{usuario.cuenta}</span>
          </span>
          <span
            className={`kr-marco__caret${sesionAbierta ? ' kr-marco__caret--arriba' : ''}`}
          >
            <Icono nombre="chevronAbajo" tamano={13} grosor={2.1} />
          </span>
        </button>

        {sesionAbierta && (
          <>
            <button
              type="button"
              onClick={alCerrarSesion}
              aria-label="Cerrar el menú de sesión"
              className="kr-marco__velo kr-marco__velo--claro"
            />
          <div role="menu" aria-label="Sesión" className="kr-marco__menu">
            <div className="kr-marco__menu-cabecera">
              <span className="kr-marco__avatar kr-marco__avatar--grande">
                {inicialesDe(usuario.nombre)}
              </span>
              <span className="kr-marco__menu-quien">
                <span className="kr-marco__menu-nombre">{usuario.nombre}</span>
                <span className="kr-marco__menu-papel">{usuario.cuenta}</span>
              </span>
            </div>
            <div className="kr-marco__menu-opciones">
              {OPCIONES_DE_SESION.map((opcion) => (
                <button
                  key={opcion.rotulo}
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    alCerrarSesion();
                    // «Cerrar sesión» sale de verdad desde I-1: hay un token que soltar y una
                    // sesion del emisor que cerrar. Un aviso flotante que dijera «cerraría la
                    // sesión» dejaria el token vivo en la pestana, que es justo lo que la PC
                    // compartida de ventanilla no puede permitirse. Las otras dos opciones
                    // siguen siendo del artboard: abren pantallas que aqui no existen.
                    if (opcion.salida) {
                      alSalir();
                      return;
                    }
                    alAvisar(`Abriría ${opcion.rotulo.toLowerCase()}.`);
                  }}
                  className={`kr-marco__menu-opcion${
                    opcion.salida ? ' kr-marco__menu-opcion--salida' : ''
                  }`}
                >
                  <Trazos trazos={opcion.trazos} tamano={15} />
                  <span className="kr-marco__menu-rotulo">{opcion.rotulo}</span>
                </button>
              ))}
            </div>
            {cuantasSucias > 0 && (
              <p className="kr-marco__menu-aviso">
                Hay {cuantasSucias}{' '}
                {cuantasSucias === 1
                  ? 'pestaña con cambios sin guardar. Al cerrar sesión se pierden.'
                  : 'pestañas con cambios sin guardar. Al cerrar sesión se pierden.'}
              </p>
            )}
          </div>
          </>
        )}
      </div>
    </header>
  );
}
