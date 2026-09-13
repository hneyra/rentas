// @vitest-environment node
//
// Compila CSS de verdad y lee archivos del disco. No es un DOM lo que necesita.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { HOJA_DE_UI, RAIZ_DE_UI, compilar } from './tailwind.ts';

/**
 * **El arnes resuelve un `@import` relativo como el empaquetador: contra el ARCHIVO que lo
 * escribe** (#125).
 *
 * <h2>Lo que estaba mal, y por que no se veia</h2>
 *
 * `compilar` leia la hoja de `<raiz de @kamayuk/ui>/estilos/estilos.css` y le decia al compilador
 * que la base era **la raiz del paquete**. Con `loadStylesheet` haciendo `join(base, id)`, un
 * `@import "./temas.css"` escrito dentro de `estilos/estilos.css` se buscaba en
 * `<raiz>/temas.css` en vez de `<raiz>/estilos/temas.css`. **Vite lo resuelve relativo al archivo
 * que lo escribe**, que es lo que el navegador recibe.
 *
 * O sea: el arnes compilaba **una hoja que no es la que se sirve**. Y no mordia porque la hoja
 * solo tenia `@import "tailwindcss"`, que el propio `loadStylesheet` intercepta por nombre: no
 * habia ni un import relativo que resolver mal.
 *
 * <h2>Por que una guarda con hoja propia, y no la de la libreria</h2>
 *
 * Porque la hoja de la libreria **hoy no tiene ningun `@import` relativo**. Apoyar la guarda en
 * ella seria apoyarla en que `kamayuk-lib`#23 —que le anade `temas.css` con las seis paletas— ya
 * este mezclado, y entonces la guarda no protegeria nada hasta ese dia, que es justo el dia en
 * que hace falta. La hoja de mentira de `paquete-de-muestra/` tiene la FORMA de la real —vive en
 * `estilos/` de un paquete y arrastra a su vecina— y mide el camino ahora.
 *
 * Y mide las dos cosas que `temas.css` traera, porque son dos caminos distintos: un bloque
 * `@theme`, del que Tailwind deriva sus utilidades, y CSS llano, que pasa tal cual.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));

/** La raiz del paquete de mentira: lo que `base` NO tiene que ser. */
const PAQUETE = join(AQUI, 'paquete-de-muestra');

/** La hoja de mentira, en `estilos/` — igual que la de `@kamayuk/ui`. */
const HOJA = join(PAQUETE, 'estilos', 'estilos.css');

describe('el arnes resuelve los `@import` donde el empaquetador los resuelve', () => {
  it('EL CENTINELA: la hoja de muestra discrimina de verdad', () => {
    // Sin esto la guarda seria decorativa. Tres cosas tienen que ser ciertas a la vez:
    expect(existsSync(HOJA), 'no esta la hoja de muestra').toBe(true);
    // 1) la hoja escribe un `@import` RELATIVO, que es lo unico que el defecto rompe;
    expect(readFileSync(HOJA, 'utf8')).toMatch(/@import\s+"\.\//);
    // 2) lo importado esta junto a la hoja...
    expect(existsSync(join(PAQUETE, 'estilos', 'paleta.css'))).toBe(true);
    // 3) ...y NO en la raiz del paquete. Si estuviera en las dos, la compilacion saldria bien
    //    resolviera contra donde resolviera, y esta prueba pasaria en verde con el defecto puesto.
    expect(
      existsSync(join(PAQUETE, 'paleta.css')),
      'Hay una copia de la vecina en la raiz del paquete de muestra: con ella, resolver contra\n' +
        'la raiz tambien encuentra el archivo y la guarda deja de distinguir nada.',
    ).toBe(false);
  });

  it('lo que la hoja importa LLEGA al CSS emitido, con su `@theme` y su CSS llano', async () => {
    const css = await compilar(['bg-muestra-vecina'], HOJA);

    // El `@theme` de la vecina: la utilidad se genera y trae el color. Es el camino por el que
    // `kamayuk-lib`#23 publica las seis paletas.
    expect(css, 'el `@theme` de la hoja importada no genero su utilidad').toContain(
      '.bg-muestra-vecina',
    );
    expect(css, 'la utilidad se genero sin el color que la vecina declara').toContain('#0a5c3e');
    // Y el CSS llano de la vecina, que pasa tal cual.
    expect(css, 'el CSS llano de la hoja importada no salio').toContain('.marca-de-la-vecina');
  });

  it('y tambien lo que importa la IMPORTADA, dos niveles mas abajo', async () => {
    const css = await compilar(['bg-muestra-anidada', 'bg-muestra-honda'], HOJA);

    // El `@import` de una hoja importada cuelga del directorio de ESA hoja. Sin esto,
    // `loadStylesheet` podria devolver cualquier `base` para lo que carga y nadie lo notaria.
    expect(css, 'el `@theme` de la hoja anidada no genero su utilidad').toContain(
      '.bg-muestra-anidada',
    );
    expect(css).toContain('#7b1fa2');
    expect(css, 'el CSS llano de la hoja anidada no salio').toContain('.marca-anidada');

    // Y el fondo del pozo, que es lo que hace que esto no sea decorativo: `temas/oscuro.css`
    // arrastra a un vecino SUYO. Solo se alcanza si el `base` de cada hoja importada es su
    // propio directorio; con el de la primera hoja se buscaria en `estilos/claro.css`.
    expect(css, 'no se alcanzo la hoja que importa la importada').toContain('.bg-muestra-honda');
    expect(css).toContain('#b26a00');
    expect(css, 'el CSS llano del fondo del pozo no salio').toContain('.marca-honda');
  });

  it('la hoja que se compila por omision es la que el navegador recibe', () => {
    // La otra mitad: que el camino correcto se aplique a la hoja DE VERDAD. Se comprueba por
    // ruta porque el contenido ya lo miden las otras pruebas; lo que aqui importa es que la hoja
    // siga viviendo en `estilos/` —o sea, que su directorio NO sea la raiz del paquete—, que es
    // exactamente lo que el defecto confundia.
    expect(existsSync(HOJA_DE_UI), `no esta la hoja de @kamayuk/ui en ${HOJA_DE_UI}`).toBe(true);
    expect(dirname(HOJA_DE_UI)).toBe(join(RAIZ_DE_UI, 'estilos'));
    expect(dirname(HOJA_DE_UI)).not.toBe(RAIZ_DE_UI);
  });
});
