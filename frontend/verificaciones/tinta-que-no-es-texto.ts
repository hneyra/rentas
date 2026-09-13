import { readdirSync } from 'node:fs';
import { join } from 'node:path';

import ts from 'typescript';

/**
 * **El token que el artboard declara NO-color-de-texto, y quien lo usa como texto** (#140).
 *
 * <h2>Vive aparte de su prueba, y es lo que la hace demostrable</h2>
 *
 * Es la misma decision que `enlace.ts`: una funcion pura sobre `(ruta, fuente)` se puede ejercer
 * sobre fuentes **inventadas** —una que viola la regla, otra que la cumple— sin fabricar archivos
 * en el arbol. Es el equivalente de una `muestras/` para una guarda que no mira disposicion sino
 * sintaxis, y sin el coste de un directorio que `tsconfig` y `eslint.config` tendrian que
 * exceptuar. Lo que en `verificaciones/muestras/` es un archivo, aqui es una cadena.
 *
 * <h2>El nombre del token NO se escribe: se lee del artboard</h2>
 *
 * `diseno/rentas-tokens.css` —que es la hoja de tokens del artboard V8, vendorizada aqui y
 * declarada en `artboards.ts`— dice cual de las cuatro tintas no es texto y **por que**. La
 * libreria repite la misma frase en mayusculas en su propia hoja. Escribir «tinta-4» aqui a mano
 * seria una tercera copia, y la copia que se queda vieja es la que alguien usa: el dia que el
 * artboard mueva esa condicion a otro token, esta guarda seguiria vigilando el de ayer **en
 * verde**.
 *
 * <h2>Por que hace falta un arbol de sintaxis y no basta un `grep`</h2>
 *
 * Porque la regla no es «no escribas esta clase»: es «no la escribas sobre algo que se lee». Los
 * usos legitimos existen y son de verdad —el trazo de un icono, el separador de una miga— y todos
 * llevan `aria-hidden`. Un `grep` no distingue los dos casos, asi que solo puede prohibirlo todo
 * —y entonces hay que exceptuar a mano, que es una lista que nadie mantiene— o no prohibir nada.
 * Con el arbol, la clase se sigue hasta el elemento sobre el que cae y la pregunta se contesta.
 *
 * <h2>Y por que es CERRADA: lo que no se puede demostrar, se senala</h2>
 *
 * Una clase que no cuelga de un `className` —la que viaja dentro de un mapa de `classNames`, o la
 * que se guarda en una constante suelta— no permite saber sobre que elemento cae. Eso no se deja
 * pasar: se senala diciendo exactamente eso. Abierta, bastaria sacar la cadena del JSX para que la
 * guarda dejara de verla, que es la forma mas comoda de saltarse una regla sin desactivarla.
 */

/** Un token que el artboard declara que NO se usa como texto, con la clase que lo pinta. */
export interface TokenQueNoEsTexto {
  /** El nombre del token, sin los dos guiones: `tinta-4`. */
  readonly token: string;
  /** Su valor en el artboard: `#93a3af`. */
  readonly valor: string;
  /** La utilidad de Tailwind que lo aplica como color de texto: `text-tinta-4`. */
  readonly clase: string;
  /** La frase del artboard que lo declara. Es la que se cita en el rojo. */
  readonly porQue: string;
}

/** Los bloques de comentario de una hoja de CSS, con sus tildes y sus saltos de linea. */
const comentariosDe = (hoja: string): string[] =>
  [...hoja.matchAll(/\/\*([\s\S]*?)\*\//g)].map(([, cuerpo]) => (cuerpo ?? '').trim());

/** El valor de un token declarado en la hoja: `--tinta-4: #93a3af;` -> `#93a3af`. */
export function valorDelToken(hoja: string, token: string): string | null {
  const encontrado = new RegExp(`--${token}\\s*:\\s*(#[0-9a-f]{6})\\s*;`, 'i').exec(hoja);
  return encontrado?.[1]?.toLowerCase() ?? null;
}

/**
 * Los tokens que la hoja del artboard declara que NO son color de texto.
 *
 * Se buscan por la frase que el artboard escribe —«NO se usa como texto»— y no por el nombre del
 * token. Devuelve una lista y no uno solo a proposito: si el artboard declarara dos, la guarda
 * tiene que vigilar los dos, y si no declara ninguno el centinela se entera en vez de quedarse
 * vigilando el vacio.
 */
export function losQueNoSonTexto(hoja: string): TokenQueNoEsTexto[] {
  const salida: TokenQueNoEsTexto[] = [];
  for (const comentario of comentariosDe(hoja)) {
    if (!/NO se usa como texto/i.test(comentario)) continue;
    for (const [, token] of comentario.matchAll(/--([a-z0-9-]+)/gi)) {
      const nombre = (token ?? '').toLowerCase();
      const valor = valorDelToken(hoja, nombre);
      if (valor === null) continue;
      salida.push({
        token: nombre,
        valor,
        clase: `text-${nombre}`,
        porQue: comentario.replace(/\s+/g, ' '),
      });
    }
  }
  return salida;
}

/** Un uso de la clase que no se pudo demostrar decorativo, con donde esta y por que no vale. */
export interface Hallazgo {
  readonly ruta: string;
  readonly linea: number;
  /** El elemento sobre el que cae la clase, o lo que se encontro en su lugar. */
  readonly sobre: string;
  readonly porQue: string;
}

/**
 * Una cadena de clases usa la utilidad, mirando token a token.
 *
 * Se compara el token ENTERO tras quitarle sus variantes —`hover:`, `md:`, `[&>button]:`— porque
 * `includes` daria por usado `text-tinta-4` dentro de `text-tinta-40` y dentro de cualquier
 * comentario que la nombre.
 */
export function usaLaClase(texto: string, clase: string): boolean {
  return texto
    .split(/\s+/)
    .some((token) => token.slice(token.lastIndexOf(':') + 1) === clase);
}

/** Las cadenas literales de un arbol: comillas y plantillas. El texto JSX no, que es prosa. */
function esCadena(nodo: ts.Node): nodo is ts.StringLiteralLike | ts.TemplateLiteralToken {
  return (
    ts.isStringLiteral(nodo) ||
    ts.isNoSubstitutionTemplateLiteral(nodo) ||
    ts.isTemplateHead(nodo) ||
    ts.isTemplateMiddle(nodo) ||
    ts.isTemplateTail(nodo)
  );
}

/** El `JsxAttribute` del que cuelga una cadena, o `null` si no cuelga de ninguno. */
function atributoQueLaLleva(nodo: ts.Node): ts.JsxAttribute | null {
  for (let actual: ts.Node | undefined = nodo.parent; actual !== undefined; actual = actual.parent) {
    if (ts.isJsxAttribute(actual)) return actual;
    if (ts.isSourceFile(actual)) return null;
  }
  return null;
}

/**
 * El elemento lleva `aria-hidden`, y no puesto en `false`.
 *
 * Un `{...resto}` no cuenta como prueba de nada: puede traerlo o no, y no se sabe hasta ejecutar.
 * Sin `aria-hidden` escrito, el elemento se senala — que es la mitad cerrada de esta guarda.
 */
function estaOculto(apertura: ts.JsxOpeningLikeElement): boolean {
  for (const propiedad of apertura.attributes.properties) {
    if (!ts.isJsxAttribute(propiedad)) continue;
    if (propiedad.name.getText() !== 'aria-hidden') continue;

    const valor = propiedad.initializer;
    // `aria-hidden` a secas es `true` en JSX.
    if (valor === undefined) return true;
    if (ts.isStringLiteral(valor)) return valor.text !== 'false';
    if (ts.isJsxExpression(valor) && valor.expression !== undefined) {
      return valor.expression.kind !== ts.SyntaxKind.FalseKeyword;
    }
    return true;
  }
  return false;
}

/**
 * Los usos de la clase en un archivo que NO se pudo demostrar que sean decorativos.
 *
 * `ruta` entra como dato y no se lee del disco: es lo que permite ejercerla sobre una fuente
 * inventada.
 */
export function hallazgosDe(ruta: string, fuente: string, clase: string): Hallazgo[] {
  const arbol = ts.createSourceFile(
    ruta,
    fuente,
    ts.ScriptTarget.Latest,
    // `setParentNodes`: sin esto no hay `.parent` que subir, y toda la guarda se queda muda.
    true,
    ruta.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );

  const salida: Hallazgo[] = [];
  const enLinea = (nodo: ts.Node): number =>
    arbol.getLineAndCharacterOfPosition(nodo.getStart(arbol)).line + 1;

  const visitar = (nodo: ts.Node): void => {
    if (esCadena(nodo) && usaLaClase(nodo.text, clase)) {
      const atributo = atributoQueLaLleva(nodo);

      if (atributo === null) {
        salida.push({
          ruta,
          linea: enLinea(nodo),
          sobre: '(ningun elemento)',
          porQue:
            'la clase no cuelga de ningun `className`, asi que no hay elemento del que decir si ' +
            'es decorativo',
        });
      } else if (atributo.name.getText() !== 'className') {
        salida.push({
          ruta,
          linea: enLinea(nodo),
          sobre: `el atributo «${atributo.name.getText()}»`,
          porQue:
            'la clase viaja en un atributo que no es `className`, asi que no se sabe sobre que ' +
            'elemento cae',
        });
      } else {
        const apertura = atributo.parent.parent;
        if (!estaOculto(apertura)) {
          salida.push({
            ruta,
            linea: enLinea(nodo),
            sobre: `<${apertura.tagName.getText()}>`,
            porQue: 'el elemento no lleva `aria-hidden`, o sea que lo que pinta se lee',
          });
        }
      }
    }
    ts.forEachChild(nodo, visitar);
  };

  visitar(arbol);
  return salida;
}

/** Los `.ts` y `.tsx` de un arbol, sin pruebas: una prueba no pinta nada. */
export function archivosDeInterfaz(raiz: string): string[] {
  return readdirSync(raiz, { withFileTypes: true }).flatMap((entrada) => {
    const ruta = join(raiz, entrada.name);
    if (entrada.isDirectory()) {
      return entrada.name === 'node_modules' ? [] : archivosDeInterfaz(ruta);
    }
    if (!/\.tsx?$/.test(entrada.name) || entrada.name.includes('.test.')) return [];
    return [ruta];
  });
}
