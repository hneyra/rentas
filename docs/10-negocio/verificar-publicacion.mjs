/* Verifica el derivado publicable de los valores normativos (#188, #247 §4).

   `docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv` es lo unico
   desde lo que `PublicarParametros` publica en `parametro_tributario`. Todo lo que
   hay en el tiene que estar, letra por letra, en el archivo del corpus que la fila
   nombra; esta comprobacion es lo que hace que esa frase sea verdad y no una
   intencion.

   La decision de diseno que protege es la (a) de #188: **la doble firma de ADR-0007
   ya ocurrio en el corpus**. Quien transcribio y quien verifico estan en la cabecera
   del archivo del corpus, y las columnas `transcribio` y `verifico` del CSV las
   copian para que viajen a `usuario_carga` y `usuario_aprueba`. Publicar una cifra que no este en un
   archivo VERIFICADO, o firmarla con un nombre que la cabecera no dice, seria meter
   un valor normativo por la puerta de atras: sin la lectura de la norma, y sin las
   dos personas que ADR-0007 exige. Por eso esto corre en cada PR.

   Por cada fila del CSV se comprueba:

     1. el archivo del corpus existe y su `Estado` es VERIFICADO. `TRANSCRITO` no
        basta: una transcripcion sin re-verificar no se carga;
     2. `transcribio` y `verifico` son los de la cabecera del archivo, y son
        distintos. Es la misma exigencia que RNF-092 pone en la base, adelantada al
        archivo para que no llegue a intentarse;
     3. el `documento_fuente` esta en el archivo. Si lleva «, articulo …», el
        articulo tiene que ser el de la cabecera;
     4. cada fragmento de `valor_texto` —separados por «; »— aparece verbatim en el
        archivo;
     5. la cifra de `valor_numerico` aparece en el `valor_texto` que la acompania, y
        si no lo hay, en el archivo. La comparacion es numerica y acepta las dos
        convenciones decimales que el corpus usa: «4 600,00» del decreto de la UIT y
        «0.2%» del TUO;
     6. la fila esta bien formada: tipo, fechas y un valor de los dos.

   Y tres mas desde #192, por la columna `valor_maquina`. Existe porque la regla 4 y
   lo que el codigo puede leer no caben en una sola columna: la norma dice «cuatro (4)
   anios» y `Plazo.de` solo acepta «4 ANIOS», sin ninguna lectura tolerante.
   `valor_texto` sigue siendo la transcripcion verbatim —la regla 4 queda intacta— y
   `valor_maquina` es lo que se publica en su lugar cuando la fila la trae:

     7. una fila de tipo `PLAZO` trae `valor_maquina`, y con la forma que `Plazo.de`
        acepta: un entero, un espacio y una unidad de `UnidadDePlazo`. Sin esto se
        publicaria un texto que revienta en el primer uso —en produccion, al contar un
        plazo— en vez de aqui;
     8. el entero de `valor_maquina` es el mismo `valor_numerico` de la fila, que la
        regla 5 ya busco en la norma. Es lo que ata la forma de maquina a la cifra
        verificada, en vez de dejarla como un segundo sitio donde escribir un numero;
     9. la unidad de `valor_maquina` corresponde a las palabras del texto verbatim
        —`ANIOS` con «anios», `DIAS_HABILES` con «dias habiles»—. Veinte dias habiles y
        veinte calendario no son lo mismo, y de esa diferencia depende si un expediente
        coactivo nacio antes de tiempo. Las unidades no se copian aqui: se leen del
        enumerado `UnidadDePlazo`, y una unidad suya sin palabras declaradas pone esto
        rojo antes de que nadie la use.

   Y un libro mayor al final: que archivos del corpus estan VERIFICADO y no publican
   ninguna fila. No es un fallo —hay valores verificados que no se pueden publicar
   tal cual, y el README del directorio dice cuales y por que— pero se ve.

   Uso: node docs/10-negocio/verificar-publicacion.mjs [--csv ruta] [--corpus ruta]
*/

import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, basename } from 'node:path';

const raiz = new URL('../../', import.meta.url);
const CSV_POR_OMISION = fileURLToPath(
  new URL('docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv', raiz),
);
const CORPUS_POR_OMISION = fileURLToPath(new URL('docs/10-negocio/valores-normativos/', raiz));

const COLUMNAS = [
  'tipo',
  'clave',
  'vigencia_desde',
  'vigencia_hasta',
  'valor_numerico',
  'valor_texto',
  'documento_fuente',
  'archivo_del_corpus',
  'transcribio',
  'verifico',
  // La ultima, y al final a proposito: los tres consumidores del archivo
  // —esta comprobacion, `FilaPublicable` del proceso batch y las tres columnas que
  // `ImportarParametrosDelConjunto` lee— lo analizan por POSICION, no por cabecera.
  'valor_maquina',
];

/** El tipo cuyo valor el codigo lee como `Plazo`, y por eso exige forma de maquina. */
const TIPO_PLAZO = 'PLAZO';

/**
 * Con que palabras escribe la norma cada unidad de plazo.
 *
 * Las llaves NO se inventan aqui: se comprueban contra `UnidadDePlazo`, y una unidad
 * del enumerado que no aparezca en este mapa —o al reves— es un fallo. Asi la unidad
 * nueva del dia que el enumerado crezca no puede publicarse sin decir con que palabras
 * la escribe la norma que la respalda.
 */
const PALABRAS_DE_LA_UNIDAD = new Map([
  ['ANIOS', ['año', 'años']],
  ['DIAS_HABILES', ['día hábil', 'días hábiles']],
  ['DIAS_CALENDARIO', ['día calendario', 'días calendario']],
]);

const problemas = [];
const señalar = (mensaje) => problemas.push(mensaje);

function argumento(nombre, omision) {
  const i = process.argv.indexOf(nombre);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : omision;
}

const CSV = argumento('--csv', CSV_POR_OMISION);
const CORPUS = argumento('--corpus', CORPUS_POR_OMISION);

/* ---------------------------------------------------------------- CSV ----- */

/* Divide una linea con la MISMA semantica que LectorDeFilasCsv del backend: campo
   entre comillas dobles, con «""» como comilla literal dentro. Si esto y aquello se
   separaran, el archivo diria una cosa aqui y otra al cargarse. */
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

/** Las filas de datos con su numero de linea real, saltando comentarios y encabezado. */
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

/** Quita el maquillaje de Markdown y colapsa los espacios: lo que la celda dice. */
function limpiar(celda) {
  return celda.replace(/[`*]/g, '').replace(/\s+/g, ' ').trim();
}

/** La cabecera «| Campo | Valor |» del archivo del corpus, como mapa. */
function cabecera(markdown) {
  const campos = new Map();
  for (const linea of markdown.split('\n')) {
    const texto = linea.trim();
    if (!texto.startsWith('|')) continue;
    const celdas = texto.slice(1, texto.endsWith('|') ? -1 : undefined).split('|').map(limpiar);
    if (celdas.length === 2 && celdas[0] !== '' && !/^:?-{3,}:?$/.test(celdas[0])) {
      if (!campos.has(celdas[0])) campos.set(celdas[0], celdas[1]);
    }
  }
  return campos;
}

/** «JNA, 2026-08-24» -> «JNA». La firma es el nombre; la fecha no la identifica. */
function firmante(celda) {
  return (celda ?? '').split(',')[0].trim();
}

/** El archivo entero con los espacios colapsados, para buscar fragmentos verbatim. */
function plano(markdown) {
  return markdown.replace(/\s+/g, ' ');
}

/* ------------------------------------------------------------ numeros ----- */

/** Las dos lecturas posibles de un numero escrito: coma decimal y punto decimal. */
function interpretaciones(token) {
  const t = token.replace(/[\s  ]/g, '');
  const candidatos = new Set();
  if ((t.match(/,/g) ?? []).length <= 1) candidatos.add(t.replace(/\./g, '').replace(',', '.'));
  if ((t.match(/\./g) ?? []).length <= 1) candidatos.add(t.replace(/,/g, ''));
  const numeros = [];
  for (const c of candidatos) {
    const n = Number(c);
    if (c !== '' && Number.isFinite(n)) numeros.push(n);
  }
  return numeros;
}

/** Todas las cifras que aparecen en un texto, en sus dos lecturas posibles. */
function cifrasDe(texto) {
  const encontradas = new Set();
  for (const token of texto.match(/[0-9][0-9\s  .,]*[0-9]|[0-9]/g) ?? []) {
    for (const n of interpretaciones(token)) encontradas.add(n);
  }
  return encontradas;
}

/* --------------------------------------------------- forma de maquina ----- */

const UNIDAD_DE_PLAZO = fileURLToPath(
  new URL(
    'backend/kamayuk-rentas-dominio-compartido/src/main/java/kamayuk/rentas/dominio/UnidadDePlazo.java',
    raiz,
  ),
);

/** Las unidades que el enumerado declara HOY, leidas de el en vez de copiadas aqui. */
function unidadesDePlazo() {
  if (!existsSync(UNIDAD_DE_PLAZO)) {
    señalar(
      `No se pudo leer ${UNIDAD_DE_PLAZO}. Las unidades de plazo no se copian en esta` +
        ' comprobacion: se leen del enumerado, para que no puedan decir una cosa aqui y otra' +
        ' al contarse el plazo.',
    );
    return new Set();
  }
  const fuente = readFileSync(UNIDAD_DE_PLAZO, 'utf8');
  const cuerpo = fuente.slice(fuente.indexOf('enum UnidadDePlazo'));
  return new Set(
    (cuerpo.match(/^\s+[A-Z][A-Z0-9_]*\s*[,;]?\s*$/gm) ?? []).map((linea) =>
      linea.trim().replace(/[,;]$/, ''),
    ),
  );
}

const UNIDADES = unidadesDePlazo();
for (const unidad of UNIDADES) {
  if (!PALABRAS_DE_LA_UNIDAD.has(unidad)) {
    señalar(
      `UnidadDePlazo declara «${unidad}» y esta comprobacion no sabe con que palabras la` +
        ' escribe la norma. Una unidad sin palabras declaradas se publicaria sin que nadie' +
        ' pudiera comprobar que el texto verbatim habla de ella.',
    );
  }
}
for (const unidad of PALABRAS_DE_LA_UNIDAD.keys()) {
  if (UNIDADES.size > 0 && !UNIDADES.has(unidad)) {
    señalar(
      `Esta comprobacion conoce la unidad «${unidad}» y UnidadDePlazo ya no la declara:` +
        ' publicarla dejaria un parametro que el codigo no puede leer.',
    );
  }
}

/* -------------------------------------------------------- comprobacion ---- */

const { encabezado, filas } = leerCsv(CSV);
if (encabezado.join(',') !== COLUMNAS.join(',')) {
  señalar(
    `El encabezado del CSV no es el esperado.\n      esperaba: ${COLUMNAS.join(',')}\n` +
      `      dijo:     ${encabezado.join(',')}`,
  );
}

const documentos = new Map();
/** El archivo del corpus, leido una sola vez. */
function documento(nombre) {
  if (!documentos.has(nombre)) {
    const ruta = join(CORPUS, nombre);
    if (!existsSync(ruta)) documentos.set(nombre, null);
    else {
      const markdown = readFileSync(ruta, 'utf8');
      documentos.set(nombre, { markdown, plano: plano(markdown), cabecera: cabecera(markdown) });
    }
  }
  return documentos.get(nombre);
}

const ES_FECHA = /^\d{4}-\d{2}-\d{2}$/;
const llavesVistas = new Map();
const archivosPublicados = new Set();

for (const { linea, campos } of filas) {
  const donde = `${basename(CSV)}:${linea}`;
  if (campos.length !== COLUMNAS.length) {
    señalar(`${donde}: trae ${campos.length} columna(s) y hacen falta ${COLUMNAS.length}.`);
    continue;
  }
  const fila = Object.fromEntries(COLUMNAS.map((c, i) => [c, campos[i]]));
  const quien = `${donde} (${fila.tipo}${fila.clave ? ':' + fila.clave : ''})`;

  // 6. La fila esta bien formada.
  if (fila.tipo === '') señalar(`${quien}: la fila no dice de que tipo es el parametro.`);
  if (!ES_FECHA.test(fila.vigencia_desde)) {
    señalar(`${quien}: «${fila.vigencia_desde}» no es una fecha aaaa-mm-dd de inicio de vigencia.`);
  }
  if (fila.vigencia_hasta !== '' && !ES_FECHA.test(fila.vigencia_hasta)) {
    señalar(`${quien}: «${fila.vigencia_hasta}» no es una fecha aaaa-mm-dd de fin de vigencia.`);
  }
  if (
    fila.vigencia_hasta !== '' &&
    ES_FECHA.test(fila.vigencia_hasta) &&
    fila.vigencia_hasta < fila.vigencia_desde
  ) {
    señalar(`${quien}: la vigencia termina antes de empezar.`);
  }
  if (fila.valor_numerico === '' && fila.valor_texto === '') {
    señalar(`${quien}: sin valor numerico ni de texto no parametriza nada.`);
  }

  const llave = `${fila.tipo}|${fila.clave}|${fila.vigencia_desde}`;
  if (llavesVistas.has(llave)) {
    señalar(
      `${quien}: repite la llave de ${basename(CSV)}:${llavesVistas.get(llave)}. Dos filas` +
        ' homonimas dejan al conjunto sin poder decir cual se sello.',
    );
  } else llavesVistas.set(llave, linea);

  // 1. El archivo del corpus existe y esta VERIFICADO.
  if (fila.archivo_del_corpus === '') {
    señalar(`${quien}: no nombra ningun archivo del corpus, asi que nada la respalda.`);
    continue;
  }
  archivosPublicados.add(fila.archivo_del_corpus);
  const doc = documento(fila.archivo_del_corpus);
  if (doc === null) {
    señalar(`${quien}: nombra «${fila.archivo_del_corpus}», que no existe en ${CORPUS}.`);
    continue;
  }
  const estado = doc.cabecera.get('Estado');
  if (estado !== 'VERIFICADO') {
    señalar(
      `${quien}: «${fila.archivo_del_corpus}» esta en estado ${estado ?? '(sin estado)'} y solo` +
        ' se publica desde VERIFICADO: una transcripcion sin re-verificar no se carga (ADR-0007).',
    );
    continue;
  }

  // 2. Las dos firmas son las del corpus, y son distintas.
  const transcribio = firmante(doc.cabecera.get('Transcribió'));
  const verifico = firmante(doc.cabecera.get('Verificó'));
  if (fila.transcribio !== transcribio) {
    señalar(
      `${quien}: firma la transcripcion como «${fila.transcribio}» y «${fila.archivo_del_corpus}»` +
        ` dice «${transcribio}». La firma que se publica es la del corpus.`,
    );
  }
  if (fila.verifico !== verifico) {
    señalar(
      `${quien}: firma la verificacion como «${fila.verifico}» y «${fila.archivo_del_corpus}»` +
        ` dice «${verifico}». La firma que se publica es la del corpus.`,
    );
  }
  if (fila.transcribio === fila.verifico || fila.transcribio === '' || fila.verifico === '') {
    señalar(
      `${quien}: transcribió y verificó tienen que ser dos firmas distintas y no vacias` +
        ' (ADR-0007, RNF-092); la base lo rechazaria igual, pero mas tarde.',
    );
  }

  // 3. El documento fuente esta en el archivo, y su articulo es el de la cabecera.
  const corte = fila.documento_fuente.lastIndexOf(', artículo ');
  const norma = corte === -1 ? fila.documento_fuente : fila.documento_fuente.slice(0, corte);
  const articulo = corte === -1 ? null : fila.documento_fuente.slice(corte + ', artículo '.length);
  if (norma === '') {
    señalar(`${quien}: sin documento fuente nadie puede volver a la norma dentro de dos anios.`);
  } else if (!doc.plano.includes(plano(norma))) {
    señalar(
      `${quien}: el documento fuente «${norma}» no aparece en «${fila.archivo_del_corpus}».` +
        ' Lo que se publica como fuente tiene que estar en el corpus, no componerse aparte.',
    );
  }
  if (articulo !== null && articulo !== doc.cabecera.get('Artículo')) {
    señalar(
      `${quien}: cita el articulo «${articulo}» y la cabecera de «${fila.archivo_del_corpus}»` +
        ` dice «${doc.cabecera.get('Artículo') ?? '(vacío)'}».`,
    );
  }

  // 4. Cada fragmento del valor de texto, verbatim en el archivo.
  const fragmentos = fila.valor_texto === '' ? [] : fila.valor_texto.split('; ');
  for (const fragmento of fragmentos) {
    if (!doc.plano.includes(plano(fragmento))) {
      señalar(
        `${quien}: «${fragmento}» no esta en «${fila.archivo_del_corpus}». El valor de texto se` +
          ' copia de la norma; componerlo aqui es reescribirla.',
      );
    }
  }

  // 5. La cifra esta donde tiene que estar.
  if (fila.valor_numerico !== '') {
    const valor = Number(fila.valor_numerico);
    if (!Number.isFinite(valor)) {
      señalar(`${quien}: «${fila.valor_numerico}» no es un numero.`);
    } else {
      const dondeBuscar = fragmentos.length > 0 ? fila.valor_texto : doc.markdown;
      if (!cifrasDe(dondeBuscar).has(valor)) {
        señalar(
          `${quien}: la cifra ${fila.valor_numerico} no aparece en ` +
            (fragmentos.length > 0
              ? `su propio valor de texto «${fila.valor_texto}»`
              : `«${fila.archivo_del_corpus}»`) +
            '. Una cifra que no esta en la norma es una cifra inventada, y multiplica un padron.',
        );
      }
    }
  }

  // 7. La forma que el codigo consume, cuando la letra de la norma no lo es.
  const maquina = fila.valor_maquina;
  const partes = maquina === '' ? [] : maquina.trim().split(/\s+/);
  const unidad = partes.length === 2 ? partes[1].toUpperCase() : null;
  const enForma =
    partes.length === 2 &&
    /^\d+$/.test(partes[0]) &&
    unidad !== null &&
    PALABRAS_DE_LA_UNIDAD.has(unidad);

  if (maquina !== '' && !enForma) {
    señalar(
      `${quien}: «${maquina}» no es la forma que Plazo.de acepta —un entero, un espacio y una` +
        ` unidad de ${[...PALABRAS_DE_LA_UNIDAD.keys()].join(', ')}—. Esa lectura no es` +
        ' tolerante a proposito: un parametro interpretado «lo mejor posible» produce un plazo' +
        ' plausible y equivocado, que es el modo de falla que nadie detecta.',
    );
  } else if (fila.tipo === TIPO_PLAZO && maquina === '') {
    señalar(
      `${quien}: es de tipo ${TIPO_PLAZO} y no trae valor_maquina, asi que se publicaria el` +
        ` texto de la norma («${fila.valor_texto}»), que es lo unico que Plazo.de NO puede leer.` +
        ' Sin esta columna el fallo saldria en el primer uso, contando un plazo en produccion,' +
        ' y no aqui.',
    );
  }

  if (enForma) {
    // 8. La cifra de la forma de maquina es la que la regla 5 busco en la norma.
    if (fila.valor_numerico === '' || Number(fila.valor_numerico) !== Number(partes[0])) {
      señalar(
        `${quien}: valor_maquina «${maquina}» publica ${partes[0]} y la fila declara` +
          ` valor_numerico «${fila.valor_numerico}». La forma de maquina no es un segundo sitio` +
          ' donde escribir un numero: es la misma cifra verificada, escrita como el codigo la lee.',
      );
    }

    // 9. Y su unidad es de la que hablan las palabras del texto verbatim.
    const texto = fila.valor_texto.toLowerCase();
    const suyas = PALABRAS_DE_LA_UNIDAD.get(unidad) ?? [];
    if (!suyas.some((palabra) => texto.includes(palabra))) {
      señalar(
        `${quien}: valor_maquina dice «${unidad}» y el texto verbatim no dice ` +
          suyas.map((palabra) => `«${palabra}»`).join(' ni ') +
          `: «${fila.valor_texto}». Veinte dias habiles y veinte calendario no son lo mismo, y de` +
          ' esa diferencia depende si un expediente coactivo nacio antes de tiempo.',
      );
    }
    for (const [otra, palabras] of PALABRAS_DE_LA_UNIDAD) {
      if (otra !== unidad && palabras.some((palabra) => texto.includes(palabra))) {
        señalar(
          `${quien}: valor_maquina dice «${unidad}» y el texto verbatim habla de «${otra}»` +
            `: «${fila.valor_texto}». Con las dos unidades en juego, lo que se publica ya no se` +
            ' puede comprobar contra la norma.',
        );
      }
    }
  }
}

/* ---------------------------------------------------------- libro mayor --- */

const verificadosSinPublicar = [];
for (const nombre of readdirSync(CORPUS)) {
  if (!nombre.endsWith('.md') || nombre.startsWith('_') || nombre === 'README.md') continue;
  if (archivosPublicados.has(nombre)) continue;
  const doc = documento(nombre);
  if (doc && doc.cabecera.get('Estado') === 'VERIFICADO') verificadosSinPublicar.push(nombre);
}

if (problemas.length > 0) {
  console.error('El derivado publicable no cuadra con el corpus.\n');
  for (const problema of problemas) console.error(`  - ${problema}`);
  process.exit(1);
}

console.log(
  `${filas.length} fila(s) publicables, todas respaldadas por un archivo VERIFICADO del corpus` +
    ` con sus dos firmas (${archivosPublicados.size} archivo(s)).`,
);
if (verificadosSinPublicar.length > 0) {
  console.log(
    `VERIFICADO y sin publicar (${verificadosSinPublicar.length}): ` +
      `${verificadosSinPublicar.join(', ')}. El README del directorio dice por que.`,
  );
}
