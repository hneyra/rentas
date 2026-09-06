import { Aviso, Boton, Esqueleto, Icono, Importe, Insignia } from '../ds/index.ts';
import {
  RUTAS,
  type ContribuyenteDelPadron,
  type CorridaDelPredial,
  type DeudaEnCoactiva,
  type ObservadoDeLaCorrida,
} from '../datos/lecturas.ts';
import { useLista, useUno } from '../datos/useRecurso.ts';
import { Expediente } from './Expediente.tsx';
import { CHIPS, NUEVO, ORDENES, type EstadoDelPadron } from './estadoDelPadron.ts';
import { componerPadron, filtrar, ordenar } from './padron.ts';
import { tonoDelEstado } from './tonos.ts';

/**
 * La seccion «Contribuyentes» (clave `predios`): el padron a la izquierda y el expediente al
 * lado, sin salir de la lista (AC2 a AC9).
 *
 * <h2>Los datos salen del proxy, y ninguno se importa (AC2)</h2>
 *
 * Esta pantalla **no importa `datos/prototipo.ts`**, ni directa ni indirectamente: pide por HTTP
 * las mismas rutas que va a pedir el dia de la integracion —`GET /rentas/contribuyentes`,
 * `GET /coactiva/deudas`, `GET /rentas/predial/corridas/ultima` y sus observados— y el proxy de
 * #4 las atiende sustituyendo el transporte. Ni un `fetch` suelto: la unica puerta es
 * `solicitar()`, y de eso responde la prohibicion `fetch-fuera-del-cliente` de ESLint.
 *
 * De donde sale cada campo de la fila, y cual no sale de ninguna parte, esta razonado y medido
 * en `padron.ts`.
 *
 * <h2>El vacio no es mudo (AC3)</h2>
 *
 * Cuando la busqueda no encuentra a nadie, el texto es el del artboard, entero, y lleva el boton
 * que resuelve el caso que lo provoca: quien viene a declarar por primera vez no esta en el
 * padron **porque todavia no existe**, y crearlo es lo siguiente que hay que hacer.
 */
export interface ContribuyentesProps {
  readonly estado: EstadoDelPadron;
  readonly alCambiar: (cambio: Partial<EstadoDelPadron>) => void;
  readonly alEnsuciar: () => void;
  readonly alAvisar: (texto: string) => void;
}

export function Contribuyentes({
  estado,
  alCambiar,
  alEnsuciar,
  alAvisar,
}: ContribuyentesProps) {
  const padron = useLista<ContribuyenteDelPadron>(RUTAS.padron);
  const coactiva = useLista<DeudaEnCoactiva>(RUTAS.coactiva);
  const corrida = useUno<CorridaDelPredial>(RUTAS.ultimaCorrida);
  const observados = useLista<ObservadoDeLaCorrida>(
    corrida.dato === null ? null : RUTAS.observados(corrida.dato.id),
  );

  const filas = componerPadron(padron.dato ?? [], coactiva.dato ?? [], observados.dato ?? []);
  const visibles = ordenar(filtrar(filas, estado.q, estado.chip), estado.orden);

  const nuevo = estado.elegido === NUEVO;
  const elegida =
    estado.elegido === null || nuevo
      ? null
      : (filas.find((fila) => fila.contribuyente.codigo === estado.elegido) ?? null);
  // Un codigo elegido que no esta en el padron servido —el que acaba de «crear» el alta— sigue
  // abriendo el expediente: lo que no hay es fila de la que sacar su estado.
  const hayFicha = nuevo || elegida !== null;

  const abrirNuevo = () => {
    alCambiar({ elegido: NUEVO, paso: 0, vals: {}, intento: false });
    alAvisar('Contribuyente nuevo: empiece por el documento de identidad.');
  };

  return (
    <main className="kr-marco__lienzo kr-seccion kr-padron">
      <div className="kr-padron__lista">
        <div className="kr-padron__buscador">
          <div className="kr-busqueda">
            <Icono nombre="lupa" tamano={15} grosor={1.8} />
            <input
              className="kr-busqueda__campo"
              value={estado.q}
              placeholder="Nombre, DNI, RUC o código"
              aria-label="Buscar en el padrón"
              onChange={(evento) => {
                alCambiar({ q: evento.target.value });
              }}
            />
            {estado.q !== '' && (
              <button
                type="button"
                aria-label="Limpiar la búsqueda"
                className="kr-busqueda__limpiar"
                onClick={() => {
                  alCambiar({ q: '' });
                }}
              >
                <Icono nombre="cerrar" tamano={14} grosor={2.2} />
              </button>
            )}
          </div>
          <div className="kr-padron__chips">
            {CHIPS.map((chip) => (
              <button
                type="button"
                key={chip}
                aria-pressed={estado.chip === chip}
                className={`kr-chip${estado.chip === chip ? ' kr-chip--on' : ''}`}
                onClick={() => {
                  alCambiar({ chip });
                }}
              >
                {chip}
              </button>
            ))}
          </div>
        </div>

        <div className="kr-padron__conteo">
          <span className="kr-padron__cuantos">
            {padron.dato === null
              ? 'Cargando el padrón…'
              : `${String(visibles.length)} de ${String(padron.dato.length)}`}
          </span>
          <label className="kr-padron__orden">
            <span className="kr-padron__orden-rotulo">Ordenar la lista</span>
            <select
              value={estado.orden}
              onChange={(evento) => {
                alCambiar({ orden: evento.target.value });
              }}
            >
              {ORDENES.map((orden) => (
                <option key={orden} value={orden}>
                  {orden}
                </option>
              ))}
            </select>
          </label>
        </div>

        <div className="kr-padron__filas" aria-busy={padron.cargando}>
          {padron.error !== null && (
            <Aviso tipo="error" titulo="No se pudo leer el padrón" detalle={padron.error} />
          )}

          {/* El estado de cobranza llega de otras dos operaciones. Si una falla, la lista se
              dibuja igual —los cinco estan— pero **con el estado incompleto**: quien esta en
              coactiva se veria como «Activo». Callarlo seria peor que no tener el dato. */}
          {padron.error === null && (coactiva.error !== null || observados.error !== null) && (
            <Aviso
              tipo="error"
              titulo="El estado de cobranza no está completo"
              detalle={
                'La lista está, pero no se pudo leer quién está en coactiva o quién quedó ' +
                'observado. Los chips de esos dos estados no encontrarán a nadie.'
              }
            />
          )}

          {padron.cargando &&
            [0, 1, 2, 3, 4].map((hueco) => (
              <div className="kr-padron__fila kr-padron__fila--esqueleto" key={hueco}>
                <Esqueleto alto={14} ancho="70%" />
                <Esqueleto alto={12} ancho="55%" />
              </div>
            ))}

          {!padron.cargando && padron.error === null && visibles.length === 0 && (
            <div className="kr-padron__vacio">
              <Aviso
                tipo="vacio"
                titulo="Ningún contribuyente coincide"
                detalle="Puede estar con el código antiguo o con otro documento. Si viene a declarar por primera vez, créelo aquí mismo."
              >
                <Boton variante="primario" onClick={abrirNuevo}>
                  Nuevo contribuyente
                </Boton>
              </Aviso>
              {estado.chip === 'Con deuda' && (
                <p className="kr-padron__hueco-del-contrato">
                  Y por «Con deuda» no va a salir nadie: ninguna operación de este backend publica
                  la deuda del padrón contribuyente por contribuyente. Lo que sí se sabe es quién
                  está en coactiva y quién quedó observado.
                </p>
              )}
            </div>
          )}

          {visibles.map((fila) => (
            <button
              type="button"
              key={fila.contribuyente.codigo}
              aria-current={estado.elegido === fila.contribuyente.codigo}
              className={`kr-padron__fila${
                estado.elegido === fila.contribuyente.codigo ? ' kr-padron__fila--actual' : ''
              }`}
              onClick={() => {
                alCambiar({
                  elegido: fila.contribuyente.codigo,
                  paso: 0,
                  vals: {},
                  intento: false,
                });
              }}
            >
              <span className="kr-padron__linea">
                <span className="kr-padron__nombre">
                  {fila.contribuyente.nombreRazonSocial}
                </span>
                <Insignia tono={tonoDelEstado(fila.estado)}>{fila.estado}</Insignia>
              </span>
              <span className="kr-padron__titular">
                {fila.contribuyente.tipoDocumento} {fila.contribuyente.numeroDocumento} ·{' '}
                {fila.contribuyente.tipoPersona}
              </span>
              <span className="kr-padron__pie">
                <span className="kr-padron__codigo">{fila.contribuyente.codigo}</span>
                <span className="kr-padron__separacion" />
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
            padron={padron.dato ?? []}
          />
        )}
      </div>
    </main>
  );
}
