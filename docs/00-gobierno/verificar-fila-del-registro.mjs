/* Comprueba que un PR que cierra un issue deja su fila en «Verificar antes de afirmar»,
   y —desde #130— que el cierre que declara lo entienda tambien GitHub.

   El registro de «Verificar antes de afirmar» es la memoria del proyecto: cada issue
   deja ahi que se implemento y **como se demostro que la verificacion puede fallar**.
   Es lo que impide volver a descubrir el mismo hallazgo de RLS por tercera vez.

   Y no la comprobaba nadie. Al integrar #585 y #618 la fila no se escribio y los dos
   PR pasaron todos sus checks en verde; el hueco se descubrio a mano, leyendo la
   tabla. El modo de fallo es silencioso: la fila que falta no se distingue de la que
   nadie tenia que escribir.

   ## Que exige, y que NO

   Exige que **exista** una fila que nombre el issue. No mira su contenido —que la
   mutacion descrita sea real, que las cifras cuadren— porque eso no lo puede leer una
   maquina, y es justo lo que la revision si puede.

   Y solo lo exige cuando las dos cosas son ciertas:

     1. el cuerpo del PR declara que cierra un issue (`Cierra #N`, `Closes #N`,
        `Fixes #N`, `Resuelve #N`), y
     2. el cambio toca el codigo de produccion del backend, del frontend o de infra.

   Un PR de solo documentacion, de solo pruebas o sin issue asociado pasa en verde. Sin
   ese contraste la guarda seria un peaje que todo el mundo aprende a esquivar — y una
   guarda esquivada no protege nada, que es de donde venimos.

   ## Y desde #130, una segunda cosa: que el cierre declarado CIERRE

   Esta guarda reconoce `Cierra #N`, en castellano, que es el idioma de la casa. **GitHub
   no.** Su auto-cierre solo entiende close/closes/closed, fix/fixes/fixed y
   resolve/resolves/resolved. El PR #129 dijo «Cierra #111», se mezclo con todo en verde
   —esta guarda incluida, porque la fila estaba— y **el issue se quedo abierto** sin que
   nada lo dijera; los otros cinco de la tanda decian «Closes» y cerraron solos.

   Asi que si el cuerpo declara un cierre con una palabra que GitHub ignora, esto sale
   **rojo**, nombra el issue que va a quedarse abierto y escribe la linea que hay que
   poner. **No cambia el idioma de nada** —AC «Lo que NO entra» de #130—: `Cierra #N` se
   sigue reconociendo, y lo que se pide es una linea que GitHub sepa leer.

   **Por que rojo y no una advertencia en la salida.** Porque el defecto que viene a cerrar
   es exactamente «verde que nadie mira»: un aviso impreso en un check que sale en verde
   tiene la misma forma que el fallo —CI contenta, log sin leer— y lo habria reproducido en
   vez de cerrarlo. El coste esta acotado y medido: de los seis PR de la tanda, cinco
   seguirian verdes y solo el roto se pondria rojo; el remedio es **una linea del cuerpo**,
   lo escribe quien lo escribio mal, y `registro.yml` escucha `edited` desde #57 —para esto
   mismo—, asi que editar el cuerpo relanza la comprobacion sin empujar un commit. Y el que
   de verdad no quiera auto-cierre tiene salida limpia: no declararlo («Ref #N»).

   ## Uso

     node docs/00-gobierno/verificar-fila-del-registro.mjs [--base origin/main]

   El cuerpo del PR sale de `KAMAYUK_CUERPO_DEL_PR`; sin esa variable no hay nada que
   comprobar y la comprobacion pasa, porque fuera de un PR no existe el dato.

   Las tres entradas se pueden dar por archivo —`--cuerpo`, `--archivos`, `--anadido`—,
   y es lo que usa su autoprueba: sin poder alimentarlas, demostrar que muerde exigiria
   fabricar un repositorio, y una comprobacion que no se puede probar es la que este
   issue viene a impedir.
*/

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

/** Lo que hace de un cambio «codigo» a efectos de esta guarda.

    ESTA LISTA ES PROPIA DE ESTE REPOSITORIO Y NO SE COPIA. El guion vive replicado en
    los cinco y lo comun es el MECANISMO —los casos de la autoprueba, `CIERRA`, «exige
    que la fila exista y no lo que diga»—; la lista la decide cada dueno con lo que su
    arbol tiene. Copiarla a ciegas es exactamente lo que produjo el hueco de #45.

    `infrastructure/src/` (#45). El descriptor de despliegue decide que corre en la
    municipalidad: los limites, los `securityContext`, las `NetworkPolicy`, las variables
    de entorno del pod y sus rutas de ingreso. C-17 midio CINCO defectos que vivian ahi y
    que solo se ven al desplegar. Estaba fuera y en los otros tres repositorios dentro:
    este es el unico de los cuatro que tiene los dos directorios a la vez —`infra/` con la
    carga de datos, `infrastructure/` con el descriptor—, que es de donde salio la
    confusion al copiar. Se acota a `src/` a proposito: `infrastructure/verificaciones/`
    son sus pruebas, y una prueba no es codigo de produccion.

    `infra/` SE QUEDA, y no por inercia (#45 AC-2). Son cuatro guiones de carga y cuatro
    CSV, y C-6 midio lo que cuesta uno mal apuntado: un guion lanzado contra la imagen
    equivocada arranca la aplicacion, NO CARGA NI UNA FILA y sale con codigo 0 —cero
    lineas de carga, ni un aviso—, que es la clase de defecto que solo el registro
    impide volver a descubrir. LO QUE CUESTA, contado: de los diez archivos de `infra/`,
    dos son `README.md`, asi que un PR que solo los toque y ademas cierre un issue
    tendra que dejar fila. Se acepta y no se talla una excepcion para dos archivos: esos
    README documentan con que variable se invoca cada cargador —lo que el censo de
    `infrastructure` cruza contra su `@ConditionalOnProperty`— y la guarda solo dispara
    cuando el PR ADEMAS cierra un issue, asi que el exceso esta acotado.

    LO QUE SIGUE FUERA, medido y no supuesto: `despliegue/compose.yaml`, que este
    repositorio tiene desde #44. Es el mismo defecto que `caja`#39 cerro alli con
    `/^despliegue\//`, y aqui NO se cierra porque no es de #45 — queda dicho para que el
    siguiente no tenga que volver a medirlo.

    SE EXPORTA para que su autoprueba pueda exigir que cada patron tenga su muestra. Es
    la mitad que faltaba: quitar una muestra dejaba la autoprueba en «las 7 se comportan
    como deben», en verde. Y se exporta en vez de copiarse alli porque una copia se queda
    vieja sola y entonces la autoprueba certifica una lista que ya no es esta. */
export const RUTAS_DE_CODIGO = [
  /^backend\/[^/]+\/src\/main\//,
  /^infrastructure\/src\//,
  /^frontend\/src\//,
  // El manifiesto del frontend es codigo de produccion aunque no sea `src/`: decide QUE VIAJA
  // AL BUNDLE. Desde #74 declara tres `link:` a un clon hermano, y sin esta linea el PR que los
  // enchufo —el cambio de infraestructura mas delicado de la etapa— habria pasado en VERDE sin
  // dejar su fila, porque no tocaba `frontend/src/`. Verde silencioso: la fila que falta no se
  // distingue de la que nadie tenia que escribir.
  //
  // Y se acota al archivo, no al directorio: `frontend/` entero incluye pruebas, `diseno/` y
  // configuracion, y una guarda que grita en cada PR se acaba apagando (#437).
  /^frontend\/package\.json$/,
  // Y por lo mismo, los dos archivos que deciden QUE IMAGEN SE PUBLICA y COMO SE LEVANTA la
  // instalacion. #75 los toco los dos —el `Dockerfile` gana un contexto con nombre, el compose
  // el suyo— y sin estas dos lineas ese PR habria salido verde sin fila, igual que #74 sin la
  // de arriba. El comentario de mas abajo ya anotaba el hueco del compose «para que el siguiente
  // no tenga que volver a medirlo»: este es el siguiente.
  /^frontend\/Dockerfile$/,
  /^despliegue\/compose\.yaml$/,
  /^infra\//,
];

/**
 * Donde vive la fila. **Es UNA, y ya no es una ventana de compatibilidad** (`infrastructure`#114):
 * el registro se mudo de `CLAUDE.md` a `docs/agent/HISTORY.md` —eran el 91 % de un archivo que
 * cada sesion carga entero— y **los seis repositorios migraron el 2026-09-12**, asi que el
 * estrechado llega en su cambio propio, que es como el primer tiempo dijo que se haria.
 *
 * Lo que cambia con esto: **una fila escrita en `CLAUDE.md` deja de contar**. Mientras los dos
 * sitios estuvieran aqui, un PR podia dejar su fila en el archivo viejo y salir en verde, y la
 * memoria del proyecto se partia en dos sin que nada lo dijera — que es justo lo que la mudanza
 * viene a cerrar.
 *
 * Sigue siendo una lista y no una cadena a proposito: es lo que se le pasa a `git diff -- …`, y
 * el dia que el registro se vuelva a partir —por tamano, por ejemplo— el segundo archivo entra
 * aqui y no hay nada mas que tocar.
 */
const DONDE_VIVE_LA_FILA = ['docs/agent/HISTORY.md'];

/** Como se declara que un PR cierra un issue: en el idioma de la casa, y en el de GitHub.

    **Son DOS listas y no una, y esa diferencia es el defecto de #130.** CLAUDE.md manda
    «comentarios, pruebas y mensajes de commit en espanol», asi que esta guarda reconoce
    `Cierra #N` y `Resuelve #N` y las va a seguir reconociendo: el idioma no se cambia por una
    limitacion de GitHub. **Pero GitHub solo auto-cierra con las inglesas** —close/closes/closed,
    fix/fixes/fixed, resolve/resolves/resolved— y con ninguna mas.

    Medido en la tanda del 2026-09-12, PR a PR: #121, #122, #123, #124 y #128 decian `Closes #N`
    y **cerraron su issue al mezclar**; #129 decia `Cierra #111` y **no cerro nada**. El PR se
    mezclo, la CI quedo verde, esta misma guarda dijo que la fila estaba, y el issue siguio
    abierto hasta que alguien lo cerro a mano al auditar. El modo de fallo **se parece al exito**,
    que es el peor que hay — y la trampa estaba montada por construccion: esta guarda PREMIA
    escribir en castellano y esa misma palabra es la que GitHub ignora.

    Las inglesas van en las dos listas a proposito, y hasta #130 `CIERRA` no conocia `closed`,
    `fixed` ni `resolved`. Eran dos huecos: un cuerpo que dijera «Fixed #N» cerraba el issue en
    GitHub y aqui **no exigia fila**, y uno que dijera «Cierra #N» y «Fixed #N» a la vez se leeria
    como que #N se queda sin auto-cierre, cuando si lo tiene. */
const PALABRAS_DE_LA_CASA = ['cierra', 'resuelve'];
const PALABRAS_DE_GITHUB = [
  'closes',
  'closed',
  'close',
  'fixes',
  'fixed',
  'fix',
  'resolves',
  'resolved',
  'resolve',
];

const CIERRA = declaracionDeCierre([...PALABRAS_DE_LA_CASA, ...PALABRAS_DE_GITHUB]);
const CIERRA_EN_GITHUB = declaracionDeCierre(PALABRAS_DE_GITHUB);

function declaracionDeCierre(palabras) {
  return new RegExp(String.raw`\b(?:${palabras.join('|')})\s+#(\d+)`, 'gi');
}

// Se ejecuta SOLO cuando se invoca como guion. Importarlo no hace nada, que es lo que
// permite a su autoprueba leer `RUTAS_DE_CODIGO` de aqui en vez de copiarla (#45).
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  principal();
}

function principal() {
  const opciones = leerOpciones(process.argv.slice(2));

  const cuerpo = opciones.cuerpo
    ? readFileSync(opciones.cuerpo, 'utf8')
    : (process.env.KAMAYUK_CUERPO_DEL_PR ?? '');

  /* Sin duplicados: un cuerpo que explica lo que hace nombra el mismo issue varias veces, y los
     tres mensajes de aqui abajo lo listan. Medido en el CI de este mismo PR antes de arreglarlo:
     «Cierra #130, #130, #130, #130 y no toca codigo de produccion». No cambia lo que se decide
     —filtrar y comprobar sobre repetidos da lo mismo—, solo lo que se lee. */
  const issues = [...new Set([...cuerpo.matchAll(CIERRA)].map((coincidencia) => coincidencia[1]))];
  if (issues.length === 0) {
    console.log('El PR no declara que cierre ningun issue: no hay fila que exigir.');
    process.exit(0);
  }

  /* Lo SEGUNDO que comprueba esta guarda (#130), y mira el CUERPO y no el diff: que la palabra
     con que el PR declara cada cierre sea de las que GitHub entiende.

     Se hace por ISSUE y no por cuerpo, porque un cuerpo puede declarar dos y acertar con uno:
     «Cierra #711 … Closes #712» cierra #712 al mezclar y deja #711 abierto. Un aviso que dijera
     «el cuerpo trae alguna palabra buena» daria ese caso por bueno.

     Y va ANTES del filtro de `RUTAS_DE_CODIGO` a proposito: quedarse sin auto-cierre no depende
     de que archivos toque el PR. Heredar aqui la condicion que la FILA si necesita dejaria el
     agujero abierto de par en par para los PR de solo documentacion —mismo defecto, otra ropa—,
     y seria una guarda con un punto ciego que nada justifica. */
  const enGitHub = new Set([...cuerpo.matchAll(CIERRA_EN_GITHUB)].map((c) => c[1]));
  const sinAutocierre = issues.filter((numero) => !enGitHub.has(numero));
  if (sinAutocierre.length > 0) {
    console.error('');
    console.error('FALLO: este PR declara un cierre con una palabra que GitHub no entiende.');
    console.error('');
    for (const numero of sinAutocierre) {
      console.error(`  · Al mezclar este PR, #${numero} NO se va a cerrar solo.`);
    }
    console.error('');
    console.error('  GitHub auto-cierra con estas palabras, y con ninguna mas:');
    console.error(`    ${PALABRAS_DE_GITHUB.join(', ')}`);
    console.error('');
    console.error('  Medido en la tanda del 2026-09-12: los cinco PR que decian «Closes #N»');
    console.error('  cerraron su issue al mezclar y el que decia «Cierra #N» no, y nadie se');
    console.error('  entero hasta la auditoria — PR mezclado, CI verde, fila escrita, issue');
    console.error('  abierto. El modo de fallo se parece al exito, y por eso esto es rojo y no');
    console.error('  una linea mas en un registro que solo se lee cuando algo ya esta rojo.');
    console.error('');
    console.error('  ARREGLO: escribe en el cuerpo del PR');
    for (const numero of sinAutocierre) {
      console.error(`    Closes #${numero}`);
    }
    console.error('');
    console.error('  Y SI NO QUIERES QUE SE CIERRE SOLO, entonces no lo declares: «Ref #N» o');
    console.error('  «Parte de #N» no disparan nada, ni aqui ni en GitHub. Lo unico que esto');
    console.error('  impide es el tercer caso —declarar el cierre y no cerrar—, que es el que');
    console.error('  miente. Que el PR se quede sin auto-cierre pasa a ser una decision.');
    console.error('');
    console.error('  EL IDIOMA NO CAMBIA: «Cierra #N» se sigue reconociendo aqui, y la fila, el');
    console.error('  commit y el resto del cuerpo siguen en castellano (CLAUDE.md §Idioma). Lo');
    console.error('  que hace falta es UNA linea que GitHub sepa leer, no un cuerpo en ingles.');
    console.error('');
    console.error('  Editar el cuerpo del PR relanza esta comprobacion sin empujar un commit:');
    console.error('  `registro.yml` escucha `edited` desde #57, justo para los rojos de cuerpo.');
    process.exit(1);
  }

  const archivos = opciones.archivos
    ? lineas(readFileSync(opciones.archivos, 'utf8'))
    : lineas(git(['diff', '--name-only', `${opciones.base}...HEAD`]));

  const deCodigo = archivos.filter((ruta) => RUTAS_DE_CODIGO.some((patron) => patron.test(ruta)));
  if (deCodigo.length === 0) {
    console.log(
      `Cierra #${issues.join(', #')} y no toca codigo de produccion: la fila no se exige.`,
    );
    process.exit(0);
  }

  const anadido = opciones.anadido
    ? readFileSync(opciones.anadido, 'utf8')
    : git(['diff', `${opciones.base}...HEAD`, '--', ...DONDE_VIVE_LA_FILA])
        .split('\n')
        .filter((linea) => linea.startsWith('+') && !linea.startsWith('+++'))
        .join('\n');

  const sinFila = issues.filter((numero) => !nombra(anadido, numero));
  if (sinFila.length > 0) {
    console.error('');
    console.error(
      `FALLO: falta la fila de «Verificar antes de afirmar» en ${DONDE_VIVE_LA_FILA[0]}.`,
    );
    console.error('');
    for (const numero of sinFila) {
      console.error(
        `  · Este PR cierra #${numero} y no lo nombra ninguna FILA nueva de ` +
          `${DONDE_VIVE_LA_FILA.join(' ni de ')}.`,
      );
    }
    console.error('');
    console.error('  Esa tabla es la memoria del proyecto: cada issue deja ahi que se');
    console.error('  implemento y COMO SE DEMOSTRO QUE LA VERIFICACION PUEDE FALLAR. Una fila');
    console.error('  que no se escribe es una leccion que el siguiente vuelve a descubrir');
    console.error('  ejecutando.');
    console.error('');
    console.error('  Lo que se comprueba aqui es solo que la fila EXISTA. Que diga la verdad');
    console.error('  —que la mutacion sea real y las cifras cuadren— lo lee la revision.');
    console.error('');
    console.error('  Y tiene que ser una FILA de la tabla —una linea que empiece por «|»—: una');
    console.error('  cabecera o un parrafo que citen el issue no cuentan. Esa era la forma de');
    console.error('  salir en verde sin una sola fila escrita.');
    console.error('');
    console.error(`  Archivos de codigo en este cambio: ${deCodigo.length}`);
    console.error(`    ${deCodigo.slice(0, 5).join('\n    ')}`);
    process.exit(1);
  }

  console.log(`Cada issue que este PR cierra tiene su fila: #${issues.join(', #')}.`);
}

// ---------------------------------------------------------------------------

/**
 * Si ese texto trae una FILA que nombre al issue —como tal y no como parte de otro numero—.
 *
 * **Que sea una fila es la mitad que faltaba, y hasta el 2026-09-12 no estaba.** Bastaba con que
 * `#N` apareciera en cualquier linea anadida, y eso **lo satisface una cabecera o un parrafo**.
 *
 * Lo destaparon TRES carriles a la vez al mudar el registro a `docs/agent/HISTORY.md`
 * (`infrastructure`#114), y este repositorio fue uno de los tres: la cabecera del archivo nuevo
 * citaba el issue que traia la mudanza, asi que la rotura de control —quitar la fila y enmendar
 * el commit— salio **VERDE**, contestando «Cada issue que este PR cierra tiene su fila» con cero
 * filas dentro. Los tres lo rodearon igual: escribiendo una cabecera que no cita su propio issue
 * y anotandolo. Eso es una costumbre, y una costumbre no es una guarda — el dia que alguien
 * escriba en la cabecera «esto se mudo por #N», la guarda vuelve a dar por buena una tabla sin
 * tocar.
 *
 * Asi que la exigencia se escribe donde se puede sostener: **una fila de una tabla de Markdown
 * empieza por `|`**. El `+` del diff se quita antes de mirar, porque lo que llega aqui son las
 * lineas anadidas del cambio.
 */
function nombra(texto, numero) {
  const cita = new RegExp(`#${numero}(?![0-9])`);
  return texto
    .split('\n')
    .map((linea) => linea.replace(/^\+/, '').trim())
    .some((linea) => linea.startsWith('|') && cita.test(linea));
}

function lineas(texto) {
  return texto
    .split('\n')
    .map((linea) => linea.trim())
    .filter((linea) => linea.length > 0);
}

function git(argumentos) {
  return execFileSync('git', argumentos, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

function leerOpciones(argumentos) {
  const opciones = { base: 'origin/main' };
  for (let i = 0; i < argumentos.length; i += 2) {
    const nombre = argumentos[i];
    const valor = argumentos[i + 1];
    if (valor === undefined) {
      throw new Error(`Falta el valor de ${nombre}`);
    }
    if (!['--base', '--cuerpo', '--archivos', '--anadido'].includes(nombre)) {
      throw new Error(`Opcion desconocida: ${nombre}`);
    }
    opciones[nombre.slice(2)] = valor;
  }
  return opciones;
}
