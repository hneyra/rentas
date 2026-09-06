import { compararImportes } from '../dominio/formato.ts';
import type {
  ContribuyenteDelPadron,
  DeudaEnCoactiva,
  ImporteConFecha,
  ObservadoDeLaCorrida,
} from '../datos/lecturas.ts';

/**
 * La fila del padron, compuesta de lo que publican TRES operaciones.
 *
 * <h2>El hallazgo que obliga a componerla, medido</h2>
 *
 * El artboard construye cada fila de la lista sobre cinco cosas: nombre, documento, codigo,
 * **estado de cobranza** e **importe**. `GET /rentas/contribuyentes` publica ocho campos —`id`,
 * `codigo`, `tipoDocumento`, `numeroDocumento`, `tipoPersona`, `nombreRazonSocial`,
 * `condicionEspecial`, `activo`— y **ni el estado de cobranza ni el importe estan entre ellos**.
 * No es una carencia del proxy: es lo que declara `docs/50-api/formas-de-la-api.json`, generado
 * del tipo de retorno del controlador.
 *
 * Recorridas **las 181 operaciones** del contrato, ninguna publica, por contribuyente y en una
 * lista, el estado de cobranza con su deuda. Lo que si publican, y de ahi salen dos de los
 * cuatro chips del artboard:
 *
 *   · `GET /coactiva/deudas` — quien tiene expediente coactivo abierto, con su total y **la
 *     fecha a la que esta ese total** (regla 9). De ahi sale «En coactiva», y el unico importe
 *     de la lista que se puede ensenar sin inventarlo.
 *   · `GET /rentas/predial/corridas/{corridaId}/observados` — quien quedo fuera de la emision.
 *     De ahi sale «Observado».
 *
 * **«Con deuda» y «Al día» no los contesta nadie**, y esta pantalla no se los inventa: quien no
 * esta en ninguna de las dos listas se ensena con lo que el padron SI publica de el —«Activo» o
 * «De baja», de `activo`—. El chip «Con deuda» sigue estando, filtra, y sale vacio: un chip que
 * devolviera resultados a ojo diria a la ventanilla quien debe y quien no sin que ningun sistema
 * lo sostenga, que es peor que una lista vacia que dice por que lo esta.
 *
 * Cerrarlo es del backend, no de aqui: el padron tendria que publicar el saldo del contribuyente
 * con su fecha, como ya hace `GET /consultas/unificada` para uno solo.
 */
export interface FilaDelPadron {
  readonly contribuyente: ContribuyenteDelPadron;
  /** El estado que se ensena en la insignia. Nunca vacio. */
  readonly estado: string;
  /** El importe con su fecha, cuando alguna operacion lo publica. */
  readonly importe: ImporteConFecha | null;
  /** El expediente coactivo, cuando lo hay. */
  readonly expediente: string | null;
  /** Por que quedo fuera de la emision, cuando lo esta. */
  readonly motivo: string | null;
}

/** El estado de quien no aparece ni en coactiva ni entre los observados. */
function estadoDelPadron(contribuyente: ContribuyenteDelPadron): string {
  return contribuyente.activo ? 'Activo' : 'De baja';
}

/** Une las tres respuestas en una fila por contribuyente, en el orden en que llego el padron. */
export function componerPadron(
  padron: readonly ContribuyenteDelPadron[],
  coactiva: readonly DeudaEnCoactiva[],
  observados: readonly ObservadoDeLaCorrida[],
): readonly FilaDelPadron[] {
  return padron.map((contribuyente) => {
    const enCoactiva = coactiva.find((uno) => uno.codContribuyente === contribuyente.codigo);
    const observado = observados.find((uno) => uno.codContribuyente === contribuyente.codigo);

    if (enCoactiva !== undefined) {
      return {
        contribuyente,
        estado: enCoactiva.estado,
        importe: { importe: enCoactiva.totalS, actualizadoA: enCoactiva.aLaFecha },
        expediente: enCoactiva.expediente,
        motivo: observado?.motivo ?? null,
      };
    }

    return {
      contribuyente,
      estado: observado === undefined ? estadoDelPadron(contribuyente) : 'Observado',
      importe: null,
      expediente: null,
      motivo: observado?.motivo ?? null,
    };
  });
}

/** Todo lo que el buscador mira de una fila, en minuscula. */
function comoSeBusca(fila: FilaDelPadron): string {
  const quien = fila.contribuyente;
  return [
    quien.codigo,
    quien.nombreRazonSocial,
    quien.tipoDocumento,
    quien.numeroDocumento,
    quien.tipoPersona,
  ]
    .join(' ')
    .toLowerCase();
}

/**
 * Las filas que casan con lo tecleado y con el chip.
 *
 * El buscador mira **codigo, nombre, tipo y numero de documento y tipo de persona**, que es lo
 * que la caja del artboard promete: «Nombre, DNI, RUC o código». Sin acentos no: quien busca
 * «Diaz» no encuentra a «Díaz», y eso es del backend —`unaccent` esta en el esquema— el dia que
 * la busqueda la haga el servidor.
 */
export function filtrar(
  filas: readonly FilaDelPadron[],
  q: string,
  chip: string,
): readonly FilaDelPadron[] {
  const buscado = q.trim().toLowerCase();
  return filas.filter((fila) => {
    const casa = buscado === '' || comoSeBusca(fila).includes(buscado);
    return casa && (chip === 'Todos' || fila.estado === chip);
  });
}

/**
 * Las filas ordenadas.
 *
 * «Código» es el orden en que llego el padron y no se reordena: el backend lo devuelve por
 * codigo y reordenarlo aqui seria hacer dos veces lo mismo, mal la segunda cuando pagine.
 *
 * «Deuda» pone delante al que mas debe, y **las filas sin importe van al final**: no es que
 * deban cero, es que ninguna operacion publica cuanto deben, y colocarlas entre los ceros diria
 * que estan al dia.
 */
export function ordenar(filas: readonly FilaDelPadron[], orden: string): readonly FilaDelPadron[] {
  if (orden === 'Nombre') {
    return [...filas].sort((a, b) =>
      a.contribuyente.nombreRazonSocial.localeCompare(b.contribuyente.nombreRazonSocial, 'es'),
    );
  }
  if (orden === 'Deuda') {
    return [...filas].sort((a, b) => {
      if (a.importe === null && b.importe === null) {
        return 0;
      }
      if (a.importe === null) {
        return 1;
      }
      if (b.importe === null) {
        return -1;
      }
      return compararImportes(b.importe.importe, a.importe.importe);
    });
  }
  return filas;
}
