import { Aviso, Boton, Campo, Icono, Importe, Insignia } from '../ds/index.ts';
import {
  documentoCompleto,
  longitudDe,
  soloDigitos,
  TIPOS_DE_DOCUMENTO,
  TIPO_POR_OMISION,
} from '../dominio/documento.ts';
import {
  RUTAS,
  type BeneficioServido,
  type ContribuyenteDelPadron,
  type DeudaPorConcepto,
  type FichaDelContribuyente,
  type PredioServido,
} from '../datos/lecturas.ts';
import { useLista, usePagina, useUno } from '../datos/useRecurso.ts';
import { NUEVO, type EstadoDelPadron } from './estadoDelPadron.ts';
import { SECCIONES_DEL_EXPEDIENTE, valoresDelExpediente } from './expediente.ts';
import { tonoDelEstado } from './tonos.ts';
import { rutaDelDocumento, type FilaDelPadron } from './padron.ts';

/**
 * El expediente del contribuyente, y el alta guiada, que son la misma pantalla (AC4, AC6-AC9).
 *
 * En el artboard tambien lo son: las seis secciones, sus campos y su pie de navegacion se
 * dibujan igual se este mirando un contribuyente o creando uno. Lo unico que aparece al crear
 * es la **compuerta del documento** de arriba y el resumen «Lo que se va a registrar» al final.
 *
 * <h2>La compuerta comprueba el DOCUMENTO, no el codigo (AC7, AC8)</h2>
 *
 * Lo dice el propio artboard, y es la regla del dominio: *«El código de contribuyente lo asigna
 * el sistema. Lo que tiene que ser único es el documento.»* Asi que la compuerta hace dos cosas
 * y las dos se ven:
 *
 *   1. **La longitud exacta del tipo** —DNI 8, RUC 11, carnet de extranjeria 12—, que vive en
 *      `dominio/documento.ts` porque es una regla y no un dato de esta pantalla.
 *   2. **Que no este ya en el padron**, y desde I-4 eso **se le pregunta al backend**:
 *      `GET /rentas/contribuyentes?dNI=…`, que es un criterio que la operacion publica. El aviso
 *      nombra al contribuyente que ya lo tiene con su codigo, y si manana ese documento fuera de
 *      otra persona, el aviso diria la otra.
 *
 * <h2>Por que la comprobacion tuvo que salir de la pantalla (I-4)</h2>
 *
 * Hasta I-4 se comprobaba contra `padron`, la lista que la seccion habia cargado. Con las cinco
 * filas del artboard eso era el padron entero; contra la instalacion son **veinte de 10 603**, y
 * un documento que esta en la pagina 400 se habria declarado **libre**. El alta habria creado el
 * segundo codigo de la misma persona, que es exactamente el dano que esta compuerta existe para
 * impedir —«dos codigos para la misma persona parten su deuda en dos cuentas que nadie cruza»—,
 * y lo habria hecho **diciendo «Documento válido»**. No es una mejora de precision: es que la
 * comprobacion anterior, sobre datos de verdad, respondia que si a casi todo.
 *
 * <h2>Y hay un tipo que NO se puede comprobar, y se dice</h2>
 *
 * `ContribuyenteController.buscar` publica `dNI` y `rUC` y ningun parametro para los demas
 * tipos, asi que el **carne de extranjeria** no se puede consultar por aqui. La compuerta no
 * finge: dice que no puede comprobarlo y que quien decide es el `POST`, que contesta **409** si
 * el documento se repite. Fingir un «Documento válido» seria la version silenciosa del defecto
 * que este issue vino a cerrar.
 *
 * <h2>Lo que el contrato no publica queda vacio, y esta razonado</h2>
 *
 * Ver el javadoc de `expediente.ts`: la ficha publica el nombre y el domicilio compuestos, y
 * este formulario los pide por partes. Partirlos escribiria un apellido que nadie declaro.
 */
export interface ExpedienteProps {
  /** La fila del padron elegida, o `null` cuando se esta creando. */
  readonly fila: FilaDelPadron | null;
  readonly estado: EstadoDelPadron;
  readonly alCambiar: (cambio: Partial<EstadoDelPadron>) => void;
  readonly alEnsuciar: () => void;
  readonly alAvisar: (texto: string) => void;
}

/** `'2026-01-01'` + `null` → `'2026 — indefinida'`, como lo escribe el artboard. */
function vigencia(desde: string, hasta: string | null): string {
  const anio = desde.slice(0, 4);
  return hasta === null ? `${anio} — indefinida` : anio;
}

/** El texto de la columna «Cuotas»: `[3, 4]` → «3 y 4»; `[1, 8]` → «1 a 8»; `[1, 1]` → «1». */
function cuotas(desde: number, hasta: number): string {
  if (desde === hasta) {
    return String(desde);
  }
  return hasta - desde === 1 ? `${String(desde)} y ${String(hasta)}` : `${String(desde)} a ${String(hasta)}`;
}

/** Lo que no publica ninguna operacion se ensena asi, y no en blanco. */
const SIN_DATO = '—';

export function Expediente({
  fila,
  estado,
  alCambiar,
  alEnsuciar,
  alAvisar,
}: ExpedienteProps) {
  const nuevo = estado.elegido === NUEVO;
  const id = fila?.contribuyente.id ?? null;

  const ficha = useUno<FichaDelContribuyente>(id === null ? null : RUTAS.ficha(id));
  const predios = useLista<PredioServido>(id === null ? null : RUTAS.predios);
  const beneficios = useLista<BeneficioServido>(
    fila === null ? null : RUTAS.beneficiosDe(fila.contribuyente.codigo),
  );
  const deuda = useLista<DeudaPorConcepto>(id === null ? null : RUTAS.deuda);

  const servidos = valoresDelExpediente({
    ficha: ficha.dato,
    predios: predios.dato,
    beneficios: beneficios.dato,
    deuda: deuda.dato,
  });

  const valor = (clave: string) => estado.vals[clave] ?? servidos[clave] ?? '';
  const escribir = (clave: string, texto: string) => {
    alCambiar({ vals: { ...estado.vals, [clave]: texto } });
    alEnsuciar();
  };

  // ── La compuerta del documento ────────────────────────────────────────────────────────────
  const tipoDoc = valor('docTipo') === '' ? TIPO_POR_OMISION : valor('docTipo');
  const largoDoc = longitudDe(tipoDoc);
  const numeroDoc = valor('docNumero');
  const listo = documentoCompleto(numeroDoc, tipoDoc);
  // `null` en dos casos que no son el mismo: el documento aun no esta completo, o su tipo no es
  // uno de los dos que la operacion admite. El segundo se distingue abajo y se dice.
  const consulta = listo ? rutaDelDocumento(RUTAS.padron, tipoDoc, numeroDoc) : null;
  const yaEnElPadron = usePagina<ContribuyenteDelPadron>(nuevo ? consulta : null);
  const seSabeSiEstaLibre = !nuevo || !listo || consulta !== null;
  const duenoDelDocumento = yaEnElPadron.dato?.contenido[0] ?? null;
  const duplicado = duenoDelDocumento !== null;
  const comprobando = yaEnElPadron.cargando;
  const noSePudoComprobar = yaEnElPadron.error !== null;

  // El codigo lo asigna el sistema. Mientras no hay backend que lo asigne, se compone como lo
  // compone el artboard: es un marcador de posicion, y por eso se ensena y no se manda.
  const codigoAsignado = listo
    ? `000001526${String(15 + (numeroDoc.length % 9)).slice(0, 2)}`
    : SIN_DATO;

  const seccion = SECCIONES_DEL_EXPEDIENTE[Math.min(estado.paso, SECCIONES_DEL_EXPEDIENTE.length - 1)];
  if (seccion === undefined) {
    throw new Error('El expediente no tiene ninguna sección: la definición del artboard está vacía.');
  }
  const ultima = estado.paso >= SECCIONES_DEL_EXPEDIENTE.length - 1;

  /** Cuantos obligatorios quedan vacios en esa seccion. */
  const faltanEn = (cuales: (typeof SECCIONES_DEL_EXPEDIENTE)[number]) =>
    cuales.campos.filter(
      (campo) =>
        campo.opcional !== true &&
        campo.tipo !== 'ro' &&
        campo.tipo !== 'chk' &&
        valor(campo.clave) === '',
    ).length;

  const pendientesPorSeccion = SECCIONES_DEL_EXPEDIENTE.map(faltanEn);
  const pendientes = pendientesPorSeccion.reduce((suma, cuantos) => suma + cuantos, 0);
  // Mientras la pregunta esta en el aire no se crea: un alta lanzada sobre «todavia no se sabe»
  // se resuelve en el peor caso posible, que es el duplicado que la compuerta existe para
  // impedir. Y si la pregunta FALLO tampoco: no saber no es lo mismo que saber que esta libre.
  const puedeCrear =
    listo && !duplicado && !comprobando && !noSePudoComprobar && pendientes === 0;
  const motivo = duplicado
    ? 'Ese documento ya está registrado a nombre de otro contribuyente.'
    : !listo
      ? 'Falta el número de documento completo.'
      : comprobando
        ? 'Se está comprobando si ese documento ya está en el padrón.'
        : noSePudoComprobar
          ? 'No se pudo comprobar en el padrón si ese documento ya existe.'
          : pendientes > 0
            ? `Quedan ${String(pendientes)} datos obligatorios sin llenar.`
            : '';

  const bloqueado = nuevo && ultima && !puedeCrear;

  return (
    <div className="kr-ficha">
      <div className="kr-ficha__cabecera">
        <div className="kr-ficha__identidad">
          <span className="kr-ficha__codigo">
            {nuevo ? (listo ? codigoAsignado : 'Sin código') : (fila?.contribuyente.codigo ?? '')}
          </span>
          <Insignia tono={nuevo ? 'atencion' : tonoDelEstado(fila?.estado ?? '')}>
            {nuevo ? 'Borrador' : (fila?.estado ?? '')}
          </Insignia>
          <span className="kr-ficha__hueco" />
          {(nuevo
            ? [
                ['Descartar', false],
                ['Guardar borrador', false],
              ]
            : [
                ['Estado de cuenta', false],
                ['Declaración jurada', false],
                ['Determinar el predial', true],
              ]
          ).map(([rotulo, primaria]) => (
            <Boton
              key={String(rotulo)}
              variante={primaria === true ? 'primario' : 'secundario'}
              onClick={() => {
                if (rotulo === 'Descartar') {
                  alCambiar({ elegido: null, vals: {}, intento: false, paso: 0 });
                  alAvisar('Borrador descartado.');
                  return;
                }
                alAvisar(
                  `${String(rotulo)}: emitido para ${fila?.contribuyente.nombreRazonSocial ?? 'el contribuyente'}.`,
                );
              }}
            >
              {String(rotulo)}
            </Boton>
          ))}
        </div>
        <p className="kr-ficha__titulo">
          {nuevo
            ? valor('apPaterno') === '' && valor('razonSocial') === ''
              ? 'Contribuyente nuevo'
              : valor('razonSocial') !== ''
                ? valor('razonSocial')
                : `${valor('apPaterno')} ${valor('apMaterno')}, ${valor('nombres')}`
            : (fila?.contribuyente.nombreRazonSocial ?? '')}
        </p>
        <p className="kr-ficha__contexto">
          {nuevo
            ? listo
              ? `${tipoDoc} ${numeroDoc} · borrador, nada se registra hasta la última sección`
              : 'Sin documento · borrador, nada se registra hasta la última sección'
            : `${fila?.contribuyente.tipoDocumento ?? ''} ${fila?.contribuyente.numeroDocumento ?? ''} · ${
                fila?.contribuyente.tipoPersona ?? ''
              }`}
        </p>
      </div>

      {nuevo && (
        <div className="kr-compuerta">
          <div className="kr-compuerta__cabecera">
            <p className="kr-compuerta__rotulo">Documento de identidad</p>
            <p className="kr-compuerta__regla">
              El código de contribuyente lo asigna el sistema. Lo que tiene que ser único es el
              documento.
            </p>
            <Insignia
              tono={
                duplicado || noSePudoComprobar
                  ? 'mal'
                  : listo && !comprobando && seSabeSiEstaLibre
                    ? 'ok'
                    : 'atencion'
              }
            >
              {duplicado
                ? 'Documento ya registrado'
                : noSePudoComprobar
                  ? 'No se pudo comprobar'
                  : !listo
                    ? `${String(numeroDoc.length)} de ${String(largoDoc)} dígitos`
                    : comprobando
                      ? 'Comprobando en el padrón…'
                      : seSabeSiEstaLibre
                        ? 'Documento válido'
                        : 'Sin comprobar en el padrón'}
            </Insignia>
          </div>
          <div className="kr-compuerta__controles">
            <Campo
              etiqueta="Tipo"
              tipo="sel"
              opciones={TIPOS_DE_DOCUMENTO}
              valor={tipoDoc}
              onCambio={(elegido) => {
                // Cambiar de tipo vacia el numero: ocho digitos tecleados como DNI no son los
                // ocho primeros de un RUC, son un documento distinto a medio escribir.
                alCambiar({ vals: { ...estado.vals, docTipo: elegido, docNumero: '' } });
                alEnsuciar();
              }}
            />
            <Campo
              etiqueta="Número"
              tipo="text"
              valor={numeroDoc}
              ph={'·'.repeat(largoDoc)}
              error={duplicado ? 'Ese documento ya está en el padrón.' : undefined}
              onCambio={(tecleado) => {
                escribir('docNumero', soloDigitos(tecleado, largoDoc));
              }}
            />
            <p className="kr-compuerta__asignado">
              <span className="kr-compuerta__asignado-rotulo">Código que se asignará</span>
              <code className="kr-compuerta__asignado-valor">{codigoAsignado}</code>
            </p>
          </div>
          {(duplicado || !listo || noSePudoComprobar || !seSabeSiEstaLibre) && (
            <p
              className={`kr-compuerta__aviso${duplicado || noSePudoComprobar ? ' kr-compuerta__aviso--mal' : ''}`}
            >
              {duplicado && duenoDelDocumento !== null
                ? `Ese documento ya está en el padrón, a nombre de ${duenoDelDocumento.nombreRazonSocial} (${duenoDelDocumento.codigo}). Dos códigos para la misma persona parten su deuda en dos cuentas que nadie cruza: abra el contribuyente que ya existe.`
                : noSePudoComprobar
                  ? `No se pudo preguntar al padrón si ese documento ya existe: ${yaEnElPadron.error ?? ''} Hasta que se pueda, el alta queda bloqueada: crear un contribuyente duplicado es el error que más cuesta deshacer.`
                  : !listo
                    ? `El ${tipoDoc} tiene ${String(largoDoc)} dígitos. Se comprueba contra el padrón antes de crear el código.`
                    : `El padrón sólo se puede consultar por DNI y por RUC, así que desde aquí no se puede comprobar si ese ${tipoDoc.toLowerCase()} ya está registrado. Si lo estuviera, el alta se rechazará al guardarla.`}
            </p>
          )}
        </div>
      )}

      <div className="kr-ficha__pestanas" role="tablist" aria-label="Secciones del expediente">
        {SECCIONES_DEL_EXPEDIENTE.map((una, i) => (
          <button
            type="button"
            key={una.id}
            role="tab"
            aria-selected={estado.paso === i}
            className={`kr-ficha__pestana${estado.paso === i ? ' kr-ficha__pestana--actual' : ''}`}
            onClick={() => {
              alCambiar({ paso: i });
            }}
          >
            <span>{una.rotulo}</span>
            {nuevo && (pendientesPorSeccion[i] ?? 0) > 0 && (
              <span className="kr-ficha__pendientes">{pendientesPorSeccion[i]}</span>
            )}
          </button>
        ))}
      </div>

      <div className="kr-ficha__cuerpo">
        <div className="kr-ficha__ancho">
          <p className="kr-ficha__nota">{seccion.nota}</p>

          {ficha.error !== null && (
            <Aviso tipo="error" titulo="No se pudo leer el expediente" detalle={ficha.error} />
          )}

          <div className="kr-rejilla">
            {seccion.campos.map((campo) => (
              <Campo
                key={campo.clave}
                etiqueta={campo.etiqueta}
                tipo={campo.tipo}
                opciones={campo.opciones}
                ancho={campo.ancho}
                opcional={campo.opcional}
                ph={campo.ph}
                ayuda={campo.ayuda}
                cargando={!nuevo && ficha.cargando}
                marcado={valor(campo.clave) !== ''}
                valor={valor(campo.clave)}
                error={
                  estado.intento &&
                  campo.opcional !== true &&
                  campo.tipo !== 'ro' &&
                  campo.tipo !== 'chk' &&
                  valor(campo.clave) === ''
                    ? 'Este dato es obligatorio.'
                    : undefined
                }
                onCambio={(texto) => {
                  escribir(campo.clave, texto);
                }}
              />
            ))}
          </div>

          {seccion.tabla !== undefined && (
            <section className="kr-tarjeta kr-tarjeta--tabla">
              <div className="kr-tarjeta__cabecera">
                <h3 className="kr-tarjeta__titulo">{seccion.tabla.titulo}</h3>
                <Boton
                  menudo
                  onClick={() => {
                    alAvisar(`${seccion.tabla?.accion ?? ''}: todavía no está construido.`);
                  }}
                >
                  {seccion.tabla.accion}
                </Boton>
              </div>
              <div className="kr-tabla__marco">
                <table className={`kr-tabla kr-tabla--${seccion.id}`}>
                  <thead>
                    <tr>
                      {seccion.tabla.columnas.map(([rotulo, derecha]) => (
                        <th key={rotulo} className={derecha ? 'kr-tabla__th--cifra' : undefined}>
                          {rotulo}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {seccion.id === 'unidades' &&
                      predios.dato?.map((predio) => (
                        <tr key={predio.predioId}>
                          <td className="kr-tabla__td--clave">
                            {predio.codigoReferenciaCatastral}
                          </td>
                          <td>{predio.direccion}</td>
                          <td>{predio.uso}</td>
                          <td className="kr-tabla__td--cifra">{predio.areaTerreno}</td>
                          <td className="kr-tabla__td--cifra">{predio.porcentajePropiedad}</td>
                          {/* El autovaluo del predio lo calcula catastro y llega sellado
                              (ADR-0024): `GET /rentas/predios` no lo publica. */}
                          <td className="kr-tabla__td--cifra">{SIN_DATO}</td>
                        </tr>
                      ))}
                    {seccion.id === 'beneficios' &&
                      beneficios.dato?.map((beneficio) => (
                        <tr key={beneficio.id}>
                          {/* El contrato publica la RESOLUCION que concede el beneficio, no el
                              expediente con que se pidio. */}
                          <td className="kr-tabla__td--clave">{SIN_DATO}</td>
                          <td>{beneficio.tipo}</td>
                          <td>{beneficio.documentoOrigen}</td>
                          <td>{vigencia(beneficio.vigenciaDesde, beneficio.vigenciaHasta)}</td>
                          <td>{beneficio.clase}</td>
                          <td>{SIN_DATO}</td>
                        </tr>
                      ))}
                    {seccion.id === 'cuenta' &&
                      deuda.dato?.map((obligacion) => (
                        <tr key={`${obligacion.tributo}-${String(obligacion.ejercicio)}`}>
                          <td className="kr-tabla__td--clave">{obligacion.ejercicio}</td>
                          <td>{obligacion.tributo}</td>
                          <td>{cuotas(obligacion.periodoDesde, obligacion.periodoHasta)}</td>
                          <td className="kr-tabla__td--cifra">
                            <Importe
                              valor={obligacion.deuda.insoluto.importe}
                              fechaCalculo={obligacion.deuda.insoluto.actualizadoA}
                              fechaImplicita
                            />
                          </td>
                          <td className="kr-tabla__td--cifra">
                            <Importe
                              valor={obligacion.deuda.interes.importe}
                              fechaCalculo={obligacion.deuda.interes.actualizadoA}
                              fechaImplicita
                            />
                          </td>
                          <td className="kr-tabla__td--cifra">
                            <Importe
                              valor={obligacion.deuda.total.importe}
                              fechaCalculo={obligacion.deuda.total.actualizadoA}
                              fechaImplicita
                            />
                          </td>
                          <td>{obligacion.fase}</td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
              {nuevo && <p className="kr-tabla__vacio">{seccion.tabla.vacioTexto}</p>}
              {/* Un 200 con lista vacia es un ESTADO, no una averia (AC9): la operacion
                  contesto, y lo que contesto es que este contribuyente no tiene ninguno. Se
                  distingue de «no se pudo leer», que es lo de la linea siguiente. */}
              {!nuevo && seccion.id === 'beneficios' && beneficios.dato?.length === 0 && (
                <p className="kr-tabla__vacio">
                  Este contribuyente no tiene ningún beneficio registrado.
                </p>
              )}
              {!nuevo && seccion.id === 'beneficios' && beneficios.error !== null && (
                <p className="kr-tabla__vacio kr-tabla__vacio--mal">
                  No se pudieron leer los beneficios: {beneficios.error}
                </p>
              )}
              <p className="kr-tarjeta__pie">{seccion.tabla.nota}</p>
            </section>
          )}

          {nuevo && ultima && (
            <section className="kr-tarjeta kr-cierre">
              <div className="kr-cierre__cabecera">
                <h3 className="kr-tarjeta__titulo">Lo que se va a registrar</h3>
                <p className="kr-cierre__nota">
                  Una ficha registrada entra en el padrón y desde ese momento el predio genera
                  obligación predial.
                </p>
              </div>
              {[
                {
                  titulo: `Se crea el contribuyente ${listo ? codigoAsignado : 'sin código'}`,
                  detalle: listo
                    ? 'El código lo asigna el sistema y ya no cambia: enlaza predios, vehículos, licencias y papeletas.'
                    : 'Falta el documento para poder asignarlo.',
                  valor: listo ? codigoAsignado : SIN_DATO,
                  hecho: listo,
                },
                {
                  titulo: 'Queda en el padrón sin unidades afectas',
                  detalle:
                    'Un contribuyente sin predio ni vehículo existe y no debe nada. La obligación nace cuando se le vincula una unidad.',
                  valor: 'Sin deuda',
                  hecho: true,
                },
                {
                  titulo: 'No se le determina nada todavía',
                  detalle:
                    'La determinación del predial se hace después, sobre el autovalúo de los predios que se le vinculen.',
                  valor: 'Después',
                  hecho: false,
                },
                {
                  titulo: 'El alta queda en la bitácora',
                  detalle:
                    'Con tu usuario y la hora. Crear un contribuyente duplicado es el error que más cuesta deshacer.',
                  valor: 'Auditoría',
                  hecho: true,
                },
              ].map((linea) => (
                <div className="kr-cierre__linea" key={linea.titulo}>
                  <span
                    className={`kr-cierre__marca kr-cierre__marca--${linea.hecho ? 'ok' : 'pendiente'}`}
                  >
                    <Icono nombre={linea.hecho ? 'visto' : 'alerta'} tamano={13} grosor={2.6} />
                  </span>
                  <span className="kr-cierre__texto">
                    <span className="kr-cierre__titulo">{linea.titulo}</span>
                    <span className="kr-cierre__detalle">{linea.detalle}</span>
                  </span>
                  <span className="kr-cierre__valor">{linea.valor}</span>
                </div>
              ))}
              <p
                className={`kr-cierre__veredicto kr-cierre__veredicto--${puedeCrear ? 'ok' : 'mal'}`}
              >
                {puedeCrear
                  ? 'Todo listo. Al crear el contribuyente entra en el padrón y se le puede vincular un predio.'
                  : `No se puede crear todavía. ${motivo}`}
              </p>
            </section>
          )}
        </div>
      </div>

      <div className="kr-ficha__pie">
        {/* `aria-disabled` y no `disabled`, como el artboard y por el mismo motivo que el
            `Campo` bloqueado de F-2: un control deshabilitado sale del recorrido del tabulador,
            y en ventanilla se trabaja con teclado. Pulsarlo en la primera seccion no hace nada,
            que es lo que el rotulo ya anuncia. */}
        <Boton
          aria-disabled={estado.paso === 0}
          onClick={() => {
            alCambiar({ paso: Math.max(estado.paso - 1, 0) });
          }}
        >
          Anterior
        </Boton>
        <p className="kr-ficha__pie-nota">
          {ultima
            ? nuevo
              ? puedeCrear
                ? 'Al crear el contribuyente se le puede vincular un predio.'
                : motivo
              : 'Los datos del contribuyente afectan la determinación del ejercicio en curso.'
            : nuevo
              ? 'El borrador se guarda al avanzar.'
              : 'Los cambios se guardan al avanzar de sección.'}
        </p>
        <Boton
          variante="primario"
          aria-disabled={bloqueado}
          title={bloqueado ? motivo : undefined}
          onClick={() => {
            if (ultima && nuevo) {
              if (!puedeCrear) {
                alCambiar({ intento: true });
                alAvisar(motivo);
                return;
              }
              alCambiar({ elegido: codigoAsignado, paso: 0, intento: false });
              alAvisar(
                `Contribuyente ${codigoAsignado} creado. Ya se le puede vincular un predio.`,
              );
              return;
            }
            if (ultima) {
              alAvisar('Cambios guardados en el expediente.');
              return;
            }
            alCambiar({ paso: estado.paso + 1 });
            alAvisar(nuevo ? 'Guardado en el borrador.' : 'Cambios guardados.');
          }}
        >
          {ultima ? (nuevo ? 'Crear el contribuyente' : 'Guardar los cambios') : 'Continuar'}
        </Boton>
      </div>
    </div>
  );
}
