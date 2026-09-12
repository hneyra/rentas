/* Comprueba que un PR que cierra un issue deja su fila en «Verificar antes de afirmar».

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

/** Como se declara que un PR cierra un issue. GitHub admite estas y alguna mas. */
const CIERRA = /\b(?:cierra|closes?|close|fixes?|fix|resuelve|resolves?)\s+#(\d+)/gi;

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

  const issues = [...cuerpo.matchAll(CIERRA)].map((coincidencia) => coincidencia[1]);
  if (issues.length === 0) {
    console.log('El PR no declara que cierre ningun issue: no hay fila que exigir.');
    process.exit(0);
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
