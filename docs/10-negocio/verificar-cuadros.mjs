/* Verifica el manifiesto de cuadros normativos nacionales (D-13, ADR-0017; #188).

   Es el hermano de `verificar-publicacion.mjs` para lo que no cabe en una fila.
   Aquel comprueba, celda a celda, que cada cifra de `parametros-2026.csv` este
   letra por letra en su archivo del corpus. Un cuadro tiene miles de cifras y no
   se transcribe a mano: se extrae mecanicamente de la fuente y se firma su huella.
   Por eso lo que se comprueba aqui es **la cadena que sustituye a esa lectura**:

     1. el archivo del corpus existe y su `Estado` es VERIFICADO. `TRANSCRITO` no
        basta, igual que en el hermano;
     2. `transcribio` y `verifico` son los de su cabecera, y son distintos
        (RNF-092, adelantado al archivo);
     3. el `documento_fuente` del manifiesto aparece verbatim en el archivo del
        corpus. La edicion no puede decir que la aprueba una norma que su archivo
        no nombra;
     4. el archivo de filas existe, y su **sha256 recalculado** coincide con el que
        el manifiesto declara. Es la misma comprobacion que hace `PublicarCuadros`
        antes de publicar una sola fila, adelantada al PR;
     5. ese mismo sha256 esta escrito en el corpus —en el archivo de la fila o en
        el README de su directorio de fuentes—, que es lo que lo convierte en una
        huella firmada y no en un numero que el manifiesto se puso a si mismo;
     6. la fila esta bien formada: once columnas, fechas, y un `cuadro` que el
        proceso sepa publicar.

   Lo que esto NO comprueba, y no puede: que el derivado mecanico reproduzca el
   PDF. Eso lo sostienen `extraer_tvr.py` con sus dos metodos independientes por
   fila y la re-verificacion humana del archivo del corpus. Lo que esto garantiza
   es que lo que se carga es exactamente el archivo que esas dos cosas firmaron.

   Uso: node docs/10-negocio/verificar-cuadros.mjs [--csv ruta] [--corpus ruta]
*/

import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { join, dirname, resolve } from 'node:path';

const raiz = new URL('../../', import.meta.url);
const CSV_POR_OMISION = fileURLToPath(
  new URL('docs/10-negocio/valores-normativos/publicacion/cuadros-2026.csv', raiz),
);
const CORPUS_POR_OMISION = fileURLToPath(new URL('docs/10-negocio/valores-normativos/', raiz));

/* El orden importa y no es cosmetico: los consumidores del manifiesto lo leen por
   POSICION. Las tres primeras son las de la llave —`tipo,clave,vigencia_desde`—, que es
   lo que `ImportarParametrosDelConjunto` lee cuando el MISMO archivo se usa para componer
   la edicion en el conjunto, y por eso `cuadro` va al final (como `valor_maquina` en
   `parametros-2026.csv`). Con `cuadro` delante, componer leia «2026» como fecha y sellaba
   el conjunto sin la edicion dentro. */
const COLUMNAS = [
  'tipo',
  'clave',
  'vigencia_desde',
  'vigencia_hasta',
  'documento_fuente',
  'archivo_de_filas',
  'sha256',
  'archivo_del_corpus',
  'transcribio',
  'verifico',
  'cuadro',
];

/* Los cuadros que `PublicarCuadros` sabe publicar, y tienen que ser los mismos que
   `FilaDelManifiesto.CUADROS` del backend. `VALOR_UNITARIO` no esta porque su tabla
   todavia no puede recibirlo sin perder una dimension: la R.M. anual del MVCS publica
   un cuadro por region y el corpus solo trae Costa, ademas de faltarle la segunda
   firma (GOB-03, H-14). `DEPRECIACION` estuvo fuera por lo mismo hasta V57 —cuatro
   tablas, una por uso de la edificacion, y ninguna columna de uso donde ponerlas
   (H-15)— y entra desde ahi. Un manifiesto que nombre lo que falta se rechaza aqui y
   en el proceso, con el mismo motivo. */
const CUADROS_PUBLICABLES = new Set(['VALOR_REFERENCIAL', 'DEPRECIACION']);

const argumentos = process.argv.slice(2);
function opcion(nombre, porOmision) {
  const i = argumentos.indexOf(nombre);
  return i >= 0 && argumentos[i + 1] ? argumentos[i + 1] : porOmision;
}
const rutaCsv = opcion('--csv', CSV_POR_OMISION);
const rutaCorpus = opcion('--corpus', CORPUS_POR_OMISION);

const problemas = [];
function señalar(mensaje) {
  problemas.push(mensaje);
}

/* ---------------------------------------------------------------- CSV ----- */

/* Misma semantica que LectorDeFilasCsv del backend y que verificar-publicacion.mjs:
   si se separaran, el archivo diria una cosa aqui y otra al cargarse. */
function dividir(linea) {
  const campos = [];
  let actual = '';
  let entreComillas = false;
  for (let i = 0; i < linea.length; i++) {
    const c = linea[i];
    if (entreComillas) {
      if (c === '"') {
        if (linea[i + 1] === '"') {
          actual += '"';
          i++;
        } else entreComillas = false;
      } else actual += c;
    } else if (c === '"') entreComillas = true;
    else if (c === ',') {
      campos.push(actual.trim());
      actual = '';
    } else actual += c;
  }
  campos.push(actual.trim());
  return campos;
}

function leerCsv(ruta) {
  const filas = [];
  let encabezado = null;
  const lineas = readFileSync(ruta, 'utf8').split('\n');
  for (let n = 0; n < lineas.length; n++) {
    const linea = lineas[n];
    if (linea.trim() === '' || linea.trimStart().startsWith('#')) continue;
    if (encabezado === null) {
      encabezado = dividir(linea);
      continue;
    }
    filas.push({ linea: n + 1, campos: dividir(linea) });
  }
  return { encabezado: encabezado ?? [], filas };
}

/* ------------------------------------------------------------- corpus ----- */

function limpiar(celda) {
  return celda.replace(/[`*]/g, '').replace(/\s+/g, ' ').trim();
}

function cabecera(markdown) {
  const campos = new Map();
  for (const linea of markdown.split('\n')) {
    const texto = linea.trim();
    if (!texto.startsWith('|')) continue;
    const celdas = texto
      .slice(1, texto.endsWith('|') ? -1 : undefined)
      .split('|')
      .map(limpiar);
    if (celdas.length === 2 && celdas[0] !== '' && !/^:?-{3,}:?$/.test(celdas[0])) {
      if (!campos.has(celdas[0])) campos.set(celdas[0], celdas[1]);
    }
  }
  return campos;
}

/** «JNA, 2026-08-24; adición…» -> «JNA». La firma es el nombre; la fecha no la identifica. */
function firmante(celda) {
  return (celda ?? '').split(',')[0].trim();
}

function plano(markdown) {
  return markdown.replace(/\s+/g, ' ');
}

/* --------------------------------------------------------------- main ----- */

if (!existsSync(rutaCsv)) {
  console.error(`No existe el manifiesto ${rutaCsv}`);
  process.exit(1);
}

const { encabezado, filas } = leerCsv(rutaCsv);
if (encabezado.join(',') !== COLUMNAS.join(',')) {
  señalar(
    `El encabezado del manifiesto no es el esperado.\n  esperado: ${COLUMNAS.join(',')}\n  y es:     ${encabezado.join(',')}`,
  );
}

for (const { linea, campos } of filas) {
  const quien = `línea ${linea}`;
  if (campos.length !== COLUMNAS.length) {
    señalar(`${quien}: trae ${campos.length} columna(s) y hacen falta ${COLUMNAS.length}.`);
    continue;
  }
  const fila = Object.fromEntries(COLUMNAS.map((c, i) => [c, campos[i]]));
  const identidad = `${quien} (${fila.cuadro} ${fila.tipo}/${fila.clave})`;

  // 6. Forma de la fila.
  if (!CUADROS_PUBLICABLES.has(fila.cuadro)) {
    señalar(
      `${identidad}: «${fila.cuadro}» no es un cuadro que el sistema sepa publicar todavía.` +
        ` Los publicables son: ${[...CUADROS_PUBLICABLES].join(', ')}.`,
    );
    continue;
  }
  for (const columna of ['tipo', 'documento_fuente', 'archivo_de_filas', 'archivo_del_corpus']) {
    if (fila[columna] === '') señalar(`${identidad}: la columna ${columna} está vacía.`);
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(fila.vigencia_desde)) {
    señalar(`${identidad}: «${fila.vigencia_desde}» no es una fecha AAAA-MM-DD.`);
  }
  if (fila.vigencia_hasta !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(fila.vigencia_hasta)) {
    señalar(`${identidad}: «${fila.vigencia_hasta}» no es una fecha AAAA-MM-DD.`);
  }
  if (!/^[0-9a-f]{64}$/.test(fila.sha256)) {
    señalar(`${identidad}: «${fila.sha256}» no es un sha256 de 64 dígitos hexadecimales.`);
    continue;
  }

  // 1. El archivo del corpus existe y está VERIFICADO.
  const rutaDelCorpus = join(rutaCorpus, fila.archivo_del_corpus);
  if (!existsSync(rutaDelCorpus)) {
    señalar(`${identidad}: no existe «${fila.archivo_del_corpus}» en el corpus.`);
    continue;
  }
  const markdown = readFileSync(rutaDelCorpus, 'utf8');
  const doc = { cabecera: cabecera(markdown), plano: plano(markdown) };
  if (doc.cabecera.get('Estado') !== 'VERIFICADO') {
    señalar(
      `${identidad}: «${fila.archivo_del_corpus}» está en estado` +
        ` «${doc.cabecera.get('Estado')}» y un cuadro se publica desde VERIFICADO:` +
        ' una transcripción sin re-verificar no se carga (ADR-0007).',
    );
  }

  // 2. Las dos firmas son las del corpus, y son distintas.
  const transcribio = firmante(doc.cabecera.get('Transcribió'));
  const verifico = firmante(doc.cabecera.get('Verificó'));
  if (fila.transcribio !== transcribio) {
    señalar(
      `${identidad}: firma la transcripción como «${fila.transcribio}» y` +
        ` «${fila.archivo_del_corpus}» dice «${transcribio}». La firma que se publica` +
        ' es la del corpus.',
    );
  }
  if (fila.verifico !== verifico) {
    señalar(
      `${identidad}: firma la verificación como «${fila.verifico}» y` +
        ` «${fila.archivo_del_corpus}» dice «${verifico}». La firma que se publica` +
        ' es la del corpus.',
    );
  }
  if (fila.transcribio === '' || fila.verifico === '' || fila.transcribio === fila.verifico) {
    señalar(
      `${identidad}: transcribió y verificó tienen que ser dos firmas distintas y no vacías` +
        ' (ADR-0007, RNF-092).',
    );
  }

  // 3. El documento fuente está en el archivo del corpus.
  if (!doc.plano.includes(fila.documento_fuente)) {
    señalar(
      `${identidad}: «${fila.documento_fuente}» no aparece en` +
        ` «${fila.archivo_del_corpus}». Una edición no puede decir que la aprueba una` +
        ' norma que su archivo no nombra.',
    );
  }

  // 4. El archivo de filas existe y su huella es la declarada.
  const rutaDeFilas = resolve(dirname(rutaCsv), fila.archivo_de_filas);
  if (!existsSync(rutaDeFilas)) {
    señalar(`${identidad}: no existe el archivo de filas «${fila.archivo_de_filas}».`);
    continue;
  }
  const huella = createHash('sha256').update(readFileSync(rutaDeFilas)).digest('hex');
  if (huella !== fila.sha256) {
    señalar(
      `${identidad}: «${fila.archivo_de_filas}» no es el archivo que el corpus firmó.\n` +
        `  declarado: ${fila.sha256}\n  y es:      ${huella}\n` +
        '  Un byte distinto en un cuadro normativo se investiga, no se publica.',
    );
    continue;
  }

  // 5. Esa huella está escrita en el corpus, no solo en el manifiesto.
  const readmeDeFuentes = join(dirname(rutaDeFilas), 'README.md');
  const dondeBuscarLaHuella = [doc.plano];
  if (existsSync(readmeDeFuentes)) {
    dondeBuscarLaHuella.push(plano(readFileSync(readmeDeFuentes, 'utf8')));
  }
  if (!dondeBuscarLaHuella.some((texto) => texto.includes(fila.sha256))) {
    señalar(
      `${identidad}: el sha256 del archivo de filas no está escrito en el corpus` +
        ` —ni en «${fila.archivo_del_corpus}» ni en el README de su directorio de` +
        ' fuentes—. Sin eso, la huella es un número que el manifiesto se puso a sí' +
        ' mismo y no una firma.',
    );
  }
}

if (problemas.length > 0) {
  console.error(`\n${problemas.length} problema(s) en ${rutaCsv}:\n`);
  for (const p of problemas) console.error(`  - ${p}`);
  console.error('');
  process.exit(1);
}

console.log(
  `${filas.length} edición(es) publicable(s), cada una respaldada por un archivo VERIFICADO` +
    ' del corpus con sus dos firmas y por la huella de su archivo de filas.',
);
