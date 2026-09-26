// @vitest-environment node
//
// Lee `lecturas.ts`, los conectores y el contrato del disco, y parsea el primero con el compilador
// de TypeScript. No hay DOM que necesitar.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import { fuentesDeLosConectores } from './los-conectores-del-arbol.ts';

/**
 * **Una lectura que trae un importe y ninguna fecha dice que se hace con el** (#261).
 *
 * <h2>El hueco que lo trae</h2>
 *
 * La regla 9 —«no existe la deuda: es `deudaActualizadaA(fecha)`, y toda cifra mostrada indica su
 * fecha»— estaba **escrita** en el javadoc de `DeterminacionDeAlcabala` y **no la verificaba
 * nadie** (#255, #261): la prohibicion delegaba su razonamiento en `secciones/determinacion.ts`,
 * un archivo de la V6 que salio del arbol en #90. Y una prohibicion escrita, en verde y sin
 * guarda es el peor estado posible: quien venga a conectar la hoja se la encuentra y la cree
 * cubierta.
 *
 * <h2>Y la mitad que caducaba ya caduco: #276</h2>
 *
 * La lista nacio con **tres** entradas y hoy tiene **una**. Las dos que se fueron —la alcabala y
 * los espectaculos— no se retiraron porque alguien se acordara: el tercer centinela de abajo cruza
 * cada entrada contra el contrato y se puso **rojo** en cuanto `POST /rentas/alcabala` publico su
 * `fechaCalculo`, diciendo que la entrada sobraba. Esa es la salida (b) de #261 —que el backend
 * publique la fecha— y es la buena; la tomo #276 para las dos operaciones a la vez. Lo que la
 * caducidad demuestra es que esta guarda no se queda vieja en verde, que era el modo de fallo de
 * la prohibicion que vino a sustituir.
 *
 * <h2>Lo que esto SI verifica, y lo que no</h2>
 *
 * Verifica que **ninguna lectura con un importe y sin una fecha se quede sin veredicto escrito**,
 * que el veredicto cuadre con lo que los conectores hacen, y que la entrada **caduque sola** el
 * dia que el contrato publique la fecha. **No** verifica que la cifra que llega a una celda lleve
 * su fecha al lado —eso se ve donde se dibuja— ni que el veredicto sea el correcto: eso lo lee la
 * revision, y por eso cada entrada lleva su motivo.
 *
 * <h2>Por que «es un importe» se decide por el NOMBRE, y esta medido</h2>
 *
 * Porque **el contrato no publica ningun tipo de dinero**: `docs/50-api/formas-de-la-api.json`
 * reduce cada hoja a `texto`, `entero`, `fecha`, `booleano`, `instante`, `objeto` o `archivo`, y
 * los `MEDIDO: 3 300 campos tipados de formas-de-la-api.json` no traen ni uno «importe» —un
 * `BigDecimal` sale como `texto`, igual que un nombre—. Del lado de TypeScript pasa lo mismo: `baseImponible` es `string`, y tambien lo es
 * `sujeto`. Asi que el nombre es la unica senal que hay, y se declara como lo que es: una
 * heuristica.
 *
 * <h2>Lo que la heuristica caza de mas, y por que NO lleva lista de excepciones</h2>
 *
 * Medido en #309, sobre los 640 campos que `lecturas.ts` tenia entonces, el patron atrapa **dos**
 * que no son cifras: `baseLegal` —la norma que
 * ampara un beneficio o una resolucion— y `arancelFuente` —de donde salio el arancel de una
 * costa—. Se escribio la lista de excepciones y luego **se midio si cambiaba la respuesta: no la
 * cambia**, ni una entrada, con lista y sin ella son los mismos 9 candidatos, porque las tres
 * lecturas que las llevan —`BeneficioServido`, `ResolucionDeDeterminacion`, `CostaDelActo`— traen
 * su fecha de todas formas. Una excepcion que no puede cambiar ningun veredicto es una linea que
 * se queda vieja sin dar rojo, asi que se retiro y la medida se escribe aqui. Si algun dia una
 * lectura entra en el conjunto **solo** por uno de esos dos nombres, la lista se escribe entonces
 * — con el caso delante, que es cuando se sabe que protege.
 *
 * Por el otro lado el patron se deja `limiteSuperior`, `determinado`, `declarado` y `diferencia`,
 * que si son cifras. Medido: ninguna cambia un veredicto, porque las dos lecturas que las llevan
 * —`TramoAplicado` y `LineaDeterminada`— ya entran por `aporte` y por `multa`.
 *
 * <h2>Y «es una fecha» tampoco puede salir del tipo del contrato</h2>
 *
 * Medido en #309, sobre las 168 operaciones que el contrato tenia entonces: de las **214**
 * apariciones de un campo cuyo nombre dice fecha, **78 estan tipadas `texto`** y no `fecha`
 * —`fechaCalculo` sale `texto` siete veces y `fecha` dos—, y hay **21** nombres tipados `fecha`/`instante` que no dicen «fecha» (`actualizadoA`,
 * `deudaAlDia`, `exigibleDesde`, `vencimiento`…). Un solo criterio se equivoca en las dos
 * direcciones; por eso el cruce con el contrato de abajo acepta **las dos senales** —el nombre o
 * el tipo—, que es lo prudente cuando lo que se busca es «aparecio una fecha».
 *
 * <h2>Por que solo las lecturas SUELTAS llevan entrada, y el anidado no se exime a ojo</h2>
 *
 * Porque la fecha de una fila vive en su envoltorio: `TramoAplicado` no lleva ninguna y no le hace
 * falta, porque `DeterminacionIndividual` publica `fechaCalculo` para toda la memoria del calculo.
 * Eximir «lo anidado» sin mas seria firmar en blanco todos esos tipos, asi que no se exime: el
 * segundo centinela exige que **cada** envoltorio de un anidado sin fecha o bien traiga la suya o
 * bien este el mismo en la lista. Medido en #309: 8 anidados, 10 pares hijo-envoltorio, **cero
 * huerfanos** — y los dos que se apoyan en la lista son los hijos de `DeterminacionGuardada`, que
 * es justamente la entrada que dice por que se dibuja sin fecha. Con una sola entrada en la lista,
 * ese apoyo es todo lo que la sostiene: si `DeterminacionGuardada` se retirara, sus dos hijos
 * saldrian huerfanos aqui.
 *
 * <h2>El tamano de la lista, medido antes de escribirla</h2>
 *
 * Medido en #309, sobre las **80** interfaces y **640** campos que `lecturas.ts` tenia entonces:
 * **9** traen un importe y ninguna fecha, de las cuales **1 es suelta** —la unica entrada de
 * abajo— y **8 anidadas**. Nacieron 11 y 3: las dos que faltan ganaron su fecha en #276. Si el criterio hubiera dado cuarenta, la lista
 * seria ruido y esta guarda no se habria escrito.
 *
 * <h2>Las cifras de este comentario llevan su issue, o su marca (#309)</h2>
 *
 * Este javadoc decia «167 operaciones» y «3 250 campos tipados» en presente, y el contrato ya traia
 * 168 operaciones y mas de 3 250 campos: las regeneraciones los movieron y nada se puso rojo. Desde #309 una cifra de aqui
 * es de dos clases. La que dice el tamano de HOY lleva su marca `MEDIDO:`, y la recalcula
 * `LasCifrasMedidasCuadranConElDiscoTest` en el backend, que corre en todo PR —tambien en el que
 * regenera el contrato, que no toca `frontend/`—. La que salio de medir la heuristica de esta guarda
 * no se puede recalcular sin la heuristica, asi que dice **de que issue es**: es la medida con que
 * se decidio, y quien venga a ensanchar o retirar la guarda sabe que tiene que volver a medir.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');
const FORMAS = join(FRONTEND, '../docs/50-api/formas-de-la-api.json');
const LECTURAS = join(FRONTEND, 'src/datos/lecturas.ts');

/**
 * **Que se hace con una lectura que trae un importe y ninguna fecha.**
 *
 * Una entrada por lectura suelta, con la operacion que la publica y el motivo escrito. `veredicto`
 * no admite un tercer valor a proposito: o no se dibuja, o se dibuja y hay que decir por que la
 * regla 9 no la alcanza.
 */
interface Veredicto {
  readonly veredicto: 'NO SE DIBUJA' | 'SE DIBUJA';
  readonly operacion: string;
  readonly motivo: string;
}

const UN_IMPORTE_SIN_SU_FECHA: Readonly<Record<string, Veredicto>> = {
  DeterminacionGuardada: {
    veredicto: 'SE DIBUJA',
    operacion: 'GET /rentas/predial/determinaciones',
    motivo:
      'Se dibuja en `ter-determinacion`, y la regla 9 no la alcanza: sus nueve cifras no son ' +
      '`deudaActualizadaA(fecha)` —aqui no corren intereses—, sino el reparto de lo que quedo ' +
      'asentado para un EJERCICIO bajo un CONJUNTO SELLADO, y los dos estan en la pantalla ' +
      '(`exigeEjercicio` y la celda del conjunto). Cada cuota dice ademas SU vencimiento. El ' +
      'razonamiento entero esta en el javadoc de `TERRITORIO`, en `datos/conectores.ts`.',
  },
};

/** Un campo que nombra una fecha, o un importe que trae la suya (`ImporteConFecha`). */
function esFecha(campo: { readonly nombre: string; readonly tipo: string }): boolean {
  const porNombre =
    /^fec|Fecha/.test(campo.nombre) ||
    /^(actualizadoA|calculadoA|calculadoEn|aLaFecha|deudaAlDia|vencimiento|prescribeEl|exigibleDesde|presentadoHasta|emitidaEl|alCierre|desde|hasta|inicio|fin|vigenciaDesde|vigenciaHasta)$/.test(
      campo.nombre,
    );
  return porNombre || /\bImporteConFecha\b/.test(campo.tipo);
}

/** Un campo cuyo nombre dice dinero y cuyo tipo es texto. Ver el javadoc: es una heuristica. */
function esImporte(campo: { readonly nombre: string; readonly tipo: string }): boolean {
  if (esFecha(campo)) return false;
  return (
    /^(monto|importe|total|saldo|deuda|insoluto|reajuste|interes|gastos?|costas?|multa|ahorro|aporte|valuo|autovaluo|ingreso|porcion|pendiente|cargado|derecho|minimo|uit|impuesto|base|arancel|tasa)/i.test(
      campo.nombre,
    ) && /string/.test(campo.tipo)
  );
}

interface Lectura {
  readonly tipo: string;
  readonly campos: readonly { readonly nombre: string; readonly tipo: string }[];
}

/** Las `export interface` de `lecturas.ts`, con el nombre y el texto del tipo de cada campo. */
function lecturas(): readonly Lectura[] {
  const fuente = readFileSync(LECTURAS, 'utf8');
  const arbol = ts.createSourceFile('lecturas.ts', fuente, ts.ScriptTarget.Latest, true);
  return arbol.statements.filter(ts.isInterfaceDeclaration).map((sentencia) => ({
    tipo: sentencia.name.text,
    campos: sentencia.members.filter(ts.isPropertySignature).map((uno) => ({
      nombre: uno.name.getText(arbol),
      tipo: uno.type === undefined ? '' : uno.type.getText(arbol),
    })),
  }));
}

/** Para cada lectura, las lecturas que la llevan como tipo de uno de sus campos. */
function envoltoriosDe(todas: readonly Lectura[]): ReadonlyMap<string, readonly string[]> {
  const salida = new Map<string, string[]>();
  for (const una of todas) {
    for (const campo of una.campos) {
      for (const otra of todas) {
        if (otra.tipo === una.tipo) continue;
        if (!new RegExp(`\\b${otra.tipo}\\b`).test(campo.tipo)) continue;
        const suyos = salida.get(otra.tipo) ?? [];
        if (!suyos.includes(una.tipo)) suyos.push(una.tipo);
        salida.set(otra.tipo, suyos);
      }
    }
  }
  return salida;
}

/** Las lecturas que traen al menos un importe y ninguna fecha. */
function conImporteYSinFecha(todas: readonly Lectura[]): readonly Lectura[] {
  return todas.filter((una) => una.campos.some(esImporte) && !una.campos.some(esFecha));
}

/** La forma de UNA respuesta del contrato: la del objeto, o la de una fila si la operacion pagina. */
function formaDe(clave: string): Readonly<Record<string, unknown>> {
  const contrato = JSON.parse(readFileSync(FORMAS, 'utf8')) as Record<string, unknown>;
  const suya = contrato[clave];
  if (suya === undefined) throw new Error(`El contrato no publica «${clave}»`);
  const forma = suya as Record<string, unknown>;
  const contenido = forma['contenido'];
  if (Array.isArray(contenido)) return contenido[0] as Record<string, unknown>;
  return forma;
}

describe('una lectura con importe y sin fecha dice que se hace con ella (#261)', () => {
  it('EL CENTINELA: toda lectura SUELTA con importe y sin fecha tiene su veredicto escrito', () => {
    // Sin esto la lista se queda vieja sola: una lectura nueva con importes y sin fecha entraria
    // al arbol sin que nadie tuviera que decidir nada, que es como la regla 9 se apago la primera
    // vez. Y al reves: una entrada cuyo tipo ya no cumple el criterio —porque gano su fecha, o
    // porque se retiro— sobra, y sobra en rojo.
    const todas = lecturas();
    const envoltorios = envoltoriosDe(todas);
    const sueltas = conImporteYSinFecha(todas)
      .filter((una) => !envoltorios.has(una.tipo))
      .map((una) => una.tipo)
      .sort();
    expect(
      sueltas,
      'Las lecturas sueltas que traen un importe y ninguna fecha no son las que la lista dice.\n' +
        '  Si sobra una: su tipo ya no cumple el criterio —gano una fecha, o se retiro— y la\n' +
        '  entrada hay que quitarla.\n' +
        '  Si falta una: hay importes nuevos que nadie puede fechar. Escribe su veredicto CON SU\n' +
        '  MOTIVO, o declara la fecha que el backend publique.',
    ).toEqual(Object.keys(UN_IMPORTE_SIN_SU_FECHA).sort());
  });

  it('EL CENTINELA: y ningun ANIDADO sin fecha se queda huerfano', () => {
    // Una fila no lleva fecha porque la lleva su envoltorio. Eso vale mientras el envoltorio la
    // tenga de verdad, o este el mismo en la lista: si un dia una fila con importes cuelga de algo
    // que tampoco la tiene y no esta decidido, esto lo nombra.
    const todas = lecturas();
    const envoltorios = envoltoriosDe(todas);
    const sinFecha = new Set(conImporteYSinFecha(todas).map((una) => una.tipo));
    const huerfanos: string[] = [];
    for (const hija of conImporteYSinFecha(todas)) {
      for (const envoltorio of envoltorios.get(hija.tipo) ?? []) {
        const suyo = todas.find((una) => una.tipo === envoltorio);
        const traeLaSuya = suyo !== undefined && suyo.campos.some(esFecha);
        const estaDecidido = envoltorio in UN_IMPORTE_SIN_SU_FECHA;
        if (!traeLaSuya && !estaDecidido) huerfanos.push(`  ${hija.tipo} < ${envoltorio}`);
      }
      // Y una fila que no cuelga de nada no es una fila: la caza el centinela de arriba.
      if (!envoltorios.has(hija.tipo) && !sinFecha.has(hija.tipo)) huerfanos.push(`  ${hija.tipo}`);
    }
    expect(
      huerfanos,
      'Hay filas con importes cuyo envoltorio tampoco trae fecha y no esta decidido:\n' +
        `${huerfanos.join('\n')}\n\n` +
        '  La fila se apoya en la fecha del envoltorio. Si el envoltorio no la tiene, la cifra se\n' +
        '  dibuja sin fecha y nadie lo dijo por escrito.',
    ).toEqual([]);
  });

  it('LA CADUCIDAD: la operacion de cada entrada sigue sin publicar una fecha', () => {
    // Esta es la mitad que caduca sola SIN que nadie toque `lecturas.ts`, y ya caduco dos veces:
    // el dia que el backend publico `fechaCalculo` para la alcabala y los espectaculos —#276, la
    // salida (b) de #261— esto se puso rojo diciendo que sus entradas sobraban, y por eso no
    // estan. Lo que vigila ahora es la que queda. Se miran las dos senales —el nombre y el tipo—
    // porque cada una por separado se equivoca: ver el javadoc.
    const yaLaPublican: string[] = [];
    for (const [tipo, suyo] of Object.entries(UN_IMPORTE_SIN_SU_FECHA)) {
      const forma = formaDe(suyo.operacion);
      const fechas = Object.entries(forma).filter(
        ([nombre, valor]) =>
          esFecha({ nombre, tipo: '' }) || valor === 'fecha' || valor === 'instante',
      );
      if (fechas.length > 0) {
        yaLaPublican.push(`  ${tipo} (${suyo.operacion}): ${fechas.map(([n]) => n).join(', ')}`);
      }
    }
    expect(
      yaLaPublican,
      'El contrato YA publica una fecha para estas operaciones:\n' +
        `${yaLaPublican.join('\n')}\n\n` +
        '  Es la salida (b) de #261, y es la buena. Declara el campo en su lectura, dibujalo al\n' +
        '  lado de la cifra, y quita la entrada de la lista: ya no hace falta.',
    ).toEqual([]);
  });

  it('LO QUE NO SE DIBUJA no lo pide ningun conector, y lo que se dibuja SI', () => {
    // La otra mitad que muerde: el dia que alguien conecte una hoja cuya lectura dice «NO SE
    // DIBUJA» sin resolver la fecha, el tipo aparece en un conector y esto se pone rojo. Se mira
    // el NOMBRE en toda la fuente y no solo el `pedir<…>`: importarlo ya es el primer paso, y es
    // lo que #261 midio. Hoy solo queda la rama contraria —«SE DIBUJA» y nadie lo pide—, porque
    // las dos entradas «NO SE DIBUJA» se fueron con la fecha que #276 publico.
    const fuentes = fuentesDeLosConectores().map((uno) => readFileSync(uno, 'utf8'));
    const desfases: string[] = [];
    for (const [tipo, suyo] of Object.entries(UN_IMPORTE_SIN_SU_FECHA)) {
      const loPiden = fuentes.some((texto) => new RegExp(`\\b${tipo}\\b`).test(texto));
      if (suyo.veredicto === 'NO SE DIBUJA' && loPiden) {
        desfases.push(`  ${tipo}: la lista dice «NO SE DIBUJA» y un conector ya lo nombra`);
      }
      if (suyo.veredicto === 'SE DIBUJA' && !loPiden) {
        desfases.push(`  ${tipo}: la lista dice «SE DIBUJA» y ningun conector lo nombra`);
      }
    }
    expect(
      desfases,
      'El veredicto escrito y lo que los conectores hacen no cuadran:\n' +
        `${desfases.join('\n')}\n\n` +
        '  Si se conecto la hoja: la cifra necesita su fecha (regla 9, RNF-075). O el backend la\n' +
        '  publica —salida (b) de #261— o la pantalla dice por que no le hace falta, y el motivo\n' +
        '  se escribe aqui cambiando el veredicto.\n' +
        '  Si se desconecto: el veredicto «SE DIBUJA» sobra.',
    ).toEqual([]);
  });

  it('EL CENTINELA: cada entrada resuelve a una lectura y a una operacion del contrato', () => {
    const tipos = new Set(lecturas().map((una) => una.tipo));
    for (const [tipo, suyo] of Object.entries(UN_IMPORTE_SIN_SU_FECHA)) {
      expect(tipos.has(tipo), `«${tipo}» no es una interfaz de lecturas.ts`).toBe(true);
      expect(Object.keys(formaDe(suyo.operacion)).length, `«${suyo.operacion}» vino vacia`)
        .toBeGreaterThan(0);
      expect(suyo.motivo.length, `«${tipo}» no dice su motivo`).toBeGreaterThan(80);
    }
  });
});
