import { Icono } from '../ds/index.ts';
import { Trazos } from './Trazos.tsx';
import { conteoDelFiltro, modulosQueCasan } from './filtro.ts';

/**
 * El panel de la izquierda: **la variante A del artboard, y ninguna otra**.
 *
 * El artboard dibuja tres marcos conmutables (`panelOpciones`, linea 1561):
 * `a` acordeon con chevron hacia abajo, `b` sin control de despliegue y `c` riel
 * de modulos. **Aqui solo existe la `a`**, que ademas era el valor inicial del
 * prototipo, y el conmutador A/B/C **no se porta**: el propio artboard lo declara
 * control del prototipo y lo esconde antes que cualquier elemento de la
 * aplicacion cuando la barra se estrecha.
 *
 * De quedarse solo con la `a` salen tres consecuencias que no son cosmeticas, y
 * las tres estan aqui:
 *
 *   · el `aside` mide **252 px** —los 292 eran de la `c`, que necesitaba sitio
 *     para el riel de iconos mas la lista—;
 *   · la cola de trabajo **se muestra siempre**, porque `hayCola` era `v !== 'c'`
 *     y ya no hay `c` de la que distinguirse;
 *   · `esA`, `esB`, `esC` y `panelOpciones` **no existen**. Portarlos habria
 *     dejado un `if` que ninguna prueba puede poner en rojo.
 *
 * El chevron apunta abajo cuando el modulo esta cerrado y arriba cuando esta
 * abierto, que es el gesto convencional del desplegable. El `>` de las variantes
 * anteriores giraba y leia como «entrar», no como «desplegar».
 */
export interface PanelDeModulosProps {
  readonly filtro: string;
  readonly alFiltrar: (filtro: string) => void;
  /** El modulo desplegado. Uno a la vez, para que la lista quepa sin desplazar. */
  readonly desplegado: string | null;
  readonly alDesplegar: (modulo: string) => void;
  readonly activa: string | null;
  readonly abiertas: readonly string[];
  readonly sucias: Readonly<Record<string, true>>;
  readonly alAbrir: (destino: string) => void;
}

/**
 * La cola de trabajo del artboard, con sus tres filas y sus cifras.
 *
 * Son **conteos de expedientes, no importes**: 534 contribuyentes observados no
 * son 534 soles, asi que no hay ninguna cifra de dinero en el marco y ninguna
 * necesita fecha de calculo. El dia que la cola venga del backend, viene con su
 * operacion; hasta entonces es lo que el artboard dibuja.
 */
const COLA = [
  { rotulo: 'Observados', n: '534', tono: 'mal' },
  { rotulo: 'Sin conciliar', n: '208', tono: 'atencion' },
  { rotulo: 'Beneficios en trámite', n: '392', tono: 'atencion' },
] as const;

export function PanelDeModulos({
  filtro,
  alFiltrar,
  desplegado,
  alDesplegar,
  activa,
  abiertas,
  sucias,
  alAbrir,
}: PanelDeModulosProps) {
  const hayFiltro = filtro.trim() !== '';
  const visibles = modulosQueCasan(filtro);

  return (
    <aside aria-label="Módulos y submódulos" className="kr-marco__panel">
      <div className="kr-marco__filtro">
        <div className="kr-marco__buscador">
          <Icono nombre="lupa" tamano={14} grosor={1.8} />
          <input
            type="search"
            value={filtro}
            onChange={(evento) => alFiltrar(evento.target.value)}
            placeholder="Filtrar módulos y submódulos"
            aria-label="Filtrar módulos y submódulos"
            className="kr-marco__entrada"
          />
          {hayFiltro && (
            <button
              type="button"
              onClick={() => alFiltrar('')}
              aria-label="Quitar el filtro"
              className="kr-marco__quitar"
            >
              <Icono nombre="cerrar" tamano={13} grosor={2.2} />
            </button>
          )}
        </div>
        {hayFiltro && <p className="kr-marco__conteo">{conteoDelFiltro(filtro)}</p>}
      </div>

      <div className="kr-marco__arbol">
        {visibles.map(({ modulo, submodulos }) => {
          // Con filtro se despliega solo el que casa; sin filtro manda la
          // eleccion de quien pulso.
          const abierto = hayFiltro || desplegado === modulo.rotulo;
          const cuantasAbiertas = modulo.submodulos.filter((submodulo) =>
            abiertas.includes(submodulo.clave),
          ).length;

          return (
            <div key={modulo.clave}>
              <button
                type="button"
                onClick={() => alDesplegar(modulo.rotulo)}
                aria-expanded={abierto}
                className={`kr-marco__modulo${abierto ? ' kr-marco__modulo--abierto' : ''}`}
              >
                <span className="kr-marco__icono-modulo">
                  <Trazos trazos={modulo.trazos} tamano={14} />
                </span>
                <span className="kr-marco__rotulo-modulo">{modulo.rotulo}</span>
                {cuantasAbiertas > 0 && (
                  <span className="kr-marco__pastilla">{String(cuantasAbiertas)}</span>
                )}
                <span
                  className={`kr-marco__chevron${abierto ? ' kr-marco__chevron--arriba' : ''}`}
                >
                  <Icono nombre="chevronAbajo" tamano={13} grosor={2.1} />
                </span>
              </button>

              {abierto && (
                <div className="kr-marco__hojas">
                  {submodulos.map((submodulo) => {
                    const esLaActiva = activa === submodulo.clave;
                    const yaAbierta = abiertas.includes(submodulo.clave);
                    return (
                      <button
                        key={submodulo.clave}
                        type="button"
                        onClick={() => alAbrir(submodulo.clave)}
                        aria-current={esLaActiva}
                        className={`kr-marco__hoja${esLaActiva ? ' kr-marco__hoja--actual' : ''}`}
                      >
                        <span className="kr-marco__rotulo-hoja">
                          {submodulo.rotulo}
                          {sucias[submodulo.clave] === true ? ' *' : ''}
                        </span>
                        {yaAbierta && !esLaActiva && (
                          <span className="kr-marco__marca">abierta</span>
                        )}
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {hayFiltro && visibles.length === 0 && (
        <div className="kr-marco__sin-coincidencias">
          <p>
            Ningún módulo ni submódulo se llama así. Pruebe con «papeleta», «acta», «recibo»
            o «expediente».
          </p>
        </div>
      )}

      {/* Siempre, sin condicion: `hayCola` era `v !== 'c'` y la `c` no se porta. */}
      <div className="kr-marco__cola">
        <p className="kr-marco__cola-titulo">Cola de trabajo</p>
        {COLA.map((fila) => (
          <button
            key={fila.rotulo}
            type="button"
            onClick={() => alAbrir('predios')}
            className="kr-marco__cola-fila"
          >
            <span className={`kr-marco__punto kr-marco__punto--${fila.tono}`} />
            <span className="kr-marco__cola-rotulo">{fila.rotulo}</span>
            <span className="kr-marco__cola-n">{fila.n}</span>
          </button>
        ))}
      </div>
    </aside>
  );
}
