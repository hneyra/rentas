import type { ClaveDeHoja } from '../arbol.ts';
import type { DefinicionDePantalla as Pantalla } from '@kamayuk/ui';

/**
 * Las cuatro pantallas de **Valores** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'val-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const VALORES = {
  'val-panel': {
    instruccion: 'un valor emitido y sin notificar no cobra, y le corre el plazo de prescripción igual.',
    bloques: [
      {
        titulo: 'Valores del ejercicio',
        nota: 'Un valor emitido y sin notificar no cobra, y le corre el plazo igual.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          { etiqueta: 'Emitidos', tipo: 'r' },
          { etiqueta: 'Notificados', tipo: 'r' },
          { etiqueta: 'Sin notificar', tipo: 'r' },
          { etiqueta: 'Reclamados', tipo: 'r' },
          { etiqueta: 'Por prescribir este año', tipo: 'r' },
        ],
      },
    ],
  },
  'val-val': {
    instruccion: 'registre la notificación: es lo que hace exigible la deuda del valor.',
    bloques: [
      {
        titulo: 'Valor',
        nota: 'La notificación es lo que hace exigible la deuda.',
        campos: [
          { etiqueta: 'Nº de valor', tipo: '' },
          {
            etiqueta: 'Tipo de valor',
            tipo: 's',
            opciones: [
              'Orden de pago',
              'Resolución de determinación',
              'Resolución de multa',
              'Resolución de pérdida de fraccionamiento',
            ],
          },
          { etiqueta: 'Contribuyente', tipo: '1' },
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025', '2024'] },
          {
            etiqueta: 'Concepto',
            tipo: 's',
            opciones: [
              'Impuesto predial',
              'Arbitrios municipales',
              'Patrimonio vehicular',
              'Multa tributaria',
            ],
          },
          { etiqueta: 'Insoluto', tipo: '' },
          { etiqueta: 'Interés', tipo: 'r' },
          { etiqueta: 'Total del valor', tipo: 'r' },
          { etiqueta: 'Fecha de emisión', tipo: 'd' },
          {
            etiqueta: 'Fecha de notificación',
            tipo: 'd',
            ayuda: 'Sin ella el valor no es exigible',
          },
          {
            etiqueta: 'Forma de notificación',
            tipo: 's',
            opciones: [
              'Personal en domicilio fiscal',
              'Con certificación de negativa',
              'Cedulón',
              'Publicación',
              'Electrónica',
            ],
          },
          {
            etiqueta: 'Estado',
            tipo: 's',
            opciones: ['Emitido', 'Notificado', 'Reclamado', 'Firme', 'Anulado'],
          },
        ],
        tabla: {
          titulo: 'Movimientos del valor',
          columnas: [
            { rotulo: 'Fecha', alineadoDerecha: false },
            { rotulo: 'Movimiento', alineadoDerecha: false },
            { rotulo: 'Documento', alineadoDerecha: false },
            { rotulo: 'Importe S/', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
        },
      },
    ],
  },
  'val-cart': {
    instruccion: 'simule antes de emitir: una corrida toca miles de cuentas y la notificación es el cuello de botella.',
    bloques: [
      {
        titulo: 'Emisión por lote',
        nota: 'Una corrida toca miles de cuentas: se simula antes de emitir.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          {
            etiqueta: 'Tipo de valor',
            tipo: 's',
            opciones: ['Orden de pago', 'Resolución de determinación', 'Resolución de multa'],
          },
          {
            etiqueta: 'Concepto',
            tipo: 's',
            opciones: ['Impuesto predial', 'Arbitrios municipales', 'Patrimonio vehicular'],
          },
          {
            etiqueta: 'Alcance',
            tipo: 's',
            opciones: ['Todo el padrón', 'Por sector', 'Por rango de deuda', 'Sólo observados'],
          },
          { etiqueta: 'Sector', tipo: 's', opciones: ['Todos', '01', '02', '03', '04', '05'] },
          { etiqueta: 'Deuda mínima', tipo: '' },
          {
            etiqueta: 'Antigüedad mínima',
            tipo: 's',
            opciones: ['Cualquiera', 'Más de 90 días', 'Más de un año'],
          },
          {
            etiqueta: 'Genera cuponera PDF',
            tipo: 'c',
            casilla: 'Produce el archivo para imprenta',
          },
        ],
        tabla: {
          titulo: 'Última corrida',
          columnas: [
            { rotulo: 'Etapa', alineadoDerecha: false },
            { rotulo: 'Registros', alineadoDerecha: true },
            { rotulo: 'Monto S/', alineadoDerecha: true },
            { rotulo: 'Observados', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
          nota: 'La notificación es el cuello de botella: lo emitido y no notificado no cobra.',
        },
      },
    ],
  },
  'val-tip': {
    instruccion: 'declarar la prescripción es un acto: se hace de oficio o a pedido, y queda en la bitácora.',
    bloques: [
      {
        titulo: 'Tipos de valor y prescripción',
        nota: 'La deuda prescribe a los cuatro años; un acto de cobranza reinicia el plazo.',
        campos: [
          // **Los tres mandos, corregidos en #244** (y su gemelo en el artboard). Ninguno llega
          // todavia al conector —eso es #172—, y aun asi dos de los tres preguntaban lo que `GET
          // /coactiva/prescripcion` no admite. Lo que decide cada uno es la operacion:
          //
          //   · **«Tipo de valor» era «Orden de pago · RD · RM», y eso no existe aqui.** Ni como
          //     filtro —los opcionales de la operacion son `codContribuyente`, `ejercicio`,
          //     `resultado` y `tributo`, y ninguno es un tipo de valor— ni como dato: la fila de
          //     esta tabla es el ejercicio de una DECLARACION, y una declaracion es sobre un
          //     TRIBUTO. `PrescripcionEnListaResource` lo publica; ningun campo suyo dice tipo de
          //     valor. Asi que el mando pregunta por el tributo.
          //   · **Y es una CAJA y no un desplegable**, que es la mitad que no se ve. Un
          //     desplegable promete una lista cerrada, y `prescripcion.tributo` es `varchar(20)`
          //     **sin `CHECK`** —los de esa tabla son `causal`, `ejercicios`, `plazo_anios` y
          //     `resultado`— y `Prescripcion` solo comprueba que mida de 1 a 20 caracteres. El
          //     vocabulario cerrado que si existe, `TributoDelLibro`, cierra
          //     `cuenta_corriente_asiento.tributo` y lo dice en su javadoc, no esta. Una opcion
          //     con otra grafia no daria error: daria CERO filas, que se lee como «no hay
          //     declaraciones de ese tributo». Es el defecto que ese enumerado documenta
          //     —`ARBITRIO` contra `ARBITRIOS`— visto desde la interfaz.
          { etiqueta: 'Tributo', tipo: '', ayuda: 'El tributo tal como el libro lo escribe; en blanco, todos' },
          // **«Ejercicio» era el unico que cableaba, y aun asi su rotulo mentia.** `?ejercicio=`
          // acota por el RANGO SOLICITADO —`ejercicio_desde <= :ejercicio AND ejercicio_hasta >=
          // :ejercicio`, `PrescripcionRepositoryJdbc`— y no por lo que prescribio;
          // `CriterioDePrescripciones` explica por que es deliberado —filtrar por «los que
          // prescribieron» esconderia las `NO_PROCEDE`, que son las que dicen que ese ejercicio
          // sigue siendo exigible—. Y la tabla de debajo tiene una columna que se llama
          // «Ejercicio»: con el mando llamado igual, pedir 2024 y ver filas de 2021 se lee como
          // una averia. Se ven todos los ejercicios de las declaraciones que PIDIERON 2024.
          //
          // «Todos» es **no mandar el parametro**: es opcional, y sin el la relacion es «todas las
          // declaraciones de esta municipalidad» (`CriterioDePrescripciones`).
          {
            etiqueta: 'Ejercicio solicitado',
            tipo: 's',
            opciones: ['Todos', '2026', '2025', '2024', '2023', '2022'],
          },
          // **«Estado» confundia el resultado de la SOLICITUD con la situacion del EJERCICIO.**
          // Lo que la operacion admite es `?resultado=`, que es como se resolvio la solicitud:
          // `PROCEDE`, `PROCEDE_EN_PARTE`, `NO_PROCEDE` —los tres del `CHECK`
          // `prescripcion_resultado_check`, y el controlador contesta 422 nombrandolos si llega
          // otro—. La situacion del ejercicio ya esta dibujada, en la columna «Situacion» de la
          // tabla, y **no tiene filtro**: `prescrita` no es un parametro.
          //
          // Y con esto **«Por prescribir» sale**, que es lo que la regla 5 pedia: exigia un umbral
          // —cuanto de cerca es «cerca»— que el corpus de `normativa` no publica. Es la misma
          // salida que #218 dio a la insignia de `ini-parado`: no se inventa el umbral, se retira
          // la opcion que lo necesitaba.
          {
            etiqueta: 'Resultado de la solicitud',
            tipo: 's',
            opciones: ['Todos', 'Procede', 'Procede en parte', 'No procede'],
          },
          // **«Declaraciones», y no «Prescriben este año»** (#230, y su gemelo en el artboard).
          // Lo de antes era un agregado del padron —cuantos valores prescriben dentro del ano en
          // curso y cuanto suman— que ninguna operacion publica; contarlo sobre la pagina que llega
          // daria una cifra sobre veinte de cientos. Este es `totalElementos`, que la operacion
          // publica sobre la bitacora entera: el mismo camino que #172 abrio para «3 de 188».
          { etiqueta: 'Declaraciones', tipo: 'r' },
        ],
        tabla: {
          // Con `clave`, las filas llegan por `DatosDeLaPantalla.tablas` (`kamayuk-lib`#87, #180).
          clave: 'reloj-de-prescripcion',
          sinDato: { texto: '—', nota: 'Ninguna operacion publica este dato.' },
          titulo: 'Reloj de prescripción',
          // **Cuatro columnas, y hasta #230 eran cinco.** El artboard dibujaba un reloj agregado
          // POR EJERCICIO —«Ejercicio · Valores · Importe S/ · Prescribe el · Situación»— sobre una
          // operacion que publica la bitacora de DECLARACIONES. Lo que cambia, y por que:
          //
          //   · **«Valores» e «Importe S/» salen.** El backend se niega a publicarlos por escrito y
          //     con su motivo: «la prescripcion no extingue un importe: deja sin accion su cobro …
          //     publicar aqui un importe obligaria ademas a decir a que fecha (regla 9), y la fecha
          //     que tendria sentido no es un dato de esta fila sino del libro» (`PrescripcionEnLista`).
          //     Agruparlos aqui es lo que prohibe `datos/conectores.ts`, y sobre una pagina de
          //     veinte de cientos seria ademas una cifra falsa.
          //   · **«Prescribe el» se queda**, y desde #230 tiene de donde salir:
          //     `ejercicios[].prescribeEl`. Viaja como DATO —la fecha que el computo resolvio el dia
          //     de la solicitud, con el plazo del conjunto sellado de entonces—, y no se resta aqui.
          //   · **«Contribuyente» entra**: la fila ya no es un tramo del padron sino el ejercicio de
          //     la declaracion de alguien, y una fecha de prescripcion sin decir de quien no dice nada.
          columnas: [
            { rotulo: 'Contribuyente', alineadoDerecha: false },
            { rotulo: 'Ejercicio', alineadoDerecha: false },
            { rotulo: 'Prescribe el', alineadoDerecha: false },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          // Sigue siendo de insignia, y ahora se puede encender: le llega `prescrita`, un booleano
          // que el backend publica, no una frase. Dos valores y no tres — «Por prescribir» exigiria
          // un umbral que el corpus no publica (regla 5), y es lo que #218 tuvo que retirar en
          // `ini-parado` por no tenerlo. **Desde #244 tampoco esta en el mando de arriba**: el
          // desplegable que la ofrecia era «Estado», y ahora pregunta por el resultado de la
          // solicitud, que si tiene tres valores publicados.
          columnaDeInsignia: 3,
          nota: 'Declarar la prescripción es un acto: se hace de oficio o a pedido, y queda en la bitácora.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
