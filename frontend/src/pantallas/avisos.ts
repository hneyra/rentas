/**
 * Los dos avisos del pie, **con las palabras del artboard**.
 *
 * El interprete trae unos neutros, porque es de `@kamayuk/ui` (#153) y alli no puede decir
 * «padron» —es vocabulario de un contexto, y ADR-0030 §4 lo prohibe—. Los de V8 viven AQUI, que
 * es donde el vocabulario de Rentas si pertenece.
 *
 * Salen del artboard palabra por palabra, y que lo sigan haciendo lo comprueba
 * `verificaciones/los-avisos-del-pie-son-los-del-artboard.test.ts`.
 *
 * <h2>Y NADIE SE LOS PASA TODAVIA, que es lo que hay que saber antes de creerse el pie</h2>
 *
 * **Medido (#262): el unico `import` de `AVISOS_DE_V8` es el de su guarda.** Hasta aqui esta
 * frase terminaba en «y se los pasa quien monta la pantalla», y eso no lo hace nadie:
 * `PantallaDeRentas.tsx` le pasa al interprete tres cosas —`traducir`, `textos` y
 * `tonoDeLaInsignia`— y los avisos no estan entre ellas. Quien los pone de verdad es
 * `i18n/textosDelMarco.ts`, que declara los suyos **a mano y neutros**:
 *
 *   · `nadaSeEscribeTodavia` — identico al de aqui, palabra por palabra;
 *   · `datosDeHoy` — «Los datos son los que figuran a la fecha de hoy.», o sea **el de la
 *     libreria**, sin «en el padrón».
 *
 * De las dos frases de V8, una llega al pie por casualidad —porque coincide— y la otra **no
 * llega**. Asi que en este arbol hay dos copias del aviso de consulta: esta, que sale del
 * artboard y esta vigilada, y la de `textosDelMarco.ts`, que es la que se ve y no la vigila
 * nadie. La guarda de aqui sigue valiendo entera —lo que impide es que estas deriven del
 * artboard, y de eso responde igual de bien en la estanteria—; lo que no vale es leer esto y dar
 * por hecho que el pie dice «padrón».
 */
export const AVISOS_DE_V8 = {
  consulta: 'Los datos son los que figuran en el padrón a la fecha de hoy.',
  escritura: 'Nada se escribe hasta que pulse Guardar.',
} as const;
