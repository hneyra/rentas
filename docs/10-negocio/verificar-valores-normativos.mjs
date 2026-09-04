/* Verifica los valores normativos transcritos de docs/10-negocio/valores-normativos/.

   E-3 (#200) es transcripcion legal, no programacion: se busca la norma, se copia
   su tabla y se firma. Lo que esta comprobacion protege no es la aritmetica —no hay
   ninguna— sino las cuatro formas en que una transcripcion deja de valer:

     1. que la firme una sola persona. Quien transcribe ya leyo la norma con una
        expectativa; releerse a uno mismo no es verificar;
     2. que la cifra llegue sin norma, sin articulo o sin fecha de publicacion, y
        entonces nadie pueda volver a la fuente;
     3. que un archivo diga cerrar una fila del mapa de NEG-02 §2 que no existe, o
        que ya cerro otro. Se comprueba en las dos direcciones, como el mapa;
     4. que una cifra acabe cargada en la base POR UNA MIGRACION. Cargar es un acto
        del proceso batch de publicacion, con la credencial de rol_carga_parametros
        y el derivado del corpus delante (#188, ADR-0017); un INSERT de valores
        normativos en una migracion pone esto en rojo, y sigue poniendolo despues de
        cerrar D-13: lo que cambio es quien carga, no que una migracion pueda.

   Y un libro mayor: cuantas filas del mapa siguen sin archivo, por parte. Bajar
   el numero de D-02a es el progreso de #200; subirlo sin querer se ve.

   Con el directorio vacio esto pasa en verde, y debe hacerlo: la barrera llega
   antes que la transcripcion. Lo que no puede es pasar en verde con un archivo mal
   firmado, que es justo lo que demuestran las muestras de `_muestras/`.

   Uso: node docs/10-negocio/verificar-valores-normativos.mjs [--directorio ruta]
*/

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const raiz = new URL('../../', import.meta.url);
const MAPA = new URL('docs/10-negocio/marco-normativo.md', raiz);
const MIGRACIONES_POR_OMISION = 'backend/kamayuk-rentas-esquema/src/main/resources/db/migration/';

const CAMPOS = [
  'Norma',
  'Artículo',
  'Publicada',
  'Ejercicios que rige',
  'Filas de NEG-02 §2',
  'Transcribió',
  'Verificó',
  'Estado',
];
const ESTADOS = ['TRANSCRITO', 'VERIFICADO'];
const SIN_VERIFICAR = '—';
const SECCIONES = [
  '1. La tabla tal como está en la norma',
  '2. Cómo entra al sistema',
  '3. Qué no cabe hoy',
];
/* Las tres tablas de dato nacional de H-5, mas la general. Un INSERT aqui es una
   cifra normativa cargada, que es lo que el cuarto criterio de #200 prohibe. */
const TABLAS_DE_VALORES = [
  'parametro_tributario',
  'valor_unitario_edificacion',
  'depreciacion',
  'valor_referencial_vehiculo',
];

const problemas = [];
const señalar = (mensaje) => problemas.push(mensaje);

/** Quita el maquillaje de Markdown que no cambia lo que la celda dice. */
function limpiar(celda) {
  return celda.replace(/[`*]/g, '').replace(/\s+/g, ' ').trim();
}

/** Sin tildes, en minuscula y con los espacios colapsados. */
function plano(texto) {
  return texto
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim();
}

/** Agrupa las tablas de un documento: cada una, su cabecera y sus filas. */
function tablas(markdown) {
  const encontradas = [];
  let actual = null;
  for (const linea of markdown.split('\n')) {
    const texto = linea.trim();
    if (!texto.startsWith('|')) {
      actual = null;
      continue;
    }
    const celdas = texto.slice(1, texto.endsWith('|') ? -1 : undefined).split('|').map(limpiar);
    if (celdas.every((c) => /^:?-{3,}:?$/.test(c))) continue;
    if (actual === null) {
      actual = { cabecera: celdas, filas: [] };
      encontradas.push(actual);
    } else {
      actual.filas.push(celdas);
    }
  }
  return encontradas;
}

// --- El mapa de NEG-02 §2: que filas hay y de que parte es cada una ---------

const esMapa = (t) => t.cabecera.length === 5 && t.cabecera[0] === '#' && t.cabecera[3] === 'Parte';

const parteDeLaFila = new Map();
for (const fila of tablas(readFileSync(MAPA, 'utf8')).filter(esMapa).flatMap((t) => t.filas)) {
  const numero = Number(fila[0]);
  if (!Number.isInteger(numero)) continue;
  const parte = (fila[3].match(/D-02[abc]/) ?? ['‹POR CLASIFICAR›'])[0];
  parteDeLaFila.set(numero, parte);
}
if (parteDeLaFila.size === 0) {
  señalar(
    'No se encontro ninguna fila del mapa en NEG-02 §2; sin mapa no hay nada contra que comprobar.',
  );
}

// --- Los archivos transcritos ----------------------------------------------

const argumentos = process.argv.slice(2);

/** `--nombre ruta`, o la ruta por omision relativa a la raiz del repositorio. */
function rutaDe(bandera, porOmision) {
  const indice = argumentos.indexOf(`--${bandera}`);
  if (indice >= 0) return argumentos[indice + 1];
  return fileURLToPath(new URL(porOmision, raiz));
}

const directorio = rutaDe('directorio', 'docs/10-negocio/valores-normativos/');
const migraciones = rutaDe('migraciones', MIGRACIONES_POR_OMISION);

/** Los que empiezan por `_` no se escanean: la plantilla y las muestras viven ahi. */
function archivosDeNorma(ruta) {
  let entradas;
  try {
    entradas = readdirSync(ruta);
  } catch {
    señalar(`No existe el directorio de valores normativos: ${ruta}`);
    return [];
  }
  return entradas
    .filter((n) => n.endsWith('.md') && !n.startsWith('_') && n !== 'README.md')
    .filter((n) => statSync(join(ruta, n)).isFile())
    .sort();
}

/** La cabecera obligatoria, como mapa campo → valor. */
function cabecera(markdown) {
  const tabla = tablas(markdown).find(
    (t) =>
      t.cabecera.length === 2 &&
      plano(t.cabecera[0]) === 'campo' &&
      plano(t.cabecera[1]) === 'valor',
  );
  if (!tabla) return null;
  return new Map(tabla.filas.filter((f) => f.length === 2).map((f) => [f[0], f[1]]));
}

/** `Nombre, AAAA-MM-DD` → {nombre, fecha}; null si no tiene esa forma. */
function firma(valor) {
  const coma = valor.lastIndexOf(',');
  if (coma < 0) return null;
  const nombre = valor.slice(0, coma).trim();
  const fecha = valor.slice(coma + 1).trim();
  if (nombre === '' || !/^\d{4}-\d{2}-\d{2}$/.test(fecha)) return null;
  return { nombre, fecha };
}

const reclamadaPor = new Map();
const archivos = archivosDeNorma(directorio);

for (const archivo of archivos) {
  const donde = `valores-normativos/${archivo}`;
  const markdown = readFileSync(join(directorio, archivo), 'utf8');
  const campos = cabecera(markdown);

  if (!campos) {
    señalar(`${donde}: no tiene la cabecera «| Campo | Valor |». Se copia de _plantilla.md.`);
    continue;
  }

  for (const campo of CAMPOS) {
    if (!campos.has(campo)) señalar(`${donde}: le falta el campo «${campo}» de la cabecera.`);
  }
  for (const campo of campos.keys()) {
    if (!CAMPOS.includes(campo)) señalar(`${donde}: campo «${campo}» que la cabecera no admite.`);
  }

  // 2. Ninguna cifra sin norma, articulo y fecha de publicacion al lado.
  for (const campo of ['Norma', 'Artículo', 'Ejercicios que rige']) {
    const valor = campos.get(campo) ?? '';
    if (valor === '' || valor.includes('‹')) {
      señalar(`${donde}: «${campo}» sin rellenar (${valor === '' ? 'vacío' : valor}).`);
    }
  }
  const publicada = campos.get('Publicada') ?? '';
  if (!/\d{4}-\d{2}-\d{2}/.test(publicada)) {
    señalar(
      `${donde}: «Publicada» no lleva fecha AAAA-MM-DD (${publicada || 'vacío'}). Sin fecha de` +
        ' publicacion no se puede volver a la fuente.',
    );
  }

  // 1. Dos personas, y distintas.
  const estado = campos.get('Estado') ?? '';
  if (!ESTADOS.includes(estado)) {
    señalar(`${donde}: «Estado» es «${estado || 'vacío'}» y solo admite ${ESTADOS.join(' o ')}.`);
  }
  const transcribio = firma(campos.get('Transcribió') ?? '');
  if (!transcribio) {
    señalar(`${donde}: «Transcribió» tiene que ser «Nombre, AAAA-MM-DD».`);
  }
  const crudoVerifico = (campos.get('Verificó') ?? '').trim();
  const verifico = crudoVerifico === SIN_VERIFICAR ? null : firma(crudoVerifico);
  if (crudoVerifico !== SIN_VERIFICAR && !verifico) {
    señalar(
      `${donde}: «Verificó» tiene que ser «Nombre, AAAA-MM-DD», o «${SIN_VERIFICAR}» mientras` +
        ' no se haya verificado.',
    );
  }
  if (estado === 'VERIFICADO' && !verifico) {
    señalar(`${donde}: está VERIFICADO y «Verificó» no nombra a nadie.`);
  }
  if (estado === 'TRANSCRITO' && verifico) {
    señalar(
      `${donde}: lo verificó ${verifico.nombre} y sigue en TRANSCRITO. El estado va con la firma.`,
    );
  }
  if (transcribio && verifico && plano(transcribio.nombre) === plano(verifico.nombre)) {
    señalar(
      `${donde}: «${transcribio.nombre}» transcribió y verificó. Releerse a uno mismo no es` +
        ' verificar (ADR-0007).',
    );
  }

  // 3. Las filas que cierra existen, y no las cerró ya otro archivo.
  const filas = (campos.get('Filas de NEG-02 §2') ?? '')
    .split(',')
    .map((n) => n.trim())
    .filter((n) => n !== '');
  if (filas.length === 0) {
    señalar(`${donde}: no dice qué filas de NEG-02 §2 cierra.`);
  }
  for (const cruda of filas) {
    const numero = Number(cruda);
    if (!Number.isInteger(numero)) {
      señalar(`${donde}: «${cruda}» no es un número de fila del mapa.`);
      continue;
    }
    if (!parteDeLaFila.has(numero)) {
      señalar(`${donde}: dice cerrar la fila ${numero} de NEG-02 §2, y esa fila no existe.`);
      continue;
    }
    const ya = reclamadaPor.get(numero);
    if (ya) {
      señalar(
        `${donde}: la fila ${numero} ya la cierra ${ya}. Dos transcripciones del mismo dato` +
          ' divergen.',
      );
      continue;
    }
    reclamadaPor.set(numero, donde);
  }

  // Las tres secciones fijas.
  const titulos = markdown
    .split('\n')
    .filter((l) => l.startsWith('## '))
    .map((l) => plano(l.slice(3)));
  for (const seccion of SECCIONES) {
    if (!titulos.includes(plano(seccion))) señalar(`${donde}: le falta la sección «${seccion}».`);
  }
  const tabla = tablas(markdown).find((t) => t.cabecera.length >= 2 && t.filas.length > 0);
  if (!tabla) señalar(`${donde}: la sección 1 no trae ninguna tabla: no hay norma transcrita.`);
}

// --- 4. Ninguna fila cargada en la base -------------------------------------

let sqls = [];
try {
  sqls = readdirSync(migraciones).filter((n) => n.endsWith('.sql'));
} catch {
  señalar(
    'No se pudo leer el directorio de migraciones; el cuarto criterio de #200 no se pudo comprobar.',
  );
}
for (const migracion of sqls.sort()) {
  const sql = readFileSync(join(migraciones, migracion), 'utf8');
  for (const tabla of TABLAS_DE_VALORES) {
    const inserta = new RegExp(`insert\\s+into\\s+${tabla}\\b`, 'i');
    if (inserta.test(sql)) {
      señalar(
        `${migracion}: carga valores en «${tabla}». Transcribir es E-3; cargar es el proceso` +
          ' batch de publicación (#188, ADR-0017), con `rol_carga_parametros` y el derivado del' +
          ' corpus delante. Una cifra normativa no entra por una migración.',
      );
    }
  }
}

// --- El libro mayor ---------------------------------------------------------

const sinArchivo = new Map();
for (const [numero, parte] of parteDeLaFila) {
  if (reclamadaPor.has(numero)) continue;
  sinArchivo.set(parte, (sinArchivo.get(parte) ?? 0) + 1);
}

if (problemas.length > 0) {
  console.error('Valores normativos: la transcripción no está en regla.\n');
  for (const problema of problemas) console.error(`  - ${problema}`);
  console.error(`\n${problemas.length} problema(s).`);
  process.exit(1);
}

const pendientes = [...sinArchivo.entries()].sort(([a], [b]) => a.localeCompare(b));
const total = [...sinArchivo.values()].reduce((a, b) => a + b, 0);
console.log(
  `Valores normativos: ${archivos.length} archivo(s), ${reclamadaPor.size} fila(s) del mapa` +
    ` cerradas, ${total} sin archivo.`,
);
for (const [parte, cuantas] of pendientes) {
  const nota = parte === 'D-02a' ? ' — bajar este numero es D-02a cerrandose (#200)' : '';
  console.log(`  ${parte}: ${cuantas} sin archivo${nota}`);
}
