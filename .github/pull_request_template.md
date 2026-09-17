<!-- ────────────────────────────────────────────────────────────────────────────────────────
     LA LINEA QUE CIERRA DE VERDAD

     Escribe `Closes #<numero>`, en ingles, y **en el cuerpo** del PR: no en el titulo, que
     GitHub no lee para esto.

     GitHub solo auto-cierra con close/closes/closed, fix/fixes/fixed y
     resolve/resolves/resolved. **La palabra castellana NO cierra nada**, aunque sea el
     idioma de la casa y aunque la guarda del registro la acepte —y la acepta a proposito:
     el idioma no se cambia por una limitacion de GitHub—.

     Medido en la tanda del 2026-09-12: cinco PR escribieron la palabra inglesa y cerraron
     su issue al mezclar; uno escribio la castellana y no cerro nada. Ese PR se mezclo, la
     CI quedo verde, la guarda dijo que la fila estaba, y el issue siguio abierto hasta que
     alguien lo cerro a mano al auditar (ver 130).

     Desde entonces eso sale ROJO en el flujo `Registro`, con el issue nombrado. Y si de
     verdad NO quieres que se cierre solo, no lo declares: «Ref» o «Parte de» seguidos del
     numero no disparan nada, ni aqui ni en GitHub.

     OJO: este comentario viaja en el cuerpo si no lo borras, y la guarda lee el cuerpo
     ENTERO, comentarios incluidos. Por eso aqui no hay ni una palabra de cierre seguida de
     un numero: le haria exigir la fila de un issue que este PR no cierra (paso en #55).

     El resto del cuerpo va en castellano, como el commit y como la fila del registro.


     Y LA RAMA CONTRA LA QUE ESTE PR ESTA ABIERTO

     Tiene que ser `main`. Compruebalo —`gh pr view N --json baseRefName`— y si no lo es,
     retargetealo con `gh pr edit N --base main`.

     Medido en #209 el 2026-09-17: el PR se abrio contra `issue-173`, se mezclo ahi, y su
     trabajo NO llego a `main`. El PR quedo en MERGED, su issue cerrado, su fila escrita y la
     guarda del registro en verde — todo verde menos el codigo, que se quedo fuera del arbol.
     Se descubrio horas despues por una resta de pruebas que no cuadraba: 788 sobre `main` y
     791 sobre la rama.

     APILAR ES LEGITIMO cuando dos ramas tocan el mismo archivo: abrir la segunda contra la
     primera es lo que evita el conflicto garantizado. Lo que no puede ser es que retargetear
     dependa de que alguien se acuerde, asi que **se declara**, con la rama nombrada:

         Apilado sobre <la-rama>

     Es el mismo trato que el auto-cierre: se puede no hacerlo por omision, pero entonces hay
     que decirlo. Lo comprueba `docs/00-gobierno/verificar-la-rama-base-del-pr.mjs` (#220), que
     ademas sale rojo —declarado o no— si esa rama YA entro entera en `main`: sobre una rama ya
     mezclada no queda nada con lo que chocar, y apilarse ahi solo vara el commit.
     ──────────────────────────────────────────────────────────────────────────────────── -->

Closes #

## Que hace

<!-- Que cambia y por que. En castellano. -->

## Como se demostro que la verificacion puede fallar

<!-- CLAUDE.md §«Verificar antes de afirmar»: con que rotura se midio y que rojo exacto
     salio. Si este PR cierra un issue y toca codigo de produccion, ademas tiene que dejar
     su fila en `docs/agent/HISTORY.md`, y eso lo comprueba el flujo `Registro`. -->

## Lo que NO cierra

<!-- Lo que queda fuera, con su motivo. -->
