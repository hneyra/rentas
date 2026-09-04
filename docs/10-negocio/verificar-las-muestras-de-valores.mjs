/* Comprueba que verificar-valores-normativos.mjs muerde.

   Una comprobacion que no puede fallar no protege nada, y la de E-3 tiene un modo
   de fallo particularmente comodo: con el directorio vacio pasa en verde, que es
   lo correcto —la barrera llega antes que la transcripcion— pero indistinguible
   de una comprobacion rota. Esto la distingue.

   `_muestras/` tiene un archivo por prohibicion, valido en todo salvo en la que
   viola. Aqui se corre la comprobacion contra cada uno y se exige que la rechace
   **nombrando esa prohibicion y no otra**: rechazarla por el motivo equivocado
   seria pasar por casualidad.

   Y `en-regla/` es la novena muestra, la que va al reves: un archivo correcto
   tiene que pasar. Sin ella, una comprobacion que rechazara todo pasaria esto
   entero.

   Uso: node docs/10-negocio/verificar-las-muestras-de-valores.mjs
*/

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const raiz = new URL('../../', import.meta.url);
const COMPROBACION = fileURLToPath(
  new URL('docs/10-negocio/verificar-valores-normativos.mjs', raiz),
);
const MUESTRAS = fileURLToPath(new URL('docs/10-negocio/valores-normativos/_muestras/', raiz));
const SIN_MIGRACIONES = `${MUESTRAS}_sin-migraciones`;

/** Cada muestra, y el trozo del mensaje que la comprobacion tiene que dar. */
const CASOS = [
  ['transcriptor-igual-a-verificador', 'transcribió y verificó'],
  ['sin-fecha-de-publicacion', 'no lleva fecha AAAA-MM-DD'],
  ['sin-articulo', '«Artículo» sin rellenar'],
  ['verificado-sin-verificador', 'está VERIFICADO y «Verificó» no nombra a nadie'],
  ['fila-que-no-existe', 'y esa fila no existe'],
  ['fila-reclamada-dos-veces', 'ya la cierra'],
  ['sin-la-seccion-de-que-no-cabe', 'le falta la sección «3. Qué no cabe hoy»'],
  ['cabecera-incompleta', 'le falta el campo «Filas de NEG-02 §2»'],
];

const fallos = [];

/** El mensaje de la comprobacion, sangrado para que se lea dentro del fallo. */
function sangrado(salida) {
  return salida.trim().split('\n').join('\n                ');
}

/** Corre la comprobacion y devuelve {codigo, salida}. */
function comprobar(documentos, migraciones) {
  try {
    const salida = execFileSync(
      process.execPath,
      [COMPROBACION, '--directorio', documentos, '--migraciones', migraciones],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    return { codigo: 0, salida };
  } catch (fallo) {
    return { codigo: fallo.status ?? 1, salida: `${fallo.stdout ?? ''}${fallo.stderr ?? ''}` };
  }
}

for (const [muestra, esperado] of CASOS) {
  const { codigo, salida } = comprobar(`${MUESTRAS}${muestra}`, SIN_MIGRACIONES);
  if (codigo === 0) {
    fallos.push(`La muestra «${muestra}» NO se detecta: la comprobación pasó en verde.`);
  } else if (!salida.includes(esperado)) {
    fallos.push(
      `La muestra «${muestra}» se rechaza por el motivo equivocado.\n` +
        `      esperaba: ${esperado}\n      dijo:     ${sangrado(salida)}`,
    );
  }
}

// La carga en la base: el cuarto criterio de #200, que no es un documento sino un INSERT.
{
  const { codigo, salida } = comprobar(
    `${MUESTRAS}carga-en-la-base/documentos`,
    `${MUESTRAS}carga-en-la-base/migracion`,
  );
  if (codigo === 0) {
    fallos.push(
      'La muestra «carga-en-la-base» NO se detecta: un INSERT de valores normativos pasó en verde.',
    );
  } else if (!salida.includes('carga valores en «parametro_tributario»')) {
    fallos.push(
      `La muestra «carga-en-la-base» se rechaza por el motivo equivocado:\n      ${salida.trim()}`,
    );
  }
}

// Y la que va al reves.
{
  const { codigo, salida } = comprobar(`${MUESTRAS}en-regla`, SIN_MIGRACIONES);
  if (codigo !== 0) {
    fallos.push(`La muestra «en-regla» está bien y se rechaza igual:\n      ${salida.trim()}`);
  }
}

if (fallos.length > 0) {
  console.error('Las muestras de valores normativos no se comportan como deben.\n');
  for (const fallo of fallos) console.error(`  - ${fallo}`);
  process.exit(1);
}

console.log(
  `Las ${CASOS.length + 1} prohibiciones de valores normativos muerden, y una en regla pasa.`,
);
