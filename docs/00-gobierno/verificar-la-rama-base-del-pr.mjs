/* Comprueba que un PR esta abierto contra la rama principal, y que si NO lo esta es porque
   alguien lo decidio y lo escribio (`rentas`#220).

   ## El defecto, medido

   `rentas`#209 se mezclo y su trabajo NO llego a `main`. El estado tras mezclarlo era el de un
   trabajo terminado: el PR en `MERGED`, su issue —#182— cerrado, su fila escrita en
   `docs/agent/HISTORY.md` y la guarda del registro en verde. Lo unico que faltaba era el codigo.

     $ gh pr view 209 --json baseRefName   -> base=issue-173
     $ git log --oneline origin/main..origin/issue-173
       efa2241 Merge pull request #209 from hneyra/issue-182
       ef40f04 `territorio` deja de mentir: no esta «sin conectar», es que solo escribe (#182)

   El PR se abrio contra `issue-173` —la rama de la que colgaba, porque las dos editan
   `porQueNoHayDato.{ts,test.ts}`— y se mezclo AHI. La quinta frase «solo escribe», que afecta a
   cuatro hojas, estuvo horas fuera de `main` con todos los indicadores en verde.

   **Como se descubrio, que es la parte incomoda: por una resta que no cuadraba.** Contando las
   pruebas del frontend salieron 788 sobre `main` y 791 sobre la rama ya mezclada. Tres de
   diferencia. Nada en el repositorio lo senalaba, y el issue estaba cerrado, asi que tampoco iba
   a aparecer en ninguna revision de pendientes.

   ## Que comprueba, en tres reglas y no en una

   1. **La rama base es la principal.** Si no lo es, rojo, nombrando la rama contra la que esta
      abierto y el `gh pr edit` que lo retargetea.

   2. **Salvo que el apilamiento se declare.** Apilar PR es LEGITIMO —cuando dos ramas tocan el
      mismo archivo, abrir la segunda contra la primera es lo que evita un conflicto garantizado—,
      asi que hay una forma explicita de decirlo en el cuerpo:

        Apilado sobre issue-173

      Es el mismo trato que el auto-cierre: se puede no declararlo, pero entonces hay que decirlo
      con otra palabra. Y la declaracion tiene que NOMBRAR la rama base de verdad: una que nombre
      otra rama no vale, porque un «apilado sobre algo» generico seria un sello de goma y esta
      guarda se apagaria sola.

   3. **Y la que cierra el caso de `rentas`#209: sobre una rama que ya entro entera en la
      principal no se apila nadie, ni declarandolo.** Cuando el tip de la rama base ya es
      ancestro de la principal, el motivo para apilarse —que la base traiga trabajo sin mezclar
      con el que este PR choca— ha desaparecido, y lo unico que queda es que el commit se
      vare. Es literalmente lo que paso:

        $ git log -1 --format='%P' efa2241        -> 3017d4e ef40f04
        $ git merge-base --is-ancestor 3017d4e origin/main   -> 0

      El tip de `issue-173` de antes del merge YA estaba en `main`. Esta tercera regla pone
      `rentas`#209 en rojo AUNQUE su autor hubiera declarado el apilamiento, que es lo que la
      separa de un simple recordatorio: la declaracion documenta una decision, no la vuelve segura.

   ## Por que esto NO va en `verificar-fila-del-registro.mjs`, que es la guarda hermana

   Es la misma familia de defecto y la tentacion es evidente: aquella guarda ya RECIBE la rama base
   —`registro.yml` la invoca con `--base origin/${{ ... base.ref }}`— y solo la usa para calcular
   el diff. Tenia el dato delante y no lo miraba. Aun asi va aparte, por un motivo medido:

   **aquel guion es una de SEIS copias byte a byte** —`infrastructure`, `rentas`, `catastro`,
   `normativa`, `caja` e `identidad`— atadas por
   `infra/verificaciones/las-seis-copias-de-la-guarda-del-registro.test.ts` de `infrastructure`
   (`infrastructure`#165), que exige que sean identicas salvo el bloque de `RUTAS_DE_CODIGO`.
   Tocarlo aqui es tocarlo en los seis, y `infrastructure` se mezcla el ULTIMO: hasta que lleguen
   los otros cinco, su prueba se queda roja nombrando a los que faltan. Se comprobo, no se supuso:
   con esta comprobacion metida en la copia de `rentas`, esa prueba pasa a rojo con
   «las copias de la guarda del registro se han separado».

   Asi que una guarda nueva y propia entra HOY y protege HOY, y la compartida no queda a medias en
   seis repositorios a la vez.

   **LO QUE ESO DEJA PENDIENTE, y de quien es.** Los otros cinco repositorios tienen el mismo
   defecto y ninguna guarda que lo vea: `catastro`, `caja`, `normativa`, `identidad` e
   `infrastructure`. Este archivo se copia tal cual —no depende de nada de `rentas`— y son tres
   pasos en cada uno: el guion, su autoprueba y los dos pasos de `registro.yml`. Es de cada dueno,
   y aqui no se abre nada en su nombre. Si alguna vez se decide que vaya en la compartida, el orden
   no admite otro: los cinco primero, `infrastructure` al final.

   ## Uso

     node docs/00-gobierno/verificar-la-rama-base-del-pr.mjs

   La rama base sale de `KAMAYUK_RAMA_BASE_DEL_PR` y el cuerpo de `KAMAYUK_CUERPO_DEL_PR`. Sin la
   primera no hay nada que comprobar y la comprobacion pasa, porque fuera de un PR ese dato no
   existe — el mismo trato que le da la guarda del registro al cuerpo.

   En local, con el PR ya abierto:

     KAMAYUK_RAMA_BASE_DEL_PR=$(gh pr view N --json baseRefName --jq .baseRefName) \
     KAMAYUK_CUERPO_DEL_PR=$(gh pr view N --json body --jq .body) \
     node docs/00-gobierno/verificar-la-rama-base-del-pr.mjs

   Y **tiene que poder correrse asi**: la CI de este repositorio esta bloqueada por facturacion y
   falla en dos segundos sin ejecutar un paso, de modo que un `job` nuevo hoy no protegeria nada.
   Se engancha igual en `registro.yml` —para el dia que vuelva— y no se depende de el.

   Las entradas se pueden dar por archivo —`--rama-base`, `--cuerpo`— y el tercer hecho por
   `--ya-en-la-principal si|no`, y es lo que usa su autoprueba: sin poder alimentarlas, demostrar
   que muerde exigiria fabricar un repositorio con sus ramas, y una comprobacion que no se puede
   probar es la que esta guarda viene a impedir.
*/

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

/** La rama contra la que se abre un PR de este repositorio salvo decision escrita. */
const LA_PRINCIPAL = 'main';

/** El remoto contra el que se mide si una rama ya entro en la principal. */
const REMOTO = 'origin';

/** Como se declara un apilamiento deliberado.

    **Es una declaracion y no una palabra magica, y la diferencia esta en el grupo**: tiene que
    nombrar la rama. Una formula generica —«esto va apilado»— la escribiria cualquiera sin mirar,
    y una guarda que se satisface con un sello de goma no comprueba nada; nombrar la rama obliga a
    leer contra que esta abierto el PR, que es justo lo que en `rentas`#209 nadie hizo.

    Se aceptan las dos concordancias —«apilado» y «apilada»— y las comillas invertidas alrededor
    del nombre, porque aqui no hay un GitHub al otro lado que entienda solo una forma: esta guarda
    es el unico lector. Es la diferencia con `CIERRA`, que es estricta porque la estrictez la pone
    GitHub y no nosotros. */
const APILADO = /\bapilad[oa]\s+sobre\s+`?([A-Za-z0-9._\/-]+?)`?(?=[\s.,;:)]|$)/gi;

// Se ejecuta SOLO cuando se invoca como guion. Importarlo no hace nada, que es lo que permite a
// su autoprueba leer de aqui —`LA_PRINCIPAL`, `APILADO`— en vez de copiarlo: una copia se queda
// vieja sola y entonces la autoprueba certifica una regla que ya no es esta.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  principal();
}

function principal() {
  const opciones = leerOpciones(process.argv.slice(2));

  const rama = (
    opciones['rama-base']
      ? readFileSync(opciones['rama-base'], 'utf8')
      : (process.env.KAMAYUK_RAMA_BASE_DEL_PR ?? '')
  ).trim();

  if (rama === '') {
    console.log('No se sabe contra que rama esta abierto este PR: no hay nada que comprobar.');
    process.exit(0);
  }

  if (rama === LA_PRINCIPAL) {
    console.log(`Este PR esta abierto contra «${LA_PRINCIPAL}».`);
    process.exit(0);
  }

  const cuerpo = opciones.cuerpo
    ? readFileSync(opciones.cuerpo, 'utf8')
    : (process.env.KAMAYUK_CUERPO_DEL_PR ?? '');

  const declaradas = [...cuerpo.matchAll(APILADO)].map((coincidencia) => coincidencia[1]);

  if (!declaradas.includes(rama)) {
    console.error('');
    console.error(`FALLO: este PR NO esta abierto contra «${LA_PRINCIPAL}».`);
    console.error('');
    console.error(`  · Esta abierto contra «${rama}», y al mezclarlo su trabajo entra AHI.`);
    if (declaradas.length > 0) {
      console.error('');
      console.error(
        `  El cuerpo declara un apilamiento, pero sobre «${declaradas.join('», «')}», que no es` +
          ` la rama base de este PR.`,
      );
      console.error('  La declaracion tiene que nombrar la rama contra la que el PR esta abierto:');
      console.error('  si no, es un sello de goma que nadie contrasta con nada.');
    }
    laLeccionDe209();
    console.error(`  ARREGLO (lo normal): retargetealo a «${LA_PRINCIPAL}».`);
    console.error(`    gh pr edit ${elNumero()} --base ${LA_PRINCIPAL}`);
    console.error('');
    console.error('  Y SI EL APILAMIENTO ES DELIBERADO —dos ramas que tocan el mismo archivo, y');
    console.error('  abrir la segunda contra la primera es lo que evita el conflicto—, entonces');
    console.error('  declaralo en el cuerpo del PR:');
    console.error('');
    console.error(`    Apilado sobre ${rama}`);
    console.error('');
    console.error('  Es el mismo trato que el auto-cierre: se puede no declararlo, pero entonces');
    console.error('  hay que decirlo con otra palabra. Lo unico que esto impide es apilar sin');
    console.error('  que nadie lo haya decidido, que es lo que paso en `rentas`#209.');
    console.error('');
    console.error('  Editar el cuerpo del PR relanza esta comprobacion sin empujar un commit:');
    console.error('  `registro.yml` escucha `edited`, justo para los rojos de cuerpo.');
    process.exit(1);
  }

  // Declarado y bien nombrado. Queda el tercer hecho, que es el que hundio a `rentas`#209:
  // apilarse sobre una rama que YA entro entera en la principal no evita ningun conflicto —no queda
  // nada suyo por mezclar— y solo consigue varar el commit.
  const yaEntro = opciones['ya-en-la-principal']
    ? opciones['ya-en-la-principal'] === 'si'
    : ramaYaEnLaPrincipal(rama);

  if (yaEntro === true) {
    console.error('');
    console.error(`FALLO: «${rama}» ya entro entera en «${LA_PRINCIPAL}».`);
    console.error('');
    console.error('  · El apilamiento esta declarado, y aun asi esto es rojo: sobre una rama que');
    console.error('    ya se mezclo no queda nada con lo que chocar, asi que apilarse sobre ella');
    console.error('    no evita ningun conflicto. Lo unico que hace es dejar el commit en una');
    console.error('    rama que nadie vuelve a mirar.');
    laLeccionDe209();
    console.error(`  ARREGLO: retargetealo, que ademas ahora no puede dar conflicto.`);
    console.error(`    gh pr edit ${elNumero()} --base ${LA_PRINCIPAL}`);
    console.error('');
    process.exit(1);
  }

  if (yaEntro === null) {
    // Ni se afirma lo que no se midio ni se pone rojo por no tener el dato: se dice cual falta. Un
    // rojo aqui seria por el clon y no por el defecto, y eso es lo que acaba apagando una guarda.
    console.log(
      `Apilamiento declarado sobre «${rama}». No se pudo leer ${REMOTO}/${rama}, asi que si ya` +
        ` entro entera en «${LA_PRINCIPAL}» NO SE MIDIO: haria falta un clon con esa rama —en CI,` +
        ` «fetch-depth: 0»—.`,
    );
    process.exit(0);
  }

  console.log(
    `Apilamiento declarado sobre «${rama}», que todavia no esta entera en «${LA_PRINCIPAL}».`,
  );
}

// ---------------------------------------------------------------------------

/** El parrafo que las dos ramas rojas comparten, escrito una sola vez. */
function laLeccionDe209() {
  console.error('');
  console.error('  MEDIDO EN `rentas`#209, el 2026-09-17: el PR se abrio contra `issue-173`, se');
  console.error('  mezclo ahi, y su trabajo NO llego a `main`. El PR quedo en MERGED, su issue');
  console.error('  cerrado, su fila escrita y la guarda del registro en verde — todo verde menos');
  console.error('  el codigo, que se quedo fuera del arbol. Se descubrio horas despues por una');
  console.error('  resta de pruebas que no cuadraba: 788 sobre `main` y 791 sobre la rama.');
  console.error('');
}

/** El numero de este PR si se sabe, y si no un hueco que se ve que hay que rellenar. */
function elNumero() {
  const numero = (process.env.KAMAYUK_NUMERO_DEL_PR ?? '').trim();
  return /^\d+$/.test(numero) ? numero : '<numero>';
}

/**
 * Si esa rama ya es ancestro de la principal —o sea, si ya entro entera—.
 *
 * Devuelve `null` cuando no se pudo medir: la rama no esta en este clon, o `git` no contesto. No
 * se colapsa con `false` a proposito, porque son cosas distintas —«comprobado que le falta» y «no
 * se comprobo»— y quien llama decide que hacer con cada una.
 */
function ramaYaEnLaPrincipal(rama) {
  const laRama = `${REMOTO}/${rama}`;
  const laPrincipal = `${REMOTO}/${LA_PRINCIPAL}`;
  for (const referencia of [laRama, laPrincipal]) {
    try {
      execFileSync('git', ['rev-parse', '--verify', '--quiet', `${referencia}^{commit}`], {
        stdio: 'ignore',
      });
    } catch {
      return null;
    }
  }
  try {
    execFileSync('git', ['merge-base', '--is-ancestor', laRama, laPrincipal], { stdio: 'ignore' });
    return true;
  } catch (fallo) {
    // `--is-ancestor` sale con 1 cuando NO lo es, y con otra cosa cuando algo fue mal. Distinguirlo
    // importa: tratar un error de `git` como «no es ancestro» seria un verde por averia.
    return fallo.status === 1 ? false : null;
  }
}

function leerOpciones(argumentos) {
  const opciones = {};
  for (let i = 0; i < argumentos.length; i += 2) {
    const nombre = argumentos[i];
    const valor = argumentos[i + 1];
    if (valor === undefined) {
      throw new Error(`Falta el valor de ${nombre}`);
    }
    if (!['--rama-base', '--cuerpo', '--ya-en-la-principal'].includes(nombre)) {
      throw new Error(`Opcion desconocida: ${nombre}`);
    }
    opciones[nombre.slice(2)] = valor;
  }
  if (
    opciones['ya-en-la-principal'] !== undefined &&
    !['si', 'no'].includes(opciones['ya-en-la-principal'])
  ) {
    throw new Error('--ya-en-la-principal solo admite «si» o «no»');
  }
  return opciones;
}

export { APILADO, LA_PRINCIPAL };
