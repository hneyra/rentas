import { useEffect, useState } from 'react';

import { Aviso, Boton, Esqueleto, Icono, Importe, Insignia } from '../ds/index.ts';
import {
  RUTAS,
  type ContribuyenteDelPadron,
  type CorridaDelPredial,
  type DeudaEnCoactiva,
  type ImporteConFecha,
  type ObservadoDeLaCorrida,
} from '../datos/lecturas.ts';
import { usePagina, useUno } from '../datos/useRecurso.ts';
import { Expediente } from './Expediente.tsx';
import { CHIPS, NUEVO, type EstadoDelPadron } from './estadoDelPadron.ts';
import {
  CRITERIOS,
  ORDENES_DEL_PADRON,
  componerPadron,
  criterioDe,
  rutaDelPadron,
} from './padron.ts';
import { tonoDelEstado } from './tonos.ts';

/**
 * La seccion «Contribuyentes» (clave `predios`): el padron a la izquierda y el expediente al
 * lado, sin salir de la lista.
 *
 * <h2>Lo que cambio en I-4: el padron son diez mil filas, no cinco</h2>
 *
 * Hasta I-4 esta pantalla pedia las cuatro operaciones y el proxy de F-4 las contestaba con las
 * cinco filas del artboard. Ahora las contesta el backend: **10 603 contribuyentes** medidos con
 * la cuenta `administrador`, en **531 paginas de veinte**. Tres cosas dejan de poder hacerse
 * aqui, y las tres por el mismo motivo:
 *
 *   · **Buscar.** Filtrar las veinte filas cargadas devuelve «ningun contribuyente coincide»
 *     para alguien que si esta en el padron. Lo resuelve el backend, con los cuatro criterios
 *     que publica y con el selector que hace falta para elegir entre ellos (`padron.ts`).
 *   · **Ordenar.** Reordenar la pagina cargada pone delante «el primero» de veinte, no del
 *     padron. Va como `?ordenarPor=`, y por eso «Deuda» desaparece: el backend contesta **422
 *     `ORDEN_NO_ADMITIDO`** a ese campo, porque esta operacion no publica la deuda.
 *   · **Contar.** `totalElementos` es la cuenta del backend y **no se recalcula**: `contenido`
 *     es una ventana, y su longitud es siempre veinte o menos.
 *
 * <h2>Los chips son listas, no filtros (AC3 y AC5)</h2>
 *
 * «En coactiva» y «Observado» no son criterios de `GET /rentas/contribuyentes`, asi que no se
 * pueden pedir sobre el padron; lo que si existe es **la operacion que contesta cada una de esas
 * dos preguntas**. De modo que el chip cambia de lista en vez de filtrar la que hay: «En
 * coactiva» ensena lo que contesta `GET /coactiva/deudas` y «Observado», los observados de la
 * ultima corrida. Cada uno con **su** cuenta.
 *
 * Medido contra la instalacion el 2026-09-07, las dos contestan **200 con lista vacia** en las
 * dos municipalidades. Eso no es un fallo: es el dato, y la pantalla lo dice como tal —«ninguno»
 * y no «no se pudo leer»—, que son dos frases distintas porque llevan a dos sitios distintos.
 *
 * <h2>Los datos salen del backend, y ninguno se importa</h2>
 *
 * Esta pantalla **no importa `datos/prototipo.ts`**, ni directa ni indirectamente. Ni un `fetch`
 * suelto: la unica puerta es `solicitar()`, y de eso responde la prohibicion
 * `fetch-fuera-del-cliente` de ESLint.
 */
export interface ContribuyentesProps {
  readonly estado: EstadoDelPadron;
  readonly alCambiar: (cambio: Partial<EstadoDelPadron>) => void;
  readonly alEnsuciar: () => void;
  readonly alAvisar: (texto: string) => void;
}

/**
 * Cuanto se espera desde la ultima tecla antes de preguntarle al backend.
 *
 * Sin esto, «MEDINA» son seis peticiones y las cinco primeras se descartan al llegar. Con la
 * busqueda del cliente ese coste no existia porque no habia peticion; con la del servidor, cada
 * tecla es una consulta por trigramas sobre 10 603 filas.
 */
const ESPERA_DEL_BUSCADOR_MS = 250;

/** Una fila de la lista, venga del padron o de una de las dos listas de estado. */
interface FilaVisible {
  readonly clave: string;
  readonly codigo: string;
  readonly nombre: string;
  /** La segunda linea: documento y tipo de persona, o de donde sale la fila. */
  readonly contexto: string;
  readonly estado: string;
  readonly importe: ImporteConFecha | null;
  /** El identificador con que se abre el expediente. `null` si esta fila no lo trae. */
  readonly id: number | null;
}

/** Lo tecleado, pero solo cuando deja de teclearse. */
function useTextoAsentado(texto: string): string {
  const [asentado, fijar] = useState(texto);

  useEffect(() => {
    if (texto === asentado) return undefined;
    const espera = setTimeout(() => {
      fijar(texto);
    }, ESPERA_DEL_BUSCADOR_MS);
    return () => {
      clearTimeout(espera);
    };
  }, [texto, asentado]);

  return asentado;
}

export function Contribuyentes({
  estado,
  alCambiar,
  alEnsuciar,
  alAvisar,
}: ContribuyentesProps) {
  const buscado = useTextoAsentado(estado.q);
  const criterio = criterioDe(estado.criterio);
  const enElPadron = estado.chip === 'Todos';

  const padron = usePagina<ContribuyenteDelPadron>(
    rutaDelPadron(RUTAS.padron, {
      criterio: estado.criterio,
      texto: buscado,
      orden: estado.orden,
      pagina: estado.pagina,
    }),
  );
  const coactiva = usePagina<DeudaEnCoactiva>(RUTAS.coactiva);
  const corrida = useUno<CorridaDelPredial>(RUTAS.ultimaCorrida);
  const observados = usePagina<ObservadoDeLaCorrida>(
    corrida.dato === null ? null : RUTAS.observados(corrida.dato.id),
  );

  const compuesto = componerPadron(padron.dato?.contenido ?? [], coactiva.dato, observados.dato);

  const delPadron: readonly FilaVisible[] = compuesto.filas.map((fila) => ({
    clave: fila.contribuyente.codigo,
    codigo: fila.contribuyente.codigo,
    nombre: fila.contribuyente.nombreRazonSocial,
    contexto: `${fila.contribuyente.tipoDocumento} ${fila.contribuyente.numeroDocumento} · ${fila.contribuyente.tipoPersona}`,
    estado: fila.estado,
    importe: fila.importe,
    id: fila.contribuyente.id,
  }));

  const deCoactiva: readonly FilaVisible[] = (coactiva.dato?.contenido ?? []).map((deuda) => ({
    clave: deuda.expediente,
    codigo: deuda.codContribuyente,
    nombre: deuda.contribuyente,
    contexto: `Expediente ${deuda.expediente} · ${String(deuda.ano)}`,
    estado: deuda.estado,
    importe: { importe: deuda.totalS, actualizadoA: deuda.aLaFecha },
    id: null,
  }));

  const deObservados: readonly FilaVisible[] = (observados.dato?.contenido ?? []).map((uno) => ({
    clave: uno.codContribuyente,
    codigo: uno.codContribuyente,
    nombre: uno.nombre,
    contexto: uno.motivo,
    estado: 'Observado',
    importe: null,
    id: null,
  }));

  /** La lista que enseña cada chip, con la peticion de la que sale y su cuenta. */
  const lista = {
    Todos: { filas: delPadron, fuente: padron, total: padron.dato?.totalElementos ?? 0 },
    'Con deuda': { filas: [] as readonly FilaVisible[], fuente: padron, total: 0 },
    'En coactiva': { filas: deCoactiva, fuente: coactiva, total: coactiva.dato?.totalElementos ?? 0 },
    Observado: { filas: deObservados, fuente: observados, total: observados.dato?.totalElementos ?? 0 },
  }[estado.chip] ?? { filas: delPadron, fuente: padron, total: padron.dato?.totalElementos ?? 0 };

  const visibles = lista.filas;
  const cargando = lista.fuente.cargando;
  const fallo = lista.fuente.error;

  const totalPaginas = padron.dato?.totalPaginas ?? 0;
  const nuevo = estado.elegido === NUEVO;
  const elegida =
    estado.elegido === null || nuevo
      ? null
      : (compuesto.filas.find((fila) => fila.contribuyente.codigo === estado.elegido) ?? null);
  const hayFicha = nuevo || elegida !== null;

  const abrirNuevo = () => {
    alCambiar({ elegido: NUEVO, paso: 0, vals: {}, intento: false });
    alAvisar('Contribuyente nuevo: empiece por el documento de identidad.');
  };

  /** Deja la lista pedible desde cero: cambiar de criterio o de texto invalida la pagina. */
  const rehacerLaConsulta = (cambio: Partial<EstadoDelPadron>) => {
    alCambiar({ ...cambio, pagina: 0 });
  };

  return (
    <main className="kr-marco__lienzo kr-seccion kr-padron">
      <div className="kr-padron__lista">
        <div className="kr-padron__buscador">
          <div className="kr-busqueda">
            <label className="kr-busqueda__criterio">
              <span className="kr-busqueda__criterio-rotulo">Buscar por</span>
              <select
                value={estado.criterio}
                onChange={(evento) => {
                  rehacerLaConsulta({ criterio: evento.target.value });
                }}
              >
                {CRITERIOS.map((uno) => (
                  <option key={uno.rotulo} value={uno.rotulo}>
                    {uno.rotulo}
                  </option>
                ))}
              </select>
            </label>
            <Icono nombre="lupa" tamano={15} grosor={1.8} />
            <input
              className="kr-busqueda__campo"
              value={estado.q}
              placeholder={criterio.ayuda}
              aria-label="Buscar en el padrón"
              onChange={(evento) => {
                rehacerLaConsulta({ q: evento.target.value });
              }}
            />
            {estado.q !== '' && (
              <button
                type="button"
                aria-label="Limpiar la búsqueda"
                className="kr-busqueda__limpiar"
                onClick={() => {
                  rehacerLaConsulta({ q: '' });
                }}
              >
                <Icono nombre="cerrar" tamano={14} grosor={2.2} />
              </button>
            )}
          </div>
          {/* Un «=» y no un «contiene»: el SQL es `codigo_contribuyente = :codigo`, medido —un
              codigo a medias devuelve cero—. Decirlo aqui es mas barato que descubrirlo. */}
          {criterio.exacto && (
            <p className="kr-padron__nota-del-criterio">
              «{criterio.rotulo}» se busca completo: el backend compara por igualdad.
            </p>
          )}
          <div className="kr-padron__chips">
            {CHIPS.map((chip) => (
              <button
                type="button"
                key={chip}
                aria-pressed={estado.chip === chip}
                className={`kr-chip${estado.chip === chip ? ' kr-chip--on' : ''}`}
                onClick={() => {
                  rehacerLaConsulta({ chip });
                }}
              >
                {chip}
              </button>
            ))}
          </div>
        </div>

        <div className="kr-padron__conteo">
          <span className="kr-padron__cuantos">
            {cargando && lista.fuente.dato === null
              ? 'Cargando…'
              : /* La cuenta es la del backend. `contenido.length` es siempre veinte o menos. */
                `${lista.total.toLocaleString('es-PE')} ${lista.total === 1 ? 'contribuyente' : 'contribuyentes'}`}
          </span>
          {enElPadron && (
            <label className="kr-padron__orden">
              <span className="kr-padron__orden-rotulo">Ordenar la lista</span>
              <select
                value={estado.orden}
                disabled={buscado.trim() !== '' && !criterio.exacto}
                onChange={(evento) => {
                  rehacerLaConsulta({ orden: evento.target.value });
                }}
              >
                {ORDENES_DEL_PADRON.map((orden) => (
                  <option key={orden.rotulo} value={orden.rotulo}>
                    {orden.rotulo}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>

        <div className="kr-padron__filas" aria-busy={cargando}>
          {fallo !== null && (
            <Aviso tipo="error" titulo="No se pudo leer la lista" detalle={fallo} />
          )}

          {/* El estado de cobranza llega de otras dos operaciones. Si una falla, la lista se
              dibuja igual —los contribuyentes estan— pero **con el estado incompleto**: quien
              esta en coactiva se veria como «Activo». Callarlo seria peor que no tener el dato. */}
          {enElPadron && fallo === null && (coactiva.error !== null || observados.error !== null) && (
            <Aviso
              tipo="error"
              titulo="El estado de cobranza no está completo"
              detalle={
                'La lista está, pero no se pudo leer quién está en coactiva o quién quedó ' +
                'observado. Los chips de esos dos estados no encontrarán a nadie.'
              }
            />
          )}

          {/* Y aunque las dos lleguen: si vienen recortadas, no aparecer en ellas no significa
              no estar. Una insignia «Activo» seria entonces una afirmacion hecha por omision. */}
          {enElPadron &&
            fallo === null &&
            coactiva.error === null &&
            observados.error === null &&
            padron.dato !== null &&
            !compuesto.estadoCompleto && (
              <Aviso
                tipo="error"
                titulo="El estado de cobranza se calculó sobre una parte"
                detalle={
                  'Las listas de coactiva y de observados llegaron paginadas, así que quien esté ' +
                  'en ellas más allá de la primera página se ve aquí como «Activo». El chip de ' +
                  'cada estado sí muestra su lista entera.'
                }
              />
            )}

          {cargando &&
            [0, 1, 2, 3, 4].map((hueco) => (
              <div className="kr-padron__fila kr-padron__fila--esqueleto" key={hueco}>
                <Esqueleto alto={14} ancho="70%" />
                <Esqueleto alto={12} ancho="55%" />
              </div>
            ))}

          {!cargando && fallo === null && visibles.length === 0 && (
            <div className="kr-padron__vacio">
              {estado.chip === 'Con deuda' ? (
                <Aviso
                  tipo="vacio"
                  titulo="Nadie puede salir por «Con deuda»"
                  detalle="Ninguna operación de este backend publica la deuda del padrón contribuyente por contribuyente, así que este chip no tiene de dónde sacar la lista. Lo que sí se sabe es quién está en coactiva y quién quedó observado."
                />
              ) : enElPadron ? (
                <Aviso
                  tipo="vacio"
                  titulo="Ningún contribuyente coincide"
                  detalle="Puede estar con el código antiguo o con otro documento. Si viene a declarar por primera vez, créelo aquí mismo."
                >
                  <Boton variante="primario" onClick={abrirNuevo}>
                    Nuevo contribuyente
                  </Boton>
                </Aviso>
              ) : (
                /* «No hay ninguno» y «no se pudo leer» son dos frases distintas porque llevan a
                   dos sitios distintos: una a seguir trabajando, la otra a mirar el sistema. */
                <Aviso
                  tipo="vacio"
                  titulo={
                    estado.chip === 'En coactiva'
                      ? 'Ningún expediente coactivo abierto'
                      : 'Ningún contribuyente observado'
                  }
                  detalle={
                    estado.chip === 'En coactiva'
                      ? 'La consulta de cobranza coactiva respondió, y no hay ninguno en esta municipalidad.'
                      : 'La última corrida de emisión respondió, y no dejó a nadie fuera.'
                  }
                />
              )}
            </div>
          )}

          {!cargando &&
            visibles.map((fila) => (
              <button
                type="button"
                key={fila.clave}
                aria-current={estado.elegido === fila.codigo}
                className={`kr-padron__fila${
                  estado.elegido === fila.codigo ? ' kr-padron__fila--actual' : ''
                }`}
                onClick={() => {
                  if (fila.id === null) {
                    // Estas dos listas publican el codigo y no el identificador, asi que el
                    // expediente no se puede abrir desde aqui. Se busca por codigo, que es un
                    // criterio que el padron SI admite, en vez de adivinar el id.
                    rehacerLaConsulta({ chip: 'Todos', criterio: 'Código', q: fila.codigo });
                    return;
                  }
                  alCambiar({
                    elegido: fila.codigo,
                    paso: 0,
                    vals: {},
                    intento: false,
                  });
                }}
              >
                <span className="kr-padron__linea">
                  <span className="kr-padron__nombre">{fila.nombre}</span>
                  <Insignia tono={tonoDelEstado(fila.estado)}>{fila.estado}</Insignia>
                </span>
                <span className="kr-padron__titular">{fila.contexto}</span>
                <span className="kr-padron__pie">
                  <span className="kr-padron__codigo">{fila.codigo}</span>
                  <span className="kr-padron__separacion" />
                  {fila.id === null && (
                    <span className="kr-padron__abrir">Buscarlo en el padrón</span>
                  )}
                  {fila.importe !== null && (
                    <span className="kr-padron__importe">
                      <Importe
                        valor={fila.importe.importe}
                        fechaCalculo={fila.importe.actualizadoA}
                        fechaImplicita
                      />
                    </span>
                  )}
                </span>
              </button>
            ))}
        </div>

        {/* La paginacion solo gobierna el padron: las otras dos listas son de su propia
            operacion y su ventana no la mueve este pie. */}
        {enElPadron && padron.dato !== null && totalPaginas > 0 && (
          <nav className="kr-padron__paginacion" aria-label="Páginas del padrón">
            {/* «Página anterior» y no «Anterior»: el alta guiada tiene sus propios «Anterior» y
                «Continuar» en la misma pantalla, y dos botones con el mismo nombre accesible son
                dos botones que quien navega por teclado no puede distinguir. */}
            <Boton
              menudo
              aria-disabled={estado.pagina === 0}
              onClick={() => {
                if (estado.pagina > 0) alCambiar({ pagina: estado.pagina - 1 });
              }}
            >
              Página anterior
            </Boton>
            <span className="kr-padron__pagina">
              Página {(padron.dato.pagina + 1).toLocaleString('es-PE')} de{' '}
              {totalPaginas.toLocaleString('es-PE')}
            </span>
            <Boton
              menudo
              aria-disabled={!padron.dato.hayMas}
              onClick={() => {
                if (padron.dato?.hayMas === true) alCambiar({ pagina: estado.pagina + 1 });
              }}
            >
              Página siguiente
            </Boton>
          </nav>
        )}
      </div>

      <div className="kr-padron__expediente">
        {!hayFicha && (
          <div className="kr-padron__sin-eleccion">
            <Icono nombre="expediente" tamano={30} grosor={1.5} />
            <p className="kr-padron__sin-eleccion-titulo">Elija un contribuyente de la lista</p>
            <p className="kr-padron__sin-eleccion-detalle">
              El expediente se abre aquí al lado, sin salir de la lista. También puede crear un
              contribuyente nuevo.
            </p>
            <Boton variante="primario" onClick={abrirNuevo}>
              Nuevo contribuyente
            </Boton>
          </div>
        )}

        {hayFicha && (
          <Expediente
            fila={elegida}
            estado={estado}
            alCambiar={alCambiar}
            alEnsuciar={alEnsuciar}
            alAvisar={alAvisar}
          />
        )}
      </div>
    </main>
  );
}
