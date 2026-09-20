import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * **Que conectores hay, leido del disco y no de una lista escrita a mano** (#277).
 *
 * <h2>El hueco que lo trae, y por que no daba rojo</h2>
 *
 * `la-lectura-declara-lo-que-la-operacion-publica.test.ts` enumeraba sus conectores a mano
 * —siete modulos— y la lista **se quedo vieja**: le faltaba `valores`, que existe desde que se
 * separo `val-tip`. Y no lo dijo nadie, porque el unico tipo que `valores.ts` pide
 * —`PrescripcionDeclarada`— lo pide **tambien** `coactiva.ts`, que si estaba en la lista: el
 * centinela seguia cuadrando y la barrera estaba **apagada sin que nadie la hubiera borrado**. El
 * dia que un conector no nombrado pidiera un tipo que nadie mas pide, su forma dejaba de
 * compararse contra el contrato en silencio.
 *
 * <h2>Por que vive aparte</h2>
 *
 * Porque lo mismo lo necesitan dos guardas —#261 ya lo derivaba del disco, y #239 lo escribia a
 * mano—, y dos copias de una derivacion son dos copias que se separan. Una sola, y las dos miran
 * el mismo arbol. Es el precedente de `contraste.ts`, que tambien existe para que dos guardas
 * midan lo mismo con el mismo codigo.
 *
 * <h2>Lo que esto NO sustituye</h2>
 *
 * `la-hoja-declara-la-ruta-que-su-conector-pide.test.ts` escribe su lista a mano **a proposito y
 * con su motivo**: alli un archivo que dejara de existir tiene que salir rojo, y una derivacion
 * lo haria desaparecer en silencio. Son dos preguntas distintas —«barrelos todos» y «estos nueve
 * siguen estando»— y por eso esta no se le impone.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');

/** El reparto: el archivo que ata cada hoja con su conector, e importa el de cada modulo. */
const EL_REPARTO = join(FRONTEND, 'src/datos/conectores.ts');

/** El arbol donde vive un conector por modulo. */
const EL_ARBOL = join(FRONTEND, 'src/datos/conectores');

/**
 * **Los modulos que el arbol tiene**: los `*.ts` de `src/datos/conectores/`, sin pruebas ni
 * muestras.
 *
 * Las `*DeMuestra.ts` quedan fuera porque no piden nada: son respuestas construidas desde el
 * contrato, y quien las vigila es el `CAPTURAS` de `camino-a-la-api.test.ts`.
 */
export function modulosDelArbol(): readonly string[] {
  return readdirSync(EL_ARBOL)
    .filter((uno) => uno.endsWith('.ts') && !uno.includes('.test.') && !uno.includes('DeMuestra'))
    .map((uno) => uno.replace(/\.ts$/, ''))
    .sort();
}

/**
 * **Los modulos que el reparto importa**, leidos de sus `import … from './conectores/<x>.ts'`.
 *
 * Es la segunda derivacion, y existe para que la primera no se pueda vaciar en silencio: si el
 * filtro de arriba dejara de casar —una extension nueva, un `.mts`, un subdirectorio—, el
 * conjunto encogeria y **ninguna comprobacion sobre el conjunto vacio daria rojo**. Cruzadas las
 * dos, el encogimiento se nombra.
 */
export function modulosQueElRepartoImporta(): readonly string[] {
  const texto = readFileSync(EL_REPARTO, 'utf8');
  const nombres = new Set<string>();
  for (const uno of texto.matchAll(/from\s+'\.\/conectores\/([\w-]+)\.ts'/g)) {
    nombres.add(uno[1] ?? '');
  }
  return [...nombres].sort();
}

/** Los conectores de verdad: el reparto mas un archivo por modulo, en rutas absolutas. */
export function fuentesDeLosConectores(): readonly string[] {
  return [EL_REPARTO, ...modulosDelArbol().map((uno) => join(EL_ARBOL, `${uno}.ts`))];
}
