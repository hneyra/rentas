/* Deriva mecanicamente el archivo de filas de la tabla de depreciacion desde el
   archivo del corpus (#188, H-15).

   NO transcribe nada. La transcripcion es `depreciacion.md`, que esta VERIFICADO y
   firmado por dos personas distintas (ADR-0007): las cuatro tablas del Anexo I del
   Reglamento Nacional de Tasaciones estan ahi, celda por celda, con el titulo de
   cada una y la nota al pie de los asteriscos. Esto solo las PROYECTA a la forma
   que `PublicarCuadros` sabe leer.

   Por que se deriva en vez de escribir el CSV a mano: porque un CSV escrito aparte
   es un segundo sitio donde una cifra puede estar mal, y el corpus dejaria de ser
   la unica fuente. Aqui las 492 filas salen de las mismas celdas que las dos firmas
   respaldan, y `--comprobar` exige en cada PR que el archivo desplegado sea
   exactamente lo que este guion produce hoy. Es la misma disciplina que
   `generar-openapi.mjs --comprobar` (#312): el derivado no se edita, se regenera.

   Es el hermano de `extraer_tvr.py`, y su diferencia dice de donde viene cada uno:
   el anexo vehicular son 169 paginas de PDF y hubo que extraerlo con dos metodos
   independientes por fila; el Anexo I del RNT cabe en cuatro tablas de doce filas y
   ya esta transcrito en el corpus, asi que aqui la fuente es el corpus.

   LAS CELDAS `*` NO SE PROYECTAN. La norma no las tabula —«el perito fija los
   porcentajes no tabulados»— y una celda que falta no vale cero (#48): la fila
   sencillamente no existe, y quien la busque tendra que fallar nombrandola en vez
   de depreciar al 0 %.

   Uso: node derivar-depreciacion.mjs [--comprobar]
*/

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const CORPUS = fileURLToPath(new URL('../../depreciacion.md', import.meta.url));
const SALIDA = fileURLToPath(new URL('depreciacion.csv', import.meta.url));

/** Cabecera del derivado. La lee `PublicarCuadros` por POSICION, no por nombre. */
const CABECERA = 'tabla,material,estado_conservacion,antiguedad_hasta,porcentaje';

/** `### Tabla 01 — Casa habitacion...`: el numero es el identificador de la norma. */
const TITULO = /^###\s+Tabla\s+(\d{2})\s+—\s+(.+)$/;

/** «Hasta 5» da 5; «Más de 50» no tiene tope y va con la celda vacia. */
function topeDelTramo(encabezado) {
  const hasta = encabezado.match(/^Hasta\s+(\d+)$/);
  if (hasta) return hasta[1];
  if (/^Más de\s+\d+$/.test(encabezado)) return '';
  return null;
}

function celdas(linea) {
  return linea
    .replace(/^\s*\|/, '')
    .replace(/\|\s*$/, '')
    .split('|')
    .map((celda) => celda.trim());
}

function derivar(markdown) {
  const lineas = markdown.split('\n');
  const filas = [];
  const tablas = [];

  for (let i = 0; i < lineas.length; i++) {
    const titulo = lineas[i].match(TITULO);
    if (!titulo) continue;

    const tabla = titulo[1];
    // La primera linea de tabla tras el titulo es su encabezado; la siguiente, el
    // separador de markdown; las demas, sus filas, hasta la primera que no lo sea.
    let j = i + 1;
    while (j < lineas.length && !lineas[j].startsWith('|')) j++;
    if (j >= lineas.length) {
      throw new Error(`La tabla ${tabla} no trae ninguna tabla debajo de su titulo`);
    }

    const encabezado = celdas(lineas[j]);
    const tramos = encabezado.slice(2).map((columna) => {
      const tope = topeDelTramo(columna);
      if (tope === null) {
        throw new Error(
          `«${columna}» no es un tramo de antiguedad de la tabla ${tabla}:` +
            ' se esperaba «Hasta N» o «Más de N»',
        );
      }
      return tope;
    });
    if (tramos.length === 0) {
      throw new Error(`La tabla ${tabla} no declara ningun tramo de antiguedad`);
    }

    let cuantas = 0;
    for (let k = j + 2; k < lineas.length && lineas[k].startsWith('|'); k++) {
      const fila = celdas(lineas[k]);
      const [material, estado] = fila;
      const valores = fila.slice(2);
      if (valores.length !== tramos.length) {
        throw new Error(
          `La fila «${material} / ${estado}» de la tabla ${tabla} trae ${valores.length}` +
            ` celda(s) y la cabecera declara ${tramos.length} tramo(s)`,
        );
      }
      valores.forEach((valor, columna) => {
        // El asterisco no se proyecta: la norma no tabula esa combinacion.
        if (valor === '*') return;
        if (!/^\d+$/.test(valor)) {
          throw new Error(
            `«${valor}» no es un porcentaje de la tabla ${tabla}` +
              ` (${material} / ${estado}); solo se admite un entero o «*»`,
          );
        }
        filas.push([tabla, material, estado, tramos[columna], valor].join(','));
        cuantas++;
      });
    }
    tablas.push({ tabla, uso: titulo[2].trim(), filas: cuantas });
  }

  if (tablas.length === 0) {
    throw new Error('El archivo del corpus no trae ninguna «### Tabla NN — …»');
  }
  return { texto: [CABECERA, ...filas].join('\n') + '\n', tablas, total: filas.length };
}

const { texto, tablas, total } = derivar(readFileSync(CORPUS, 'utf8'));

if (process.argv.includes('--comprobar')) {
  let desplegado;
  try {
    desplegado = readFileSync(SALIDA, 'utf8');
  } catch {
    console.error(`✗ Falta ${SALIDA}: el derivado no esta en el repositorio`);
    process.exit(1);
  }
  if (desplegado !== texto) {
    const esperadas = texto.split('\n');
    const actuales = desplegado.split('\n');
    const linea = esperadas.findIndex((l, i) => l !== actuales[i]);
    console.error(
      '✗ depreciacion.csv no es lo que su generador produce desde depreciacion.md.\n' +
        `  Primera diferencia, linea ${linea + 1}:\n` +
        `    corpus:    ${esperadas[linea] ?? '(no hay mas lineas)'}\n` +
        `    desplegado: ${actuales[linea] ?? '(no hay mas lineas)'}\n` +
        '  El derivado no se edita: se regenera con `node derivar-depreciacion.mjs`.',
    );
    process.exit(1);
  }
  console.log(`✓ depreciacion.csv reproduce el corpus: ${total} filas`);
  process.exit(0);
}

writeFileSync(SALIDA, texto);
for (const { tabla, uso, filas } of tablas) {
  console.log(`  Tabla ${tabla} — ${uso}: ${filas} filas`);
}
console.log(`${total} filas escritas en ${SALIDA}`);
