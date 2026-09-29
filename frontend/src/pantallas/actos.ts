/**
 * **Los nombres que comparten la definicion de un acto y quien lo escribe** (#629).
 *
 * Por lo mismo que `tablas.ts` junta los sitios de la ruta que una tabla escribe y su conector lee:
 * un acto se nombra por su `clave` en la definicion —la accion que lo `abre`— y en `datos/actos.ts`
 * —el manejador que lo atiende—, y lee de `nombrados` lo que el sistema le pone —si la cuenta puede,
 * y lo que el servidor contesto—. Escritos dos veces a mano, un nombre que no case deja el boton
 * impedido para siempre, o el acto hecho diciendo la raya del dato ausente, sin un solo error.
 *
 * Vive aqui y no en `datos/` porque la definicion no puede importar de `datos/`: `datos/` ya importa
 * las definiciones —`laVentana.ts`, `catalogo-de-claves.ts`— y la vuelta cerraria un ciclo.
 */

/**
 * **El largo de la observacion de un acto, que es el del BACKEND** (regla 10, ADR-0008).
 *
 * `@kamayuk/ui` lo exige en el tipo del acto y a proposito no pone ninguno por omision: «un minimo
 * escrito en la libreria seria el de un sistema». El de este sistema lo fija `Observacion` del
 * dominio compartido —`LARGO_MINIMO = 5`, la restriccion `auditoria_observacion_ck` sobre el texto
 * recortado, y `LARGO_MAXIMO = 500`, el ancho de las columnas `observacion varchar(500)`—.
 *
 * Es la unica cifra del backend que esta interfaz copia, y no por gusto: `cambiarElEjercicio`
 * explica por que el mando de la barra NO la copia —el 422 lo dice con sus palabras—, pero el acto
 * del interprete no tiene otra forma de existir. Asi que se copia **con su guarda**:
 * `verificaciones/el-acto-escribe-lo-que-la-hoja-declara.test.ts` lee `Observacion.java` y sale
 * roja el dia que las dos cifras dejen de ser estas. El 422 del backend sigue siendo la ultima
 * palabra, y se ensena tal cual.
 */
export const LARGO_DE_LA_OBSERVACION = { minimo: 5, maximo: 500 } as const;

/** La `clave` del acto que anula la licencia de edificacion de un expediente (#455, #629). */
export const ANULAR_LA_LICENCIA = 'anular-licencia-de-edificacion';

/**
 * El dato con nombre que dice que **la cuenta puede** hacer este acto: presente —`'si'`— cuando
 * `GET /seguridad/sesion/permisos` le da el privilegio que la escritura pide, y ausente si no.
 * Ausente es «no puede» y no «no se sabe»: la matriz llega antes que el armazon, porque de ella se
 * compone el catalogo.
 */
export const puedeHacerlo = (claveDelActo: string): string => `permiso.${claveDelActo}`;

/** El nombre, en el catalogo de opciones, de la que hay que pedir para poder. Va en el motivo. */
export const opcionQueLoPide = (claveDelActo: string): string => `permiso.${claveDelActo}.opcion`;

/** Lo que contesto la anulacion, por nombre, para la tarjeta de lo hecho. */
export const LO_QUE_CONTESTO_LA_ANULACION = {
  resolucion: 'anulacion.resolucion',
  licencia: 'anulacion.licencia',
  expediente: 'anulacion.expediente',
} as const;

/**
 * La `clave` del acto que registra la diligencia de la resolucion de un RECURSO de transito (#638):
 * `POST /transito/descargos/{nDeExpediente}/resolucion/notificacion`, desde `tra-pap`.
 */
export const NOTIFICAR_LA_RESOLUCION = 'notificar-resolucion-del-recurso';

/**
 * **La forma de la notificacion: lo que se LEE y lo que VIAJA** (#638).
 *
 * El desplegable del acto envia la opcion tal cual la escribe la definicion —si se tradujera,
 * cambiar de idioma cambiaria lo que se envia—, y el backend lee el NOMBRE de su enumerado
 * `ModalidadDeNotificacion`: sin traducirlo aqui, `PeticionesDeSanciones.enumeradoDe` contesta 422
 * a cualquier cosa que se elija. Por eso las dos mitades viven en UN mapa: la definicion saca de
 * aqui sus opciones —en este orden, y la primera es la que el acto deja elegida— y `datos/actos.ts`
 * saca de aqui lo que manda. Escritas en dos listas, una opcion que no casara viajaria vacia.
 *
 * Los rotulos son los de «Forma de notificación» de `val-val`, que el artboard ya escribe para la
 * misma lista del art. 104 del TUO del Codigo Tributario. Que los valores sigan siendo los del
 * enumerado lo vigila `verificaciones/el-acto-escribe-lo-que-la-hoja-declara.test.ts`, que lee el
 * `.java`.
 */
export const FORMAS_DE_NOTIFICACION: Readonly<Record<string, string>> = {
  'Personal en domicilio fiscal': 'PERSONAL',
  'Con certificación de negativa': 'NEGATIVA',
  'Cedulón': 'CEDULON',
  'Publicación': 'PUBLICACION',
  'Electrónica': 'CORREO',
};

/**
 * **El resultado de la diligencia: lo que se LEE y lo que VIAJA** (#638), por lo mismo que
 * `FORMAS_DE_NOTIFICACION`, contra `ResultadoDeNotificacion`. Solo «No ubicado» deja el plazo sin
 * abrir: la negativa a recibir surte efecto (art. 104 a).
 */
export const RESULTADOS_DE_LA_DILIGENCIA: Readonly<Record<string, string>> = {
  'Notificado': 'NOTIFICADO',
  'No ubicado': 'NO_UBICADO',
  'Rechazado': 'RECHAZADO',
};

/** Lo que contesto la diligencia, por nombre, para la tarjeta de lo hecho. */
export const LO_QUE_CONTESTO_LA_DILIGENCIA = {
  numero: 'diligencia.numero',
  resolucion: 'diligencia.resolucion',
  direccion: 'diligencia.direccion',
  resultado: 'diligencia.resultado',
} as const;
