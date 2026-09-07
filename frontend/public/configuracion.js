/*
 * Las senias del ambiente, servidas y no horneadas. Ver `src/api/configuracion.ts`.
 *
 * ESTE ARCHIVO VIAJA VACIO A PROPOSITO, y esa es toda su razon de ser.
 *
 * Es el que la imagen trae dentro. En el cluster, el `ConfigMap` que declara
 * `infrastructure/src/descriptor.ts` se monta ENCIMA de el y lo reemplaza con las senias de la
 * municipalidad; en `yarn dev` y en la imagen levantada a secas, se sirve este, no fija ninguna
 * llave, y la cadena de `configuracion()` cae al escalon siguiente.
 *
 * Que exista aunque este vacio es la decision. La alternativa —no ponerlo, y que `nginx` conteste
 * 404 mientras nadie monte el `ConfigMap`— cuesta dos cosas: un error en la consola del navegador
 * en cada carga de `yarn dev`, que es ruido que acaba no mirandose; y, peor, un 404 que el
 * `try_files` de un `nginx` mal configurado convierte en el `index.html` servido como si fuera un
 * guion —el «200 que miente» de #44—, o sea que el fallo mas ruidoso posible se disfrazaria del
 * mas silencioso.
 *
 * NO se le ponen valores por omision aqui. Los tiene `src/api/configuracion.ts`, en un solo sitio
 * y con su tipo: dos copias de «el emisor local es localhost:8181» se separan, y la que se
 * separaria es esta, que no la lee ningun compilador.
 *
 * Guion clasico y no modulo: `index.html` lo carga antes que el paquete, y un `type="module"` se
 * difiere hasta despues del analisis del documento — o sea que llegaria TARDE, cuando la puerta
 * de identidad ya hubiera leido las senias.
 */
window.__KAMAYUK_RENTAS__ = window.__KAMAYUK_RENTAS__ || {};
