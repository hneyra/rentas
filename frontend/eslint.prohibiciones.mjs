/**
 * Las prohibiciones del frontend de `rentas`, como DATO.
 *
 * No estan escritas dentro de `eslint.config.js` a proposito. Este archivo lo leen dos
 * consumidores y tienen que leer lo mismo:
 *
 *   1. `eslint.config.js`, que las convierte en opciones de `no-restricted-syntax`, y
 *   2. `verificaciones/reglas-de-eslint.test.ts`, que exige de cada una su muestra.
 *
 * Si la prueba tuviera su propia lista, seria una copia: se anade una regla al config, la
 * lista de la prueba no se toca, y la regla nueva queda sin muestra **en verde**. Que es
 * exactamente el modo de fallo que la prueba existe para impedir. Derivadas de aqui las
 * dos, una prohibicion sin muestra sale roja sola.
 *
 * El `clave` no es decorativo: **es el nombre de su muestra**. La prueba no tiene un mapa
 * de «regla -> archivo» que alguien pueda dejar desactualizado; compone la ruta.
 */

/**
 * @typedef {object} Prohibicion
 * @property {string} clave     Identificador estable. Tambien el nombre del archivo de su
 *                              muestra en `verificaciones/muestras/`, sin extension.
 * @property {string} regla     La fila de la tabla de reglas del producto a la que sirve.
 *                              Varias prohibiciones pueden servir a la misma regla.
 * @property {string} selector  Selector ESQuery que la detecta. Admite varios separados
 *                              por coma, que es como una regla se hace de varias formas.
 * @property {string} message   Lo que se le dice a quien la incumple. La prueba compara
 *                              contra ESTE texto, no contra una copia suya.
 * @property {string} [salvo]   Prefijo de ruta donde la prohibicion NO aplica. Una sola,
 *                              porque una excepcion que se puede repetir deja de serlo.
 */

/**
 * Nombres de campo que llevan dinero. Sobre ellos no se hace aritmetica ni se declara un
 * `number`.
 *
 * **`total` lleva una excepcion, y es de verdad la unica.** `totalElementos` y `totalPaginas`
 * son los dos contadores del envoltorio de paginacion del backend —`{ contenido, pagina,
 * tamano, totalElementos, totalPaginas, hayMas }`, que publican mas de sesenta de las 181
 * operaciones—, y son cuentas de cosas, no de dinero: llegan como `entero` en
 * `docs/50-api/formas-de-la-api.json` y tienen que declararse `number`. Sin la excepcion, toda
 * pantalla con una tabla paginada arrancaria con dos `eslint-disable`, y una regla que se
 * desactiva por costumbre deja de proteger a la tercera vez. Lo descubrio F-4 al tipar el
 * envoltorio; el resto de `total…` —`totalAPagar`, `totalDeLaDeuda`— sigue prohibido, y la
 * prueba de reglas lo comprueba por los dos lados.
 */
const CAMPOS_DE_DINERO =
  'monto|importe|saldo|deuda|total(?!Elementos|Paginas)|insoluto|interes|autovaluo|arbitrio|recargo|vuelto|recibido|pagado|abonado';

/**
 * Tildes y enie: prohibidas en identificadores (idioma del repositorio).
 * Copiada de `infrastructure/infra/eslint.config.mjs`, donde ya estaba escrita: la misma
 * regla en dos sitios distintos es dos reglas que divergen.
 */
const LETRAS_ACENTUADAS = 'áéíóúÁÉÍÓÚñÑüÜ';

/**
 * El unico directorio que puede llamar a `fetch`.
 *
 * Es la excepcion que da sentido a la regla: mientras toda peticion pase por `solicitar()`,
 * enchufar el token, la clave de idempotencia y el formato de error se hace en un sitio.
 * Un `fetch` suelto en una pantalla no se salta una convencion: se salta las tres.
 */
export const CLIENTE_DE_API = 'src/api/';

/** @type {readonly Prohibicion[]} */
export const PROHIBICIONES = [
  {
    clave: 'identificador-con-tilde',
    regla: 'sin tildes ni enie en identificadores',
    selector: `Identifier[name=/[${LETRAS_ACENTUADAS}]/]`,
    message: 'Sin tildes ni enie en identificadores. El texto con tildes va en las cadenas.',
  },
  {
    clave: 'fetch-fuera-del-cliente',
    regla: 'fetch prohibido fuera del cliente de API',
    selector: "CallExpression[callee.name='fetch']",
    message:
      'Las peticiones pasan por «solicitar» de src/api: ahi viven el token, la clave de idempotencia y el formato de error (ADR-0030 §3).',
    salvo: CLIENTE_DE_API,
  },
  {
    clave: 'importe-declarado-number',
    regla: 'un importe es string, nunca number',
    selector:
      `TSPropertySignature[key.name=/^(${CAMPOS_DE_DINERO})/i] > TSTypeAnnotation > TSNumberKeyword, ` +
      `Identifier[name=/^(${CAMPOS_DE_DINERO})/i] > TSTypeAnnotation > TSNumberKeyword`,
    message:
      'Un importe se declara «string», nunca «number»: en coma flotante 0.1 + 0.2 no es 0.30 y el centimo se pierde antes de mostrarse (regla 1, RNF-055).',
  },
  {
    clave: 'importe-convertido-a-number',
    regla: 'un importe es string, nunca number',
    selector:
      `CallExpression[callee.name=/^(Number|parseFloat|parseInt)$/] > MemberExpression[property.name=/^(${CAMPOS_DE_DINERO})/i], ` +
      `CallExpression[callee.name=/^(Number|parseFloat|parseInt)$/] > Identifier[name=/^(${CAMPOS_DE_DINERO})/i]`,
    message:
      'Un importe es texto y pierde centimos como number. No lo conviertas: formatealo (regla 1, RNF-055).',
  },
  {
    clave: 'aritmetica-con-importes',
    regla: 'sin aritmetica sobre importes',
    selector:
      `BinaryExpression[operator=/^[-+*/%]$/] > MemberExpression[property.name=/^(${CAMPOS_DE_DINERO})/i], ` +
      `CallExpression[callee.property.name='reduce'][callee.object.property.name=/^(${CAMPOS_DE_DINERO}|cuotas|conceptos|valores|papeletas)/i]`,
    message:
      'Aritmetica con un importe. El total lo calcula el backend y lo sostiene con su fecha: pidelo, no lo sumes (regla 1, regla 9).',
  },
  {
    clave: 'importe-sin-fecha',
    regla: 'un importe se muestra con su fecha de calculo',
    // `:not(:has(...))`: el elemento de apertura que NO tiene entre sus atributos
    // uno llamado `fechaCalculo`. Un `<Importe {...props} />` tambien cae, y esta
    // bien que caiga: desde el JSX no hay forma de saber si ese objeto la trae.
    selector:
      "JSXOpeningElement[name.name='Importe']:not(:has(JSXAttribute[name.name='fechaCalculo']))",
    message:
      'Un importe se muestra con la fecha a la que esta calculado: no existe «la deuda», existe la deuda a una fecha (regla 9, RNF-075).',
  },
  {
    clave: 'municipalidad-en-el-cliente',
    regla: 'municipalidadId no se manda nunca',
    selector: "Identifier[name='municipalidadId']",
    message:
      'El frontend jamas envia municipalidadId: el backend lo toma del token (regla 2, ADR-0028 §2).',
  },
  {
    clave: 'token-en-almacenamiento',
    regla: 'el token no toca localStorage ni sessionStorage',
    // La prohibicion es guardar CREDENCIALES en el navegador, no usar el almacenamiento:
    // una preferencia de la ventanilla ahi esta en su sitio. Por eso mira la clave.
    selector:
      'CallExpression[callee.object.name=/^(localStorage|sessionStorage)$/][callee.property.name=/^(setItem|getItem|removeItem)$/][arguments.0.value=/token|jwt|bearer|credencial|contrasena|acceso|sesion/i]',
    message:
      'El token vive en memoria, nunca en localStorage ni sessionStorage: en una PC de ventanilla compartida entre turnos, un token persistido sobrevive al cierre del navegador (ADR-0030 §3).',
  },
  {
    clave: 'tasa-en-vez-de-alicuota',
    regla: 'alicuota, nunca tasa',
    selector: 'Identifier[name=/^tasa(De)?(Interes|Descuento|Porcentaje|Depreciacion|Moratori)/i]',
    message: 'Un porcentaje se llama «alicuota» (regla 8). «tasa» es un tipo de tributo del manual.',
  },
];

/**
 * Las reglas del producto que el frontend expresa como verificacion, tal como las nombra
 * el issue F-1. La prueba exige que cada una tenga al menos una prohibicion que la sirva.
 *
 * ES LA LISTA ESCRITA A MANO, y es deliberado que sea la unica. `PROHIBICIONES` se deriva
 * hacia la prueba, asi que **borrar una prohibicion borraria tambien su prueba**, en
 * silencio. Esta lista es lo que se pone rojo cuando eso pasa.
 *
 * @type {readonly string[]}
 */
export const REGLAS_EXIGIDAS = [
  'sin tildes ni enie en identificadores',
  'fetch prohibido fuera del cliente de API',
  'un importe es string, nunca number',
  'un importe se muestra con su fecha de calculo',
  'sin aritmetica sobre importes',
  'municipalidadId no se manda nunca',
  'el token no toca localStorage ni sessionStorage',
  'alicuota, nunca tasa',
];
