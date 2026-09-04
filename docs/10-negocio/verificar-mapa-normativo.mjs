/* Verifica el mapa normativo de docs/10-negocio/marco-normativo.md §2.

   El mapa dice, para cada dato normativo que falta, que norma lo fija, que parte
   de D-02 lo bloquea y que issues lo esperan. De ahi sale el etiquetado del
   tablero, asi que las dos cosas tienen que decir lo mismo —y decirlo **en las
   dos direcciones**, como el contrato de la API—:

     1. toda fila tiene norma y parte, todo marcador de duda de la columna
        «Parte» lleva su motivo, y toda fila que dice «ninguno todavia» dice
        **por que** no bloquea a nadie —el dato ya esta firmado, la decision se
        cerro, nadie lo consume aun—: sin motivo, esa celda no se distingue de
        un olvido, y desde que D-02a se cerro la llevan veintidos de las
        treinta y dos;
     2. todo issue que una fila nombra aparece en §2.8 con esa parte;
     3. todo issue de §2.8 esta justificado por las filas que declara;
     4. §2.8 coincide exactamente con las etiquetas reales del tablero, que viven
        en etiquetas-de-bloqueo.json.

   Quitar un issue de una fila dejandolo etiquetado —o etiquetar uno que ninguna
   fila nombra— tiene que poner esto en rojo. Una comprobacion que no puede
   fallar no protege nada.

   Uso: node docs/10-negocio/verificar-mapa-normativo.mjs
*/

import { readFileSync } from 'node:fs';

const raiz = new URL('../../', import.meta.url);
const MAPA = new URL('docs/10-negocio/marco-normativo.md', raiz);
const TABLERO = new URL('docs/10-negocio/etiquetas-de-bloqueo.json', raiz);

const PARTES = ['D-02a', 'D-02b', 'D-02c'];
const SIN_ISSUES = 'ninguno todavia';

const problemas = [];
const señalar = (mensaje) => problemas.push(mensaje);

/** Quita el maquillaje de Markdown que no cambia lo que la celda dice. */
function limpiar(celda) {
  return celda.replace(/[`*]/g, '').replace(/\s+/g, ' ').trim();
}

/** Sin tildes y en minuscula: comparar «ninguno todavía» no debe depender de una tilde. */
function plano(texto) {
  return texto.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
}

/** Agrupa las tablas del documento: cada una, su cabecera y sus filas de datos. */
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

const markdown = readFileSync(MAPA, 'utf8');
const todas = tablas(markdown);

const esMapa = (t) =>
  t.cabecera.length === 5 && t.cabecera[0] === '#' && t.cabecera[3] === 'Parte';
const esInventario = (t) =>
  t.cabecera.length === 3 && t.cabecera[0] === 'Issue' && t.cabecera[1] === 'Partes';

const filasDelMapa = todas.filter(esMapa).flatMap((t) => t.filas);
const inventario = todas.filter(esInventario).flatMap((t) => t.filas);

if (filasDelMapa.length === 0) señalar('No se encontro ninguna tabla del mapa en §2.');
if (inventario.length === 0) señalar('No se encontro la tabla de issues de §2.8.');

// --- 1. Cada fila del mapa esta completa ------------------------------------

/** Numero de fila -> { norma, parte, issues[] } */
const filas = new Map();

for (const [numero, dato, norma, parte, issues] of filasDelMapa) {
  const n = Number(numero);
  if (!Number.isInteger(n)) {
    señalar(`Fila con numero no entero: «${numero}».`);
    continue;
  }
  if (filas.has(n)) {
    señalar(`La fila ${n} esta dos veces en el mapa.`);
    continue;
  }
  if (!dato) señalar(`La fila ${n} no dice que dato falta.`);
  if (!norma) señalar(`La fila ${n} no dice que norma lo fija.`);

  const duda = /‹(POR CLASIFICAR|confirmar)([^›]*)›/.exec(parte);
  const asignada = PARTES.find((p) => parte.startsWith(p));
  if (!asignada && !duda) {
    señalar(
      `La fila ${n} tiene la parte «${parte}», que no es D-02a/b/c ni ‹POR CLASIFICAR›.`,
    );
  }
  if (duda) {
    // El motivo puede vivir en la propia celda o en la de la norma; lo que no se
    // admite es dudar sin decir de que.
    const motivo = /‹(?:POR CLASIFICAR|confirmar)[^›]*:\s*(.{20,})›/.test(`${parte} ${norma}`);
    if (!motivo) {
      señalar(`La fila ${n} duda de su parte y no dice por que: hace falta el motivo.`);
    }
  }

  const sinIssues = plano(issues).startsWith(SIN_ISSUES);
  const nombrados = sinIssues ? [] : [...issues.matchAll(/#(\d+)/g)].map((m) => m[1]);
  if (nombrados.length === 0 && !sinIssues) {
    señalar(
      `La fila ${n} no nombra issues ni dice «ninguno todavia». Una fila que no dice a quien` +
        ' bloquea no se puede comprobar.',
    );
  }
  if (sinIssues && !/ninguno todavia\s*[—–-]\s*\S.{19,}/.test(plano(issues))) {
    señalar(
      `La fila ${n} dice «ninguno todavia» y no dice por que. Una fila que no bloquea a nadie` +
        ' tiene que decir si es porque el dato ya esta firmado, porque su parte se cerro o' +
        ' porque nadie lo consume todavia.',
    );
  }
  filas.set(n, { parte: asignada ?? null, issues: nombrados });
}

const numeros = [...filas.keys()].sort((a, b) => a - b);
numeros.forEach((n, i) => {
  if (n !== i + 1) señalar(`Las filas del mapa no son consecutivas: tras ${numeros[i - 1]} viene ${n}.`);
});

// --- 2. Del mapa al inventario ----------------------------------------------

/** issue -> { partes: Set, filas: Set } segun las filas del mapa */
const segunElMapa = new Map();
for (const [n, fila] of filas) {
  for (const issue of fila.issues) {
    if (!segunElMapa.has(issue)) segunElMapa.set(issue, { partes: new Set(), filas: new Set() });
    const acumulado = segunElMapa.get(issue);
    acumulado.filas.add(n);
    if (fila.parte) acumulado.partes.add(fila.parte);
  }
}

/** issue -> { partes: Set, filas: Set } segun §2.8 */
const segunElInventario = new Map();
for (const [issue, partes, referencias] of inventario) {
  const numero = /#(\d+)/.exec(issue)?.[1];
  if (!numero) {
    señalar(`Fila de §2.8 sin numero de issue: «${issue}».`);
    continue;
  }
  const declaradas = new Set(partes.split(',').map((p) => p.trim()).filter(Boolean));
  for (const parte of declaradas) {
    if (!PARTES.includes(parte)) señalar(`#${numero} declara la parte «${parte}», que no existe.`);
  }
  segunElInventario.set(numero, {
    partes: declaradas,
    filas: new Set([...referencias.matchAll(/\d+/g)].map((m) => Number(m[0]))),
  });
}

const conjuntosIguales = (a, b) => a.size === b.size && [...a].every((x) => b.has(x));
const enOrden = (conjunto) => [...conjunto].sort((x, y) => (x > y ? 1 : -1)).join(', ');

for (const [issue, delMapa] of segunElMapa) {
  const delInventario = segunElInventario.get(issue);
  if (!delInventario) {
    señalar(
      `El mapa nombra a #${issue} en las filas ${enOrden(delMapa.filas)} y §2.8 no lo lista.`,
    );
    continue;
  }
  if (!conjuntosIguales(delMapa.partes, delInventario.partes)) {
    señalar(
      `#${issue}: las filas del mapa lo bloquean por ${enOrden(delMapa.partes) || '(nada)'} y` +
        ` §2.8 dice ${enOrden(delInventario.partes)}.`,
    );
  }
  if (!conjuntosIguales(delMapa.filas, delInventario.filas)) {
    señalar(
      `#${issue}: el mapa lo nombra en las filas ${enOrden(delMapa.filas)} y §2.8 declara` +
        ` ${enOrden(delInventario.filas)}.`,
    );
  }
}

for (const issue of segunElInventario.keys()) {
  if (!segunElMapa.has(issue)) {
    señalar(`§2.8 lista a #${issue} y ninguna fila del mapa lo nombra.`);
  }
}

// --- 3. Del inventario al tablero -------------------------------------------

const { etiquetas } = JSON.parse(readFileSync(TABLERO, 'utf8'));

for (const [issue, { partes }] of segunElInventario) {
  const puestas = etiquetas[issue];
  if (!puestas) {
    señalar(`§2.8 lista a #${issue} y el tablero no le tiene ninguna etiqueta de bloqueo.`);
    continue;
  }
  if (!conjuntosIguales(partes, new Set(puestas))) {
    señalar(
      `#${issue}: §2.8 dice ${enOrden(partes)} y el tablero tiene ${puestas.join(', ')}.`,
    );
  }
}

for (const issue of Object.keys(etiquetas)) {
  if (!segunElInventario.has(issue)) {
    señalar(
      `El tablero etiqueta a #${issue} y §2.8 no lo lista: o sobra la etiqueta, o falta la fila` +
        ' del mapa que la justifica.',
    );
  }
}

// --- Resultado ---------------------------------------------------------------

if (problemas.length > 0) {
  console.error('El mapa normativo y el tablero no dicen lo mismo:\n');
  for (const problema of problemas) console.error(`  - ${problema}`);
  console.error(
    `\n${problemas.length} problema(s). El mapa vive en docs/10-negocio/marco-normativo.md §2 y` +
      ' las etiquetas en docs/10-negocio/etiquetas-de-bloqueo.json.',
  );
  process.exit(1);
}

const porParte = PARTES.map(
  (p) => `${p}: ${[...segunElInventario.values()].filter((i) => i.partes.has(p)).length}`,
).join(' · ');
console.log(
  `Mapa normativo verificado: ${filas.size} filas, ${segunElInventario.size} issues bloqueados` +
    ` (${porParte}).`,
);
