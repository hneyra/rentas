/**
 * El convenio de fraccionamiento con su preconvenio, su formalizacion y su quiebre (ARQ-01 §3.8), y
 * el ADAPTADOR CLIENTE de {@code caja} (P5D, ADR-0026).
 *
 * <h2>El modulo hace DOS cosas, y por eso conserva su nombre</h2>
 *
 * <p>Hasta P5D esto era la caja entera: ventanilla, turno, recibo, arqueo, catalogo del TUPA y
 * convenio, todo junto. `V7` retiro de esta base las diez tablas del dinero —viven en el
 * repositorio {@code caja}— y este modulo <b>se partio, no se fue</b>:
 *
 * <ul>
 *   <li><b>Se queda el convenio</b>, con su dominio, sus cinco tablas y sus dos repositorios,
 *       porque un convenio es <b>deuda reprogramada</b>: tiene interes, tiene quiebre y tiene
 *       consecuencias coactivas. Si viajara a {@code caja}, {@code caja} adquiriria reglas
 *       tributarias y dejaria de poder cobrar un puesto de mercado (ADR-0026 §5).
 *   <li><b>Se queda el transporte hacia {@code caja}</b>: los cuatro puertos de este paquete que
 *       preguntan por el dinero —{@link kamayuk.rentas.tesoreria.RecibosDeTramite}, {@link
 *       kamayuk.rentas.tesoreria.CobrosDeTasas}, {@link kamayuk.rentas.tesoreria.AvanceDeCaja} y
 *       {@link kamayuk.rentas.tesoreria.AnulacionesDeRecibo}— y el cliente HTTP que los implementa.
 * </ul>
 *
 * <p><b>El modulo NO se renombra</b>, y es deliberado: renombrarlo obligaria a tocar el paquete de
 * las 33 clases del convenio, sus {@code import} en {@code rentas} y en {@code coactiva}, y el
 * artefacto de Gradle — un diff enorme en el que un cambio de verdad no se veria, justo en la etapa
 * en la que hay que poder leer que se movio. Que «tesoreria» ya no describa solo la caja se dice
 * aqui, que es donde se lee.
 *
 * <h2>Los puertos no se tocaron, y ahi esta lo que se cobra</h2>
 *
 * <p>Los tres que otros modulos consumen eran ya el contrato desde #44, #50 y #56, asi que {@code
 * licencias}, {@code sanciones}, {@code coactiva} e {@code indicadores} <b>no cambiaron ni una
 * linea</b>. Lo unico que cambio es quien los implementa: antes tres clases de {@code .aplicacion}
 * que leian {@code recibo}; ahora {@code ClienteHttpDeCaja}. Es la propiedad que ARQ-01 §4 compro y
 * que aqui se cobra por tercera vez, tras P5B con {@code normativa} y P5C con {@code catastro}.
 *
 * <p>Asienta abonos; nunca determina.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.tesoreria;
