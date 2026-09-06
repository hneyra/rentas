import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { desinstalarProxyDeDatos, instalarProxyDeDatos, RAIZ } from '../api/proxy.ts';
import { OPERACIONES, claveDe, type Operacion } from './operaciones.ts';

/**
 * Lo que sirve el proxy tiene la forma que publica el backend.
 *
 * <h2>Por que esta prueba es la que justifica el proxy</h2>
 *
 * Un proxy que sirviera una forma inventada no adelantaria trabajo: lo duplicaria. Cada
 * pantalla escrita contra `deudaTotal` habria que reescribirla el dia que el backend
 * conteste `deuda.total`, y el defecto no se veria hasta ese dia — con la interfaz entera ya
 * construida encima.
 *
 * Asi que la forma no se elige aqui. `docs/50-api/formas-de-la-api.json` lo genera
 * `FormasDeLaApiTest` del **tipo de retorno de cada controlador**, y su propia cabecera dice
 * para que existe: «la lee el frontend para comprobar que su proxy de datos publica la forma
 * que el backend publica». Esta prueba es esa lectura.
 *
 * <h2>Como compara</h2>
 *
 * Pide cada operacion **por HTTP**, con el proxy instalado y `fetch` de verdad —no llama al
 * constructor: si el proxy dejara de enrutar, esto se pondria rojo igual—, y compara el JSON
 * que sale contra la forma declarada:
 *
 *   · **Los nombres de campo, en los dos sentidos.** Un campo que sobra es tan grave como uno
 *     que falta: una pantalla puede acabar leyendo el que sobra.
 *   · **El anidamiento.** Un objeto donde el backend declara un arreglo no cuela.
 *   · **El tipo de la hoja.** `entero` tiene que llegar como numero entero, `booleano` como
 *     booleano, `fecha` como `AAAA-MM-DD` y `texto` como texto. Un importe declarado `texto`
 *     que llegara como numero se pondria rojo aqui, que es la regla 1 vigilada desde el otro
 *     extremo del cable.
 *
 * <h2>Los huecos, declarados uno a uno</h2>
 *
 * Donde el artboard no tiene dato, el proxy sirve `null` o un arreglo vacio — y entonces esa
 * rama no se compara con nada. Un hueco silencioso convertiria esta prueba en un colador: se
 * podria servir `{ }` de nulos y saldria verde. Por eso los huecos se declaran en
 * `HUECOS_ACEPTADOS` y la prueba exige que sean **exactamente** esos: uno nuevo sale rojo, y
 * uno que alguien rellene tambien —porque hay que borrarlo de la lista, que es como se ve que
 * el hueco se cerro—.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FORMAS = join(AQUI, '../../../docs/50-api/formas-de-la-api.json');

type Formas = Record<string, unknown>;

const declaradas = JSON.parse(readFileSync(FORMAS, 'utf8')) as Formas;

/** La clave de metadatos del archivo generado, que no es una operacion. */
const METADATOS = '_';

/** `AAAA-MM-DD`, que es como el backend publica una `fecha`. */
const FECHA = /^\d{4}-\d{2}-\d{2}$/;

/** `AAAA-MM-DDTHH:MM…`, que es como publica un `instante`. */
const INSTANTE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/;

/** Si un valor servido puede ser lo que la hoja declara. */
function laHojaAdmite(hoja: string, servido: unknown): boolean {
  switch (hoja) {
    case 'entero':
      return typeof servido === 'number' && Number.isInteger(servido);
    case 'booleano':
      return typeof servido === 'boolean';
    case 'fecha':
      return typeof servido === 'string' && FECHA.test(servido);
    case 'instante':
      return typeof servido === 'string' && INSTANTE.test(servido);
    case 'texto':
      return typeof servido === 'string';
    case 'objeto':
      return true;
    default:
      return false;
  }
}

interface Comparacion {
  /** Lo que no cuadra, dicho por su camino dentro del JSON. */
  readonly desajustes: string[];
  /** Caminos donde el proxy no sirve nada: nulo siempre, o arreglo siempre vacio. */
  readonly huecos: Set<string>;
  /** Caminos donde SI sirvio algo. Sirve para descartar un hueco que solo se da a veces. */
  readonly servidos: Set<string>;
}

function comparar(
  declarada: unknown,
  servido: unknown,
  camino: string,
  salida: Comparacion,
): void {
  if (Array.isArray(declarada)) {
    if (!Array.isArray(servido)) {
      salida.desajustes.push(`${camino}: el backend declara un arreglo y el proxy sirve otra cosa`);
      return;
    }
    if (servido.length === 0) {
      salida.huecos.add(camino);
      return;
    }
    salida.servidos.add(camino);
    const forma = declarada[0];
    servido.forEach((elemento, i) => comparar(forma, elemento, `${camino}[${i}]`, salida));
    return;
  }

  if (declarada !== null && typeof declarada === 'object') {
    if (servido === null) {
      salida.huecos.add(camino);
      return;
    }
    if (typeof servido !== 'object' || Array.isArray(servido)) {
      salida.desajustes.push(`${camino}: el backend declara un objeto y el proxy sirve otra cosa`);
      return;
    }
    salida.servidos.add(camino);
    const esperados = Object.keys(declarada as Record<string, unknown>);
    const recibidos = Object.keys(servido as Record<string, unknown>);
    for (const campo of esperados.filter((c) => !recibidos.includes(c))) {
      salida.desajustes.push(`${camino}.${campo}: el backend lo publica y el proxy no lo sirve`);
    }
    for (const campo of recibidos.filter((c) => !esperados.includes(c))) {
      salida.desajustes.push(`${camino}.${campo}: el proxy lo sirve y el backend no lo publica`);
    }
    for (const campo of esperados.filter((c) => recibidos.includes(c))) {
      comparar(
        (declarada as Record<string, unknown>)[campo],
        (servido as Record<string, unknown>)[campo],
        `${camino}.${campo}`,
        salida,
      );
    }
    return;
  }

  // Una hoja.
  if (servido === null) {
    salida.huecos.add(camino);
    return;
  }
  salida.servidos.add(camino);
  if (!laHojaAdmite(String(declarada), servido)) {
    salida.desajustes.push(
      `${camino}: el backend lo declara «${String(declarada)}» y el proxy sirve ` +
        `${JSON.stringify(servido)}`,
    );
  }
}

/** Una ruta con sus `{parametros}` rellenos, para poder pedirla de verdad. */
function urlDe(operacion: Operacion): string {
  return RAIZ + operacion.ruta.replace(/\{\w+\}/g, '1');
}

async function servido(operacion: Operacion): Promise<unknown> {
  const respuesta = await fetch(urlDe(operacion), { method: operacion.metodo });
  expect(
    respuesta.ok,
    `El proxy no atendio «${claveDe(operacion)}»: contesto ${respuesta.status}.`,
  ).toBe(true);
  return respuesta.json();
}

/**
 * Los caminos donde el artboard no tiene dato y el proxy no se lo inventa.
 *
 * Cada uno esta razonado en `operaciones.ts`, junto al campo. Los dos que mas dicen:
 *
 *   · `predios[].baseImponible` — es el valuo afecto YA ponderado por la cuota de propiedad, y
 *     RNF-083 prohibe recomponerlo en la pantalla. El artboard publica la suma (151,406.75) y
 *     no el reparto; multiplicar aqui seria ejecutar en el frontend una regla del backend.
 *   · `responsables` — vacio no es un hueco de la captura: es lo que el expediente dice.
 */
const HUECOS_ACEPTADOS: readonly string[] = [
  'GET /rentas/contribuyentes.contenido[3].condicionEspecial',
  'GET /rentas/contribuyentes.contenido[4].condicionEspecial',
  'GET /rentas/contribuyentes/{id}/ficha.datosPersonales.conyugeId',
  'GET /rentas/contribuyentes/{id}/ficha.domicilioFiscal.vigenciaDesde',
  'GET /rentas/contribuyentes/{id}/ficha.domicilioFiscal.vigenciaHasta',
  'GET /rentas/contribuyentes/{id}/ficha.domicilioFiscal.documentoOrigen',
  'GET /rentas/contribuyentes/{id}/ficha.domicilioProcesal',
  'GET /rentas/contribuyentes/{id}/ficha.historialDeDomicilios[0].vigenciaDesde',
  'GET /rentas/contribuyentes/{id}/ficha.historialDeDomicilios[0].vigenciaHasta',
  'GET /rentas/contribuyentes/{id}/ficha.historialDeDomicilios[0].documentoOrigen',
  'GET /rentas/contribuyentes/{id}/ficha.contactos[0].nombre',
  'GET /rentas/contribuyentes/{id}/ficha.contactos[0].documento',
  'GET /rentas/contribuyentes/{id}/ficha.contactos[0].observacion',
  'GET /rentas/contribuyentes/{id}/ficha.contactos[1].nombre',
  'GET /rentas/contribuyentes/{id}/ficha.contactos[1].documento',
  'GET /rentas/contribuyentes/{id}/ficha.contactos[1].observacion',
  'GET /rentas/contribuyentes/{id}/ficha.responsables',
  'GET /rentas/beneficios.contenido[0].vehiculoId',
  'GET /rentas/beneficios.contenido[0].porcentaje',
  'GET /rentas/beneficios.contenido[0].vigenciaHasta',
  'GET /rentas/beneficios.contenido[1].predioId',
  'GET /rentas/beneficios.contenido[1].vehiculoId',
  'GET /rentas/beneficios.contenido[1].monto',
  'GET /consultas/deuda.contenido[0].predioId',
  'GET /consultas/deuda.contenido[0].vehiculoId',
  'GET /consultas/deuda.contenido[1].predioId',
  'GET /consultas/deuda.contenido[1].vehiculoId',
  'GET /consultas/deuda.contenido[2].predioId',
  'GET /consultas/deuda.contenido[2].vehiculoId',
  'GET /consultas/deuda.contenido[3].predioId',
  'GET /consultas/deuda.contenido[3].vehiculoId',
  'GET /rentas/predial/corridas/ultima.sector',
  'POST /rentas/predial/calculo-individual.predios[0].baseImponible',
  'POST /rentas/predial/calculo-individual.predios[0].porcentajeRegistradoDelPredio',
  'POST /rentas/predial/calculo-individual.predios[0].titularidadCompleta',
  'POST /rentas/predial/calculo-individual.predios[1].baseImponible',
  'POST /rentas/predial/calculo-individual.predios[1].porcentajeRegistradoDelPredio',
  'POST /rentas/predial/calculo-individual.predios[1].titularidadCompleta',
  'POST /rentas/predial/calculo-individual.tramos[2].limiteSuperior',
  'POST /rentas/alcabala.predioId',
  'POST /rentas/alcabala.contribuyenteId',
  'POST /rentas/espectaculos.organizadorId',

  // ── El panel (F-5) ──────────────────────────────────────────────────────────────────────
  // Todos son de la MISMA familia, y por eso van juntos: el panel del artboard escribe cifras
  // redondeadas para un ojo —«S/ 9.42 M», «41.2 %», «534 pendientes»— y el contrato publica al
  // lado el importe exacto con su fecha. De 9.42 millones no sale un centimo sin inventarlo.
  'GET /indicadores/recaudacion.cargado',
  'GET /indicadores/recaudacion.kpis[0].importe',
  'GET /indicadores/recaudacion.kpis[1].importe',
  'GET /indicadores/recaudacion.kpis[2].importe',
  'GET /indicadores/recaudacion.kpis[3].importe',
  'GET /indicadores/recaudacion.paneles[0].rows[0].importe',
  'GET /indicadores/recaudacion.paneles[0].rows[0].cargado',
  'GET /indicadores/recaudacion.paneles[0].rows[0].pendiente',
  'GET /indicadores/recaudacion.paneles[0].rows[1].importe',
  'GET /indicadores/recaudacion.paneles[0].rows[1].cargado',
  'GET /indicadores/recaudacion.paneles[0].rows[1].pendiente',
  'GET /indicadores/recaudacion.paneles[0].rows[2].importe',
  'GET /indicadores/recaudacion.paneles[0].rows[2].cargado',
  'GET /indicadores/recaudacion.paneles[0].rows[2].pendiente',
  'GET /indicadores/recaudacion.paneles[0].rows[3].importe',
  'GET /indicadores/recaudacion.paneles[0].rows[3].cargado',
  'GET /indicadores/recaudacion.paneles[0].rows[3].pendiente',
  'GET /indicadores/recaudacion.paneles[0].rows[4].importe',
  'GET /indicadores/recaudacion.paneles[0].rows[4].cargado',
  'GET /indicadores/recaudacion.paneles[0].rows[4].pendiente',
  'GET /indicadores/trabajo-parado.frentes[0].importe',
  'GET /indicadores/trabajo-parado.frentes[1].importe',
  'GET /indicadores/trabajo-parado.frentes[2].importe',

  // ── La bitacora del panel (F-5) ─────────────────────────────────────────────────────────
  // Cuatro lineas de resumen, no el registro completo: el artboard no dice desde que equipo ni
  // desde que IP se hizo cada cosa, ni el antes y el despues de cada cambio.
  'GET /seguridad/auditoria.contenido[0].origenEquipo',
  'GET /seguridad/auditoria.contenido[0].origenIp',
  'GET /seguridad/auditoria.contenido[0].datosAnteriores',
  'GET /seguridad/auditoria.contenido[0].datosNuevos',
  'GET /seguridad/auditoria.contenido[1].origenEquipo',
  'GET /seguridad/auditoria.contenido[1].origenIp',
  'GET /seguridad/auditoria.contenido[1].datosAnteriores',
  'GET /seguridad/auditoria.contenido[1].datosNuevos',
  'GET /seguridad/auditoria.contenido[2].origenEquipo',
  'GET /seguridad/auditoria.contenido[2].origenIp',
  'GET /seguridad/auditoria.contenido[2].datosAnteriores',
  'GET /seguridad/auditoria.contenido[2].datosNuevos',
  'GET /seguridad/auditoria.contenido[3].origenEquipo',
  'GET /seguridad/auditoria.contenido[3].origenIp',
  'GET /seguridad/auditoria.contenido[3].datosAnteriores',
  'GET /seguridad/auditoria.contenido[3].datosNuevos',

  // ── El expediente coactivo del padron (F-5) ─────────────────────────────────────────────
  // El artboard dice «expediente coactivo 2026-0418 con medida cautelar» y nada mas: ni de que
  // tributos es la deuda, ni el numero y la fecha de esa medida, ni beneficio alguno.
  'GET /coactiva/deudas.contenido[0].tributos',
  'GET /coactiva/deudas.contenido[0].ultimaActuacion',
  'GET /coactiva/deudas.contenido[0].beneficios',
];

beforeAll(() => {
  // `yaServidas: []` y no la lista de verdad: **lo que este archivo mide es la forma que sirve
  // el PROXY**, y desde I-4 seis de las dieciocho operaciones que simula estan tambien en
  // `YA_SERVIDAS`. Con la lista real, esas seis saldrian a la red —a un servidor que en las
  // pruebas no existe— y la comparacion de formas dejaria de hacerse justo sobre las que mas
  // se usan. Que la lista real deje pasar lo suyo lo mide `api/proxy.test.ts`.
  instalarProxyDeDatos({ yaServidas: [] });
});

afterAll(() => {
  desinstalarProxyDeDatos();
});

describe('el proxy solo sirve operaciones que el backend publica', () => {
  it.each(OPERACIONES.map((o) => claveDe(o)))('«%s» esta en formas-de-la-api.json', (clave) => {
    expect(
      Object.keys(declaradas),
      `El proxy declara «${clave}», que no es una operacion de este backend.\n` +
        'Las rutas de otros sistemas no son suyas: catastro sirve bajo /catastro/api/v1 y\n' +
        'caja bajo /caja/api/v1, y fingir sus respuestas seria inventar contratos ajenos.',
    ).toContain(clave);
  });

  it('el archivo de formas trae las 181 operaciones del backend, y su cabecera', () => {
    expect(Object.keys(declaradas)).toContain(METADATOS);
    expect(Object.keys(declaradas).filter((k) => k !== METADATOS)).toHaveLength(181);
  });
});

describe('lo que sirve el proxy tiene la forma que el backend publica', () => {
  it.each(OPERACIONES.map((o) => [claveDe(o), o] as const))('%s', async (clave, operacion) => {
    const cuerpo = await servido(operacion);
    const salida: Comparacion = { desajustes: [], huecos: new Set(), servidos: new Set() };

    comparar(declaradas[clave], cuerpo, clave, salida);

    expect(
      salida.desajustes,
      `La forma que sirve el proxy para «${clave}» no es la que publica el backend.\n` +
        'La declara docs/50-api/formas-de-la-api.json, generada del tipo de retorno del\n' +
        'controlador: si el campo cambio ahi, actualiza src/datos/operaciones.ts; si cambio\n' +
        'aqui, es un defecto del proxy.',
    ).toEqual([]);
  });
});

describe('los huecos de la captura estan declarados uno a uno', () => {
  it('no hay ni uno mas ni uno menos que los aceptados', async () => {
    const huecos = new Set<string>();
    const servidos = new Set<string>();

    for (const operacion of OPERACIONES) {
      const salida: Comparacion = { desajustes: [], huecos: new Set(), servidos: new Set() };
      comparar(declaradas[claveDe(operacion)], await servido(operacion), claveDe(operacion), salida);
      salida.huecos.forEach((h) => huecos.add(h));
      salida.servidos.forEach((s) => servidos.add(s));
    }

    // Un camino que a veces trae valor no es un hueco: el limite superior del ultimo tramo es
    // nulo y el de los otros dos no, y eso es la forma correcta, no una falta de datos.
    const sinNada = [...huecos].filter((h) => !servidos.has(h)).sort();

    expect(
      sinNada,
      'La lista de huecos de la captura cambio.\n' +
        'Si aparece uno nuevo, el proxy dejo de servir algo que servia — o se anadio un campo\n' +
        'que el artboard no tiene, y hay que razonarlo en operaciones.ts antes de aceptarlo.\n' +
        'Si desaparece uno, el hueco se cerro: borralo de HUECOS_ACEPTADOS, que es como se ve\n' +
        'que se cerro. Un hueco sin declarar volveria inutil la comparacion de formas: una\n' +
        'respuesta de puros nulos cuadra con cualquier forma.',
    ).toEqual([...HUECOS_ACEPTADOS].sort());
  });
});
