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

export interface BarraGlobalProps {
  readonly entidad: string;
  readonly ejercicio: string;
  readonly alCambiarEjercicio: (ejercicio: string) => void;
  readonly panelAbierto: boolean;
  readonly alAlternarPanel: () => void;
  readonly lanzadorAbierto: boolean;
  readonly alAlternarLanzador: () => void;
  readonly alAbrirPaleta: () => void;
  readonly sesionAbierta: boolean;
  readonly alAlternarSesion: () => void;
  readonly alCerrarSesion: () => void;
  readonly hayAviso: boolean;
  readonly alVerAviso: () => void;
  readonly cuantasSucias: number;
  readonly alAbrir: (destino: string) => void;
  readonly alAvisar: (mensaje: string) => void;
}

export function BarraGlobal({
  entidad,
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
          value={ejercicio}
          onChange={(evento) => alCambiarEjercicio(evento.target.value)}
          aria-label="Ejercicio de trabajo"
          className="kr-marco__ejercicio-selector"
        >
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
          aria-label="Sesión de J. Cárdenas Vega"
          className={`kr-marco__sesion-boton${
            sesionAbierta ? ' kr-marco__sesion-boton--activo' : ''
          }`}
        >
          <span className="kr-marco__avatar">JC</span>
          <span className="kr-marco__sesion-quien">
            <span className="kr-marco__sesion-nombre">J. Cárdenas Vega</span>
            <span className="kr-marco__sesion-papel">Rentas · ventanilla</span>
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
              <span className="kr-marco__avatar kr-marco__avatar--grande">JC</span>
              <span className="kr-marco__menu-quien">
                <span className="kr-marco__menu-nombre">J. Cárdenas Vega</span>
                <span className="kr-marco__menu-papel">jcardenas · Rentas · ventanilla</span>
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
                    alAvisar(
                      opcion.salida
                        ? 'Cerraría la sesión de jcardenas.'
                        : `Abriría ${opcion.rotulo.toLowerCase()}.`,
                    );
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
