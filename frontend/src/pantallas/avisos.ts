/**
 * Los dos avisos del pie, **con las palabras del artboard, y son los que se VEN**.
 *
 * El armazon decide cual de los dos sale —`avisoDelPie()` de `@kamayuk/shell`: el de escritura si
 * el destino tiene algun campo que se escriba, el de consulta si no— y lo saca del saco de textos
 * que este sistema le pasa. Ese saco es `i18n/textosDelMarco.ts`, y **desde #281 sus dos entradas
 * salen de aqui**: `nadaSeEscribeTodavia` es `AVISOS_DE_V8.escritura` y `datosDeHoy` es
 * `AVISOS_DE_V8.consulta`. Una fuente, no dos.
 *
 * Que sigan siendo las del artboard lo comprueba
 * `verificaciones/los-avisos-del-pie-son-los-del-artboard.test.ts`; que sean las que la pantalla
 * montada enseña, `verificaciones/el-pie-que-se-ve-sale-de-aqui.test.tsx`. Hacen falta las dos y
 * ninguna sirve sola: la primera compara esto contra el diseno y no mira el DOM; la segunda mira
 * el DOM y no sabe que dice el diseno.
 *
 * <h2>De que defecto vienen las dos guardas, medido (#262, #281)</h2>
 *
 * Hasta #281 **nadie importaba esta constante mas que su guarda**. Quien ponia el pie era
 * `textosDelMarco.ts`, con las dos frases escritas **a mano**, y de las dos de V8 una llegaba
 * —`nadaSeEscribeTodavia`, por coincidir palabra por palabra, que es una coincidencia con forma de
 * contrato— y la otra **no**: le faltaban las tres palabras «en el padrón». O sea que la copia
 * vigilada no se veia y la que se veia no la vigilaba nadie, y ya diferian.
 *
 * <h2>Y por que la frase de consulta perdio «en el padrón», que es lo que decia V8</h2>
 *
 * Porque no es verdad donde sale, y eso se midio antes de elegir —sobre este arbol y sobre el
 * artboard por separado, y las dos cuentas dan lo mismo—: de los cuarenta destinos, el que no se
 * escribe es **uno**, `seg-panel`, cuyos seis campos son usuarios registrados, activos, con
 * permiso total, cuentas inactivas con permisos, contrasenas caducadas y la ultima restauracion
 * verificada. Ahi no hay padron ninguno. La rama de consulta es ademas generica para los diez
 * modulos —Seguridad, Infracciones y Transito incluidos— y el propio artboard nombra tres
 * padrones distintos, asi que «el padrón» no tendria ni a que referirse. Se corrigio el artboard
 * con la misma medida escrita al lado, que es como se corrigio en su dia el rotulo de `val-tip`:
 * el artboard es el diseno contra el que se reimplanta, no un registro de una medicion.
 *
 * <h2>Lo que esto NO es</h2>
 *
 * **No es el saco de textos del marco.** Son dos frases de ESTE sistema, guardadas contra el
 * diseno de ESTE sistema; el resto de las treinta y dos del armazon vive en `textosDelMarco.ts` y
 * ahi se queda. Y no suben a `@kamayuk/shell` —donde estan sus valores por omision, identicos a
 * estos hoy— porque las de alla valen para los cuatro sistemas y estas responden ante `RentasV8`.
 */
export const AVISOS_DE_V8 = {
  consulta: 'Los datos son los que figuran a la fecha de hoy.',
  escritura: 'Nada se escribe hasta que pulse Guardar.',
} as const;
