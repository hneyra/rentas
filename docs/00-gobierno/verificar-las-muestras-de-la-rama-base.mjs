/* Comprueba que verificar-la-rama-base-del-pr.mjs muerde, y que no muerde de mas.

   Una guarda que no puede fallar no protege nada; y una que grita siempre acaba esquivada, que
   en una convencion de proceso es peor todavia — el peaje se aprende a rodear y el commit se
   queda igual de varado.

   Asi que se corre la comprobacion contra una lista de situaciones fabricadas, unas que tiene que
   rechazar y otras que tiene que dejar pasar, y se exige que el rechazo **nombre la rama y su
   motivo**: rechazar por el motivo equivocado seria pasar por casualidad. Es la misma forma que
   `verificar-las-muestras-del-registro.mjs`, y no por parecido: es lo que este repositorio tiene
   por prueba de una guarda que no es una prueba de Vitest ni de JUnit.

   **El recuento no se escribe aqui**, lo imprime el final del guion. En la autoprueba hermana esa
   frase decia «nueve, cinco y cuatro» y llevaba cuatro muestras siendo falsa.

   ## Las tres reglas, y por que cada una necesita su pareja

   1. **La rama base es la principal.** Una muestra roja contra `issue-173` y su contraste verde
      contra `main`. Sin el contraste, la guarda se podria satisfacer gritando en todos los PR, y
      una guarda que grita en cada PR se acaba apagando.

   2. **Salvo que el apilamiento se declare.** La declarada que pasa, y la que declara OTRA rama,
      que tiene que seguir roja: sin esa, «Apilado sobre lo que sea» seria un sello de goma.

   3. **Y sobre una rama que ya entro entera en la principal no se apila nadie.** La muestra que
      reproduce #209: declarado, bien nombrado, y rojo igual. Es la unica de las tres que el
      apilamiento legitimo no puede desactivar, y por eso va con su contraste —la misma rama sin
      mezclar aun, que pasa— pegado al lado.

   Y dos mas que no son de ninguna regla sino del contorno: sin rama base no hay nada que
   comprobar —fuera de un PR ese dato no existe—, y la rama se lee tal cual, de modo que un
   `mainline` cualquiera no cuela por parecerse a `main`.

   Uso: node docs/00-gobierno/verificar-las-muestras-de-la-rama-base.mjs
*/

import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { APILADO, LA_PRINCIPAL } from './verificar-la-rama-base-del-pr.mjs';

const COMPROBACION = fileURLToPath(new URL('./verificar-la-rama-base-del-pr.mjs', import.meta.url));

const CASOS = [
  {
    // El caso de #209 tal cual llego: nadie declaro nada y nadie miro la rama base.
    nombre: 'abierto contra una rama que no es la principal, sin declarar nada',
    ramaBase: 'issue-173',
    cuerpo: 'Closes #182.\n\nLa quinta frase, «solo escribe».',
    esperado: 'rojo',
    dice: ['issue-173', `NO esta abierto contra «${LA_PRINCIPAL}»`, '--base main'],
  },
  {
    // El contraste, y es el que impide que esto se satisfaga gritando siempre.
    nombre: 'abierto contra la principal: no dice nada',
    ramaBase: LA_PRINCIPAL,
    cuerpo: 'Closes #182.',
    esperado: 'verde',
  },
  {
    // La segunda regla: apilar es legitimo, y declararlo es la salida limpia.
    nombre: 'apilamiento declarado sobre la rama base de verdad: pasa',
    ramaBase: 'issue-173',
    cuerpo: 'Closes #182.\n\nApilado sobre issue-173, que toca el mismo archivo.',
    yaEnLaPrincipal: 'no',
    esperado: 'verde',
  },
  {
    // Y con las comillas invertidas, que es como se escribe un nombre de rama en la prosa de la
    // casa. Sin esta, la forma natural de escribirlo saldria roja y la guarda se volveria un
    // acertijo de puntuacion.
    nombre: 'la declaracion con la rama entre comillas invertidas tambien vale',
    ramaBase: 'issue-173',
    cuerpo: 'Apilado sobre `issue-173`.',
    yaEnLaPrincipal: 'no',
    esperado: 'verde',
  },
  {
    // La precision que la declaracion tiene que tener. Sin esto, «Apilado sobre lo que sea» seria
    // un sello de goma: la escribiria cualquiera sin mirar contra que esta abierto el PR, que es
    // exactamente lo que en #209 nadie hizo.
    nombre: 'declara el apilamiento sobre OTRA rama: sigue rojo, y lo dice',
    ramaBase: 'issue-173',
    cuerpo: 'Apilado sobre issue-199.',
    esperado: 'rojo',
    dice: ['issue-173', 'issue-199', 'no es la rama base'],
  },
  {
    /* La tercera regla, que es la que cierra #209 de verdad: aqui el apilamiento ESTA declarado y
       bien nombrado, y sale rojo igual porque la rama base ya entro entera en la principal.

       Medido sobre el caso real: el primer padre del merge de #209 —el tip de `issue-173` de antes
       de mezclarlo— ya era ancestro de `main`, asi que no quedaba nada con lo que chocar y lo unico
       que consiguio el apilamiento fue varar el commit. Sin esta regla, #209 habria salido VERDE
       con solo escribir una linea. */
    nombre: 'declarado, pero la rama base ya entro entera en la principal: rojo igual (#209)',
    ramaBase: 'issue-173',
    cuerpo: 'Apilado sobre issue-173.',
    yaEnLaPrincipal: 'si',
    esperado: 'rojo',
    dice: ['issue-173', 'ya entro entera', '--base main'],
  },
  {
    // Su contraste, pegado al lado: la MISMA rama y la MISMA declaracion, y lo unico que cambia es
    // que todavia le falta entrar. Sin este verde, la tercera regla se podria satisfacer
    // prohibiendo el apilamiento, que es justo lo que el issue dice que no entra.
    nombre: 'declarado y la rama base todavia NO ha entrado: pasa',
    ramaBase: 'issue-173',
    cuerpo: 'Apilado sobre issue-173.',
    yaEnLaPrincipal: 'no',
    esperado: 'verde',
  },
  {
    // El contorno. Fuera de un PR no existe el dato, y una guarda que se pone roja por no tener el
    // dato se apaga en el primer `node` que alguien corre a mano.
    nombre: 'sin rama base —fuera de un PR— no hay nada que comprobar',
    ramaBase: '',
    cuerpo: 'Apilado sobre lo que sea.',
    esperado: 'verde',
  },
  {
    // La rama se compara entera, no por prefijo: `mainline` no es `main`. Con un `startsWith` en
    // vez de una igualdad, esta muestra saldria verde y una rama de trabajo llamada asi pasaria.
    nombre: 'una rama que EMPIEZA por el nombre de la principal no es la principal',
    ramaBase: `${LA_PRINCIPAL}line`,
    cuerpo: 'Sin declarar nada.',
    esperado: 'rojo',
    dice: [`${LA_PRINCIPAL}line`, `NO esta abierto contra «${LA_PRINCIPAL}»`],
  },
];

/* Y la direccion que faltaba: que el numero del PR llegue al `gh pr edit` que el rojo propone.
   Sin ella, el arreglo que la guarda escribe es un `<numero>` que hay que ir a buscar, y un
   arreglo que no se puede pegar tal cual es un arreglo que no se aplica. */
const CON_NUMERO = {
  nombre: 'el rojo propone el `gh pr edit` con el numero de ESTE PR',
  ramaBase: 'issue-173',
  cuerpo: 'Sin declarar nada.',
  entorno: { KAMAYUK_NUMERO_DEL_PR: '209' },
  esperado: 'rojo',
  dice: ['gh pr edit 209 --base main'],
};
CASOS.push(CON_NUMERO);

/* El centinela de la propia autoprueba: que lo que se importa del guion sea lo que se cree. Si
   `LA_PRINCIPAL` viniera vacia, la mitad de las muestras compararia contra la cadena vacia y todo
   pasaria sin medir nada; si `APILADO` dejara de casar, las verdes de arriba seguirian siendo
   verdes por el motivo equivocado. */
if (typeof LA_PRINCIPAL !== 'string' || LA_PRINCIPAL.length === 0) {
  console.error('MAL: `LA_PRINCIPAL` se leyo vacia, asi que esto no mediria nada.');
  process.exit(2);
}
if (!'Apilado sobre issue-173'.match(APILADO)) {
  console.error('MAL: `APILADO` no reconoce ni la forma canonica de la declaracion.');
  process.exit(2);
}

const carpeta = mkdtempSync(join(tmpdir(), 'rentas-220-'));
let fallos = 0;

for (const caso of CASOS) {
  const ramaBase = join(carpeta, 'rama-base.txt');
  const cuerpo = join(carpeta, 'cuerpo.txt');
  writeFileSync(ramaBase, caso.ramaBase);
  writeFileSync(cuerpo, caso.cuerpo);

  const argumentos = [COMPROBACION, '--rama-base', ramaBase, '--cuerpo', cuerpo];
  if (caso.yaEnLaPrincipal !== undefined) {
    argumentos.push('--ya-en-la-principal', caso.yaEnLaPrincipal);
  }

  let salida = '';
  let codigo = 0;
  try {
    salida = execFileSync('node', argumentos, {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      // El entorno se limpia a proposito: las dos variables que el guion lee de verdad estan
      // puestas en la sesion que abre el PR, y una muestra que las heredara mediria el PR de
      // quien la corre y no el caso que dice medir.
      env: { ...process.env, KAMAYUK_RAMA_BASE_DEL_PR: '', KAMAYUK_CUERPO_DEL_PR: '',
        KAMAYUK_NUMERO_DEL_PR: '', ...(caso.entorno ?? {}) },
    });
  } catch (fallo) {
    codigo = fallo.status ?? -1;
    salida = `${fallo.stdout ?? ''}${fallo.stderr ?? ''}`;
  }

  const fueRojo = codigo !== 0;
  const esperabaRojo = caso.esperado === 'rojo';
  if (fueRojo !== esperabaRojo) {
    console.error(`MAL: «${caso.nombre}» esperaba ${caso.esperado} y salio lo contrario.`);
    console.error(salida.trim());
    fallos++;
    continue;
  }
  /* `dice` es una LISTA y no una cadena, por lo mismo que en la autoprueba hermana: con tres
     reglas distintas en el mismo guion, un `dice: 'issue-173'` lo satisface cualquiera de las
     tres, asi que una muestra podia ponerse roja por el motivo equivocado y pasar. Cada roja
     nombra su rama **y su motivo**. */
  const faltan = [caso.dice ?? []].flat().filter((texto) => !salida.includes(texto));
  if (esperabaRojo && faltan.length > 0) {
    console.error(`MAL: «${caso.nombre}» se puso rojo sin decir ${faltan.join(' ni ')}.`);
    console.error(salida.trim());
    fallos++;
    continue;
  }
  console.log(`OK (${caso.esperado}): ${caso.nombre}`);
}

if (fallos > 0) {
  console.error(`\nFALLO: ${fallos} de ${CASOS.length} muestras no se comportan como deben.`);
  process.exit(1);
}
console.log(`\nLas ${CASOS.length} muestras se comportan como deben: las tres reglas muerden, y`);
console.log('las tres tienen quien demuestre que no muerden de mas.');
