/* Comprueba que verificar-publicacion.mjs muerde (#188, #247 §4).

   Es la hermana de `verificar-las-muestras-de-valores.mjs`, y existe por el mismo
   motivo: el derivado publicable es hoy correcto, asi que la comprobacion pasa en
   verde, y eso es indistinguible de una comprobacion rota. Esto lo distingue.

   `valores-normativos/publicacion/_muestras/` tiene un CSV por prohibicion, igual al
   archivo real salvo en la que viola. Aqui se corre la comprobacion contra cada uno y
   se exige que lo rechace **nombrando esa prohibicion y no otra**: rechazarlo por el
   motivo equivocado seria pasar por casualidad.

   Y el archivo real es la muestra que va al reves: tiene que pasar. Sin ella, una
   comprobacion que rechazara todo pasaria esto entero.

   Uso: node docs/10-negocio/verificar-las-muestras-de-publicacion.mjs
*/

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const raiz = new URL('../../', import.meta.url);
const COMPROBACION = fileURLToPath(new URL('docs/10-negocio/verificar-publicacion.mjs', raiz));
const MUESTRAS = fileURLToPath(
  new URL('docs/10-negocio/valores-normativos/publicacion/_muestras/', raiz),
);
const REAL = fileURLToPath(
  new URL('docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv', raiz),
);

/** Cada muestra, y el trozo del mensaje que la comprobacion tiene que dar. */
const CASOS = [
  ['cifra-que-no-esta-en-la-norma', 'no aparece en «uit.md»'],
  ['estado-no-verificado', 'solo se publica desde VERIFICADO'],
  ['una-sola-firma', 'dos firmas distintas'],
  ['firma-que-el-corpus-no-dice', 'La firma que se publica es la del corpus'],
  ['fuente-inventada', 'no aparece en «uit.md»'],
  ['articulo-que-no-es', 'cita el articulo «99»'],
  ['texto-reescrito', 'no esta en «predial-deducciones.md»'],
  ['archivo-que-no-existe', 'que no existe en'],
  ['llave-repetida', 'repite la llave'],
  // Las tres de #192, por la columna `valor_maquina`. La segunda es la que importa:
  // veinte dias habiles y veinte calendario no son lo mismo, y de esa diferencia
  // depende si un expediente coactivo nacio antes de tiempo.
  ['cifra-que-el-plazo-no-dice', '«5 ANIOS» publica 5 y la fila declara'],
  ['unidad-que-no-es-la-de-la-norma', 'dice «DIAS_CALENDARIO» y el texto verbatim no dice'],
  ['plazo-sin-forma-de-maquina', 'no trae valor_maquina'],
];

const fallos = [];

function comprobar(csv) {
  try {
    const salida = execFileSync(process.execPath, [COMPROBACION, '--csv', csv], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { codigo: 0, salida };
  } catch (fallo) {
    return { codigo: fallo.status ?? 1, salida: `${fallo.stdout ?? ''}${fallo.stderr ?? ''}` };
  }
}

for (const [muestra, esperado] of CASOS) {
  const { codigo, salida } = comprobar(`${MUESTRAS}${muestra}.csv`);
  if (codigo === 0) {
    fallos.push(`La muestra «${muestra}» NO se detecta: la comprobación pasó en verde.`);
  } else if (!salida.includes(esperado)) {
    fallos.push(
      `La muestra «${muestra}» se rechaza por el motivo equivocado.\n` +
        `      esperaba: ${esperado}\n      dijo:     ${salida.trim().split('\n').join('\n                ')}`,
    );
  }
}

{
  const { codigo, salida } = comprobar(REAL);
  if (codigo !== 0) {
    fallos.push(`El derivado real está bien y se rechaza igual:\n      ${salida.trim()}`);
  }
}

if (fallos.length > 0) {
  console.error('Las muestras del derivado publicable no se comportan como deben.\n');
  for (const fallo of fallos) console.error(`  - ${fallo}`);
  process.exit(1);
}

console.log(`Las ${CASOS.length} prohibiciones del derivado publicable muerden, y el real pasa.`);
