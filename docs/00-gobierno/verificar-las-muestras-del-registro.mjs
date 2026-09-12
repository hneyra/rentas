/* Comprueba que verificar-fila-del-registro.mjs muerde, y que no muerde de mas.

   Una guarda que no puede fallar no protege nada; y una que grita siempre acaba
   esquivada, que en una convencion de proceso es peor todavia — el peaje se aprende a
   rodear y la tabla se queda igual de vacia.

   Asi que se corre la comprobacion contra nueve situaciones fabricadas, cinco que tiene
   que rechazar y cuatro que tiene que dejar pasar, y se exige que el rechazo **nombre el
   issue**: rechazar por el motivo equivocado seria pasar por casualidad.

   La ultima en llegar es del tercer tiempo de `infrastructure`#114 y fija lo que la mudanza
   del registro destapo en tres repositorios a la vez: **una cabecera o un parrafo que citen
   el issue no valen como fila**. Hasta entonces valian, y con eso un PR podia salir en verde
   con la tabla intacta.

   Las dos ultimas en llegar son de #45 y van EN PAREJA: una toca `infrastructure/src/`
   —el descriptor de despliegue— cerrando un issue sin dejar fila y tiene que salir roja;
   la otra toca `infrastructure/` FUERA de `src/` —su prueba y su README— y tiene que
   seguir pasando. Sin la segunda, «que el descriptor cuente» se podria satisfacer
   declarando que todo cuenta, y una guarda que grita en cada PR se acaba apagando.

   Uso: node docs/00-gobierno/verificar-las-muestras-del-registro.mjs
*/

import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { RUTAS_DE_CODIGO } from './verificar-fila-del-registro.mjs';

const COMPROBACION = fileURLToPath(
  new URL('./verificar-fila-del-registro.mjs', import.meta.url),
);

/** Una fila de la tabla, como la que este mismo PR anade. */
const FILA = '| Lo que se verifico (#711, 3 pruebas) | La rotura | El rojo |';

const CASOS = [
  {
    nombre: 'cierra un issue, toca backend y NO deja fila',
    cuerpo: 'Cierra #711.\n\nLo de siempre.',
    archivos: ['backend/kamayuk-rentas-nucleo/src/main/java/kamayuk/rentas/nucleo/Algo.java'],
    anadido: '',
    esperado: 'rojo',
    dice: '#711',
  },
  {
    nombre: 'la fila que anade nombra a OTRO issue',
    cuerpo: 'Closes #711',
    archivos: ['frontend/src/modulos/rentas/Rentas.tsx'],
    anadido: '+| Otra cosa (#712) | … | … |',
    esperado: 'rojo',
    dice: '#711',
  },
  {
    nombre: 'un numero que solo CONTIENE al del issue no cuenta como su fila',
    cuerpo: 'Cierra #71',
    archivos: ['infra/src/componentes/index.ts'],
    anadido: '+| Una fila cualquiera (#711) | … | … |',
    esperado: 'rojo',
    dice: '#71',
  },
  {
    // `infrastructure`#114, tercer tiempo. Hasta el 2026-09-12 `nombra()` buscaba `#N` en
    // CUALQUIER linea anadida, y eso lo satisface una cabecera o un parrafo. Lo destaparon tres
    // carriles a la vez al mudar el registro: la cabecera del archivo nuevo citaba el issue de
    // la mudanza y la rotura de control —quitar la fila— salia VERDE. Con `nombra()` devuelta a
    // su forma de antes, esta muestra pasa a verde: ese es el rojo que demuestra el arreglo.
    nombre: 'una cabecera o un parrafo que citen el issue NO valen como fila',
    cuerpo: 'Cierra #711.',
    archivos: ['backend/kamayuk-rentas-nucleo/src/main/java/kamayuk/rentas/nucleo/Algo.java'],
    anadido: '+# Registro\n+\n+Se mudo aqui por #711, y esto no es una fila.',
    esperado: 'rojo',
    dice: '#711',
  },
  {
    // #45. El descriptor de despliegue decide que corre en la municipalidad, y hasta #45
    // esta guarda no lo miraba: el mismo cambio salia `exit=0` aqui y `exit=1` en los
    // otros tres repositorios. Con `RUTAS_DE_CODIGO` devuelta a la lista de antes, esta
    // muestra pasa a VERDE — y ese es el rojo que demuestra que el arreglo sirve.
    nombre: 'cierra un issue, toca el descriptor de despliegue y NO deja fila',
    cuerpo: 'Cierra #44.\n\nEl despliegue de `rentas-web`.',
    archivos: ['infrastructure/src/descriptor.ts'],
    anadido: '',
    esperado: 'rojo',
    dice: '#44',
  },
  {
    // El contraste de la de arriba (#45 AC-4), y no es el mismo que el de `docs/`: estos
    // dos archivos viven DENTRO de `infrastructure/`, asi que separan «`src/` es codigo»
    // de «todo `infrastructure/` es codigo». Sin el, la correccion se podria satisfacer
    // declarando que todo cuenta, y entonces la guarda grita en cada PR y se apaga (#437).
    nombre: 'toca infrastructure/ FUERA de src/ —su prueba y su README— y no exige fila',
    cuerpo: 'Cierra #44.',
    archivos: ['infrastructure/verificaciones/descriptor.test.ts', 'infrastructure/README.md'],
    anadido: '',
    esperado: 'verde',
  },
  {
    // #74. El manifiesto del frontend decide que viaja al bundle, y hasta entonces no estaba
    // en `RUTAS_DE_CODIGO`: el PR que enchufo los tres `link:` al clon hermano habria salido
    // verde sin fila. Con la ruta devuelta a la lista de antes, esta muestra pasa a VERDE — y
    // ese es el rojo que demuestra que el arreglo sirve.
    nombre: 'cierra un issue, toca el manifiesto del frontend y NO deja fila',
    cuerpo: 'Cierra #74.\n\nEl primer clon hermano.',
    archivos: ['frontend/package.json'],
    anadido: '',
    esperado: 'rojo',
    dice: '#74',
  },
  {
    // El contraste, y es el que impide que la correccion se satisfaga declarando que todo
    // `frontend/` cuenta: el candado y las barreras no son codigo de produccion.
    nombre: 'toca el candado del frontend y sus barreras, y no exige fila',
    cuerpo: 'Cierra #74.',
    archivos: ['frontend/yarn.lock', 'frontend/verificaciones/enlace.ts'],
    anadido: '',
    esperado: 'verde',
  },
  {
    // #75. El `Dockerfile` decide que se publica, y el compose como se levanta la instalacion.
    nombre: 'cierra un issue, toca el Dockerfile de la interfaz y NO deja fila',
    cuerpo: 'Cierra #75.\n\nLa imagen alcanza al clon hermano.',
    archivos: ['frontend/Dockerfile'],
    anadido: '',
    esperado: 'rojo',
    dice: '#75',
  },
  {
    nombre: 'cierra un issue, toca el compose y NO deja fila',
    cuerpo: 'Cierra #75.',
    archivos: ['despliegue/compose.yaml'],
    anadido: '',
    esperado: 'rojo',
    dice: '#75',
  },
  {
    nombre: 'cierra un issue, toca backend y SI deja su fila',
    cuerpo: 'Cierra #711.',
    archivos: ['backend/kamayuk-rentas-nucleo/src/main/java/kamayuk/rentas/nucleo/Algo.java'],
    anadido: `+${FILA}`,
    esperado: 'verde',
  },
  {
    nombre: 'cierra un issue y NO toca codigo de produccion',
    cuerpo: 'Cierra #711.',
    archivos: [
      'docs/00-gobierno/algo.md',
      'backend/kamayuk-rentas-nucleo/src/test/java/kamayuk/rentas/nucleo/AlgoTest.java',
    ],
    anadido: '',
    esperado: 'verde',
  },
  {
    nombre: 'toca backend y no declara que cierre nada',
    cuerpo: 'Un arreglo suelto, sin issue.',
    archivos: ['backend/kamayuk-rentas-nucleo/src/main/java/kamayuk/rentas/nucleo/Algo.java'],
    anadido: '',
    esperado: 'verde',
  },
];

/* Y la direccion que faltaba, que es de #45: TODO patron de `RUTAS_DE_CODIGO` tiene que
   tener al menos una muestra ROJA que lo ejerza.

   Sin ella, quitar una muestra no pone nada rojo: **deja de comprobarse, en verde**.
   Medido con la de `infrastructure/src/` fuera, la autoprueba decia «Las 7 muestras se
   comportan como deben» y salia con 0 — y peor, su contraste seguia ahi certificando que
   `infrastructure/` fuera de `src/` no cuenta, mientras nadie comprobaba que `src/` si.
   Es la leccion de «una regla sin muestra no protege nada» por el eje de las rutas.

   La lista se LEE del guion y no se copia aqui: una copia se queda vieja sola y entonces
   esto certificaria una lista que ya no es la que corre. Y si viniera vacia, lo de abajo
   se cumpliria sobre el conjunto vacio, asi que se dice y se falla. */
if (RUTAS_DE_CODIGO.length === 0) {
  console.error('MAL: `RUTAS_DE_CODIGO` se leyo vacia, asi que esto no mediria nada.');
  process.exit(2);
}

const sinMuestra = RUTAS_DE_CODIGO.filter(
  (patron) =>
    !CASOS.some(
      (caso) => caso.esperado === 'rojo' && caso.archivos.some((ruta) => patron.test(ruta)),
    ),
);
if (sinMuestra.length > 0) {
  console.error('');
  console.error('MAL: hay rutas de codigo de produccion sin muestra que las ejerza.');
  for (const patron of sinMuestra) {
    console.error(`  · ${patron} no lo toca ninguna muestra que espere rojo.`);
  }
  console.error('');
  console.error('  Una ruta sin muestra no falla: DEJA DE COMPROBARSE, en verde. Anade una');
  console.error('  muestra que toque esa ruta cerrando un issue y sin dejar fila.');
  console.error('');
  process.exit(1);
}

const carpeta = mkdtempSync(join(tmpdir(), 'sgtm-711-'));
let fallos = 0;

for (const caso of CASOS) {
  const cuerpo = join(carpeta, 'cuerpo.txt');
  const archivos = join(carpeta, 'archivos.txt');
  const anadido = join(carpeta, 'anadido.txt');
  writeFileSync(cuerpo, caso.cuerpo);
  writeFileSync(archivos, caso.archivos.join('\n'));
  writeFileSync(anadido, caso.anadido);

  let salida = '';
  let codigo = 0;
  try {
    salida = execFileSync(
      'node',
      [COMPROBACION, '--cuerpo', cuerpo, '--archivos', archivos, '--anadido', anadido],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
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
  if (esperabaRojo && !salida.includes(caso.dice)) {
    console.error(`MAL: «${caso.nombre}» se puso rojo sin nombrar ${caso.dice}.`);
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
console.log(
  `\nLas ${CASOS.length} muestras se comportan como deben, y las ` +
    `${RUTAS_DE_CODIGO.length} rutas`,
);
console.log('declaradas como codigo de produccion tienen quien las ejerza.');
