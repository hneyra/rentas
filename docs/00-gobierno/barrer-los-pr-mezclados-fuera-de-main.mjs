/* Barre el historial buscando PR ya mezclados cuyo trabajo NO llego a la rama principal
   (`rentas`#220, AC-3).

   La guarda de al lado —`verificar-la-rama-base-del-pr.mjs`— impide que vuelva a pasar. Este
   guion contesta la otra mitad, que es la incomoda: **cuantos han pasado ya**. En #209 el hueco
   se descubrio por una resta de pruebas que no cuadraba —788 sobre `main` y 791 sobre la rama—, y
   esa cuenta se hizo por casualidad, contando para otro issue.

   Y hace falta AUNQUE la guarda este puesta, porque el apilamiento declarado sigue siendo
   legitimo: la declaracion documenta la decision, no garantiza que el commit llegue. Este barrido
   es lo unico que cierra ese ultimo tramo, y por eso se corre a mano y no en cada PR.

   ## Como decide, y por que en dos pasos y no en uno

   El primer paso es barato y exacto: **el commit de mezcla, ¿es ancestro de la principal?** Si lo
   es, el trabajo esta dentro y no hay mas que mirar.

   Si no lo es, NO se concluye nada todavia, y eso hubo que medirlo: en este repositorio hay cinco
   PR de la tanda del 2026-09-06 mezclados contra `beta` —la rama de trabajo de aquella etapa, que
   ya no existe— cuyos commits de mezcla no son ancestros de `main` y cuyo contenido SI esta en
   `main`, porque `beta` entro despues aplastada. Un barrido que se quedara en el primer paso los
   daria por varados a los cinco, y una guarda que grita cinco falsos acaba apagada.

   Asi que el segundo paso mira el CONTENIDO: se toman lineas anadidas por el PR —las largas, que
   son las que identifican— y se cuenta cuantas siguen apareciendo en el arbol de la principal. Es
   una senal y se dice como tal: el veredicto que imprime nombra la proporcion, y la decision de
   rescatar la toma quien lo lee. Un archivo puede haberse reescrito desde entonces, y entonces
   este paso dira «no esta» de algo que si esta — en la direccion segura, que es la que hace mirar.

   ## Uso

     node docs/00-gobierno/barrer-los-pr-mezclados-fuera-de-main.mjs [--limite 300]

   Necesita `gh` autenticado y la rama principal en el clon. Sale con 1 si algun PR parece varado.
*/

import { execFileSync } from 'node:child_process';

const LA_PRINCIPAL = 'origin/main';

/** Cuantas lineas anadidas se muestrean por PR, y cuantas tienen que aparecer para darlo por
    llegado. No son cifras finas: el barrido separa «casi todo» de «casi nada», y los casos reales
    medidos en este repositorio caen en un extremo o en el otro (5 de 5 frente a 0 de 8). */
const LINEAS_QUE_SE_MUESTREAN = 12;
const PROPORCION_QUE_BASTA = 0.6;

/** Lo corto no identifica nada: un `import` o un `}` estan en todos los arboles. */
const LARGO_MINIMO = 40;

const limite = leerLimite(process.argv.slice(2));

const mezclados = JSON.parse(
  gh(['pr', 'list', '--state', 'merged', '--limit', String(limite),
    '--json', 'number,title,baseRefName,headRefName,mergeCommit']),
);

const fuera = mezclados.filter((pr) => pr.baseRefName !== 'main');

console.log(`PR mezclados leidos: ${mezclados.length} (limite ${limite}).`);
console.log(`Mezclados contra una rama que no es «main»: ${fuera.length}.`);
console.log('');

if (fuera.length === 0) {
  console.log('Ninguno. No hay nada que rescatar.');
  process.exit(0);
}

const varados = [];

for (const pr of fuera) {
  const sha = pr.mergeCommit?.oid ?? '';
  console.log(`#${pr.number}  base=${pr.baseRefName}  head=${pr.headRefName}`);
  console.log(`  ${pr.title}`);

  if (sha && esAncestro(sha, LA_PRINCIPAL)) {
    console.log(`  LLEGO: su commit de mezcla ${sha.slice(0, 8)} es ancestro de ${LA_PRINCIPAL}.`);
    console.log('');
    continue;
  }

  const motivo = sha
    ? `su commit de mezcla ${sha.slice(0, 8)} no es ancestro de ${LA_PRINCIPAL}`
    : 'no consta su commit de mezcla';

  const muestra = lineasQueIdentifican(pr.number);
  if (muestra.length === 0) {
    console.log(`  A MANO: ${motivo}, y no se pudo leer su diff para mirar el contenido.`);
    varados.push(pr);
    console.log('');
    continue;
  }

  const presentes = muestra.filter((linea) => estaEnLaPrincipal(linea));
  const proporcion = presentes.length / muestra.length;
  const veredicto = proporcion >= PROPORCION_QUE_BASTA ? 'LLEGO POR OTRA VIA' : 'VARADO';
  console.log(`  ${veredicto}: ${motivo};`);
  console.log(
    `  de ${muestra.length} lineas suyas que identifican, ${presentes.length} estan en` +
      ` ${LA_PRINCIPAL}.`,
  );
  if (veredicto === 'VARADO') {
    varados.push(pr);
    console.log(`  RESCATE: git cherry-pick, o  gh pr view ${pr.number} --json headRefOid`);
  }
  console.log('');
}

if (varados.length > 0) {
  console.error(`FALLO: ${varados.length} PR parecen varados: ` +
    varados.map((pr) => `#${pr.number}`).join(', '));
  console.error('');
  console.error('  Su trabajo se mezclo en una rama que no es la principal y no consta en ella.');
  console.error('  Es el defecto de #209: PR en MERGED, issue cerrado, fila escrita, codigo');
  console.error('  fuera del arbol. Reviselos uno a uno antes de dar el barrido por bueno.');
  process.exit(1);
}

console.log('Todos los mezclados fuera de «main» tienen su trabajo dentro. Nada que rescatar.');

// ---------------------------------------------------------------------------

/** Lineas anadidas por ese PR que sirven para reconocerlo en otro arbol. */
function lineasQueIdentifican(numero) {
  let diff = '';
  try {
    diff = gh(['pr', 'diff', String(numero)]);
  } catch {
    return [];
  }
  const candidatas = diff
    .split('\n')
    .filter((linea) => linea.startsWith('+') && !linea.startsWith('+++'))
    .map((linea) => linea.slice(1).trim())
    .filter((linea) => linea.length >= LARGO_MINIMO);
  // Repartidas por todo el diff y no las doce primeras: las de arriba suelen ser la fila del
  // registro y las cabeceras, que viajan aparte del codigo y darian por llegado un PR cuyo
  // codigo no llego.
  const paso = Math.max(1, Math.floor(candidatas.length / LINEAS_QUE_SE_MUESTREAN));
  const muestra = [];
  for (let i = 0; i < candidatas.length && muestra.length < LINEAS_QUE_SE_MUESTREAN; i += paso) {
    muestra.push(candidatas[i]);
  }
  return muestra;
}

function estaEnLaPrincipal(linea) {
  try {
    execFileSync('git', ['grep', '--quiet', '--fixed-strings', linea, LA_PRINCIPAL], {
      stdio: 'ignore',
    });
    return true;
  } catch {
    return false;
  }
}

function esAncestro(sha, referencia) {
  try {
    execFileSync('git', ['merge-base', '--is-ancestor', sha, referencia], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

function gh(argumentos) {
  return execFileSync('gh', argumentos, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

function leerLimite(argumentos) {
  if (argumentos.length === 0) return 300;
  if (argumentos.length !== 2 || argumentos[0] !== '--limite' || !/^\d+$/.test(argumentos[1])) {
    throw new Error(
      'Uso: node docs/00-gobierno/barrer-los-pr-mezclados-fuera-de-main.mjs [--limite N]',
    );
  }
  return Number(argumentos[1]);
}
