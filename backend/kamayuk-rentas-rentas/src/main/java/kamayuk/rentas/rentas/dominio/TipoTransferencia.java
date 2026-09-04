package kamayuk.rentas.rentas.dominio;

import java.util.Locale;
import java.util.Objects;

/**
 * El acto por el que un predio o un vehiculo cambia de titular (#29, #542).
 *
 * <h2>De donde salen los nueve, uno por uno</h2>
 *
 * <p>Del <b>catalogo portado del prototipo</b>, que es la especificacion funcional: son las
 * opciones del desplegable «Tipo de acto» de las dos pantallas que registran el acto, y no hay
 * ninguno que no este dibujado ahi. <b>El manual dibuja dos listas distintas</b>, y esta es la
 * union de las dos:
 *
 * <ul>
 *   <li><b>«Transferencia de predio»</b> ofrece siete: COMPRA-VENTA, DONACION, PERMUTA, ANTICIPO DE
 *       LEGITIMA, ADJUDICACION, DACION EN PAGO y SUCESION.
 *   <li><b>«Transferencia de vehiculo»</b> ofrece cinco, y <b>dos de ellas no estan en la otra
 *       lista</b>: REMATE y HERENCIA. Las otras tres —COMPRA-VENTA, DONACION y DACION EN PAGO— si.
 * </ul>
 *
 * <p><b>SUCESION y HERENCIA no se funden en un solo valor</b>, aunque nombren el mismo hecho. Son
 * dos rotulos que el manual imprime en dos pantallas, y decidir aqui que uno es el otro cambiaria
 * en silencio lo que quedo registrado: es la misma razon por la que #427 se nego a traducir
 * «ACTIVA» a {@code VIGENTE}. Que el manual use dos palabras para lo mismo es un hallazgo suyo, no
 * una errata que este enumerado pueda corregir.
 *
 * <h2>Por que este enumerado NO decide la afectacion a alcabala</h2>
 *
 * <p>La tentacion es evidente —{@code Transferencia} dice que «el tipo de transferencia decide si
 * el impuesto aplica»— y aun asi {@code afectaAlcabala} <b>sigue siendo un dato declarado</b> y no
 * una propiedad de este enumerado. Tres medidas lo sostienen:
 *
 * <ol>
 *   <li><b>El manual dibuja las dos cosas, en la misma pantalla.</b> «Transferencia de predio»
 *       tiene el desplegable «Tipo de acto» <i>y</i> una casilla aparte, «Genera alcabala», con su
 *       ayuda «Liquida el impuesto de alcabala». Si el tipo decidiera, la casilla sobraria; que el
 *       manual la dibuje al lado dice que quien atiende declara las dos cosas.
 *   <li><b>La afectacion tiene tres dimensiones y solo una es el tipo del acto.</b> El corpus
 *       VERIFICADO de {@code docs/10-negocio/valores-normativos/alcabala.md} transcribe el TUO LTM:
 *       el art. 27 inafecta <i>siete</i> supuestos por la naturaleza de la transferencia, el art.
 *       28 inafecta <i>cinco</i> por <b>quien adquiere</b> (Gobierno Central, entidades religiosas,
 *       bomberos, universidades) y el art. 22 inafecta la primera venta de una empresa constructora
 *       por <b>quien vende</b>. Un enumerado del tipo del acto no ve ni al adquirente ni al
 *       transferente. Ese mismo archivo lo dice de si mismo: «la logica de que campo del hecho
 *       imponible hay que mirar para aplicar cada una ... no esta en el esquema — es logica de
 *       negocio, no un parametro».
 *   <li><b>De los nueve, solo UNO cuadra letra por letra con un literal del art. 27.</b> {@link
 *       #ANTICIPO_DE_LEGITIMA} con «Los anticipos de legitima». {@link #SUCESION} y {@link
 *       #HERENCIA} <i>se parecen</i> a «las que se produzcan por causa de muerte», y {@link
 *       #ADJUDICACION} y {@link #REMATE} son las dos cosas a la vez —la adjudicacion por division y
 *       particion de la masa hereditaria esta inafecta (art. 27, sexto literal) y la que sale de un
 *       remate judicial no—. Derivar la afectacion del tipo produciria, en al menos dos de los
 *       nueve, una clasificacion <b>plausible y equivocada</b>, y lo que esta al otro lado es el 3
 *       % del art. 25: cobrado a quien no lo debe, o perdonado a quien si.
 * </ol>
 *
 * <p>Y no hay de donde leerlo: {@code publicacion/parametros-2026.csv} publica {@code
 * ALCABALA_ALICUOTA} y {@code ALCABALA_TRAMO_INAFECTO_UIT}, y <b>ninguna</b> fila {@code
 * ALCABALA_INAFECTACION}. Mientras las doce inafectaciones no esten publicadas con su dimension,
 * este enumerado solo dice <b>que acto fue</b>.
 *
 * <h2>Lo que si aporta</h2>
 *
 * <p>Que el acto quede clasificado por un vocabulario cerrado. Hasta #542 el campo era texto libre:
 * {@code POST /rentas/transferencias/predio} guardaba {@code XXXX} con un 201, y una compraventa
 * escrita {@code COMPRAVENTA} —que es lo que sembraba {@code ejemplos/transferencias.csv}— no la
 * encuentra ninguna consulta que pregunte por {@code COMPRA_VENTA}.
 *
 * <h2>El cotejo con el rotulo del manual, para quien porte la pantalla</h2>
 *
 * <p><b>Ni uno solo de los doce rotulos del prototipo coincide letra por letra con el nombre que
 * hay que mandar</b>, y por eso este cotejo se deja escrito aqui en vez de en la interfaz. La
 * pantalla que registre el acto tiene que traducir <b>con una tabla</b> —una entrada por rotulo—,
 * nunca quitando tildes ni cambiando guiones con una funcion: eso convertiria cualquier texto
 * parecido en un valor «traducido», y aqui lo que se clasifica es un acto que se imprime y del que
 * cuelga un impuesto. Es el precedente literal de {@code TIPO_DE_CERTIFICADO_DEL_BACKEND}, y la
 * razon por la que #427 se nego a traducir «ACTIVA» a {@code VIGENTE}.
 *
 * <p>El rotulo va como el catalogo portado lo escribe —{@code
 * catalogo/pantallas/rentas-registro.generado.ts}, campo {@code tipoDeActo} de cada pantalla—; el
 * valor, como esta clase lo nombra:
 *
 * <pre>
 * «Transferencia de predio» (7)          «Transferencia de vehiculo» (5)
 *   COMPRA-VENTA         -> COMPRA_VENTA   COMPRA-VENTA  -> COMPRA_VENTA
 *   DONACION (con tilde) -> DONACION       DONACION (con tilde) -> DONACION
 *   PERMUTA              -> PERMUTA        REMATE        -> REMATE
 *   ANTICIPO DE LEGITIMA -> ANTICIPO_DE_LEGITIMA         HERENCIA -> HERENCIA
 *     (con tilde en LEGITIMA)              DACION EN PAGO (con tilde) -> DACION_EN_PAGO
 *   ADJUDICACION (con tilde) -> ADJUDICACION
 *   DACION EN PAGO (con tilde) -> DACION_EN_PAGO
 *   SUCESION (con tilde) -> SUCESION
 * </pre>
 *
 * <p><b>Las diferencias son de tres clases, y ninguna es inofensiva.</b> (1) La <b>tilde</b>: siete
 * de los doce rotulos la llevan y ningun nombre de aqui puede llevarla (Checkstyle prohibe tildes
 * en identificadores). (2) El <b>separador</b>: el manual escribe «COMPRA-VENTA» con guion y
 * «ANTICIPO DE LEGITIMA» y «DACION EN PAGO» con espacios, donde aqui va el guion bajo. (3) Y el
 * caso que ninguna regla de escritura arregla: <b>{@code COMPRAVENTA} sin nada en medio</b>, que es
 * lo que sembraba {@code ejemplos/transferencias.csv} y lo que el issue nombra como el error
 * realista — se parece a las dos anteriores y no es ninguna.
 *
 * <p><b>Y REMATE y HERENCIA solo estan en la pantalla del vehiculo</b>: una tabla escrita mirando
 * solo el desplegable de predio —los siete que el issue listaba— deja esos dos actos sin poder
 * registrarse, con un 422 que llega despues de rellenar el formulario entero.
 */
public enum TipoTransferencia {

    /** Compra-venta. En las dos pantallas. */
    COMPRA_VENTA,

    /** Donacion. En las dos pantallas. */
    DONACION,

    /** Permuta. Solo en la de predio. */
    PERMUTA,

    /**
     * Anticipo de legitima. Solo en la de predio.
     *
     * <p>Es el unico de los nueve que cuadra letra por letra con un literal del art. 27 del TUO LTM
     * («Los anticipos de legitima»). Aun asi no marca nada: ver el javadoc de la clase.
     */
    ANTICIPO_DE_LEGITIMA,

    /** Adjudicacion. Solo en la de predio. */
    ADJUDICACION,

    /** Dacion en pago. En las dos pantallas. */
    DACION_EN_PAGO,

    /** Sucesion. Solo en la de predio; la de vehiculo llama {@link #HERENCIA} a lo mismo. */
    SUCESION,

    /** Remate. Solo en la de vehiculo. */
    REMATE,

    /** Herencia. Solo en la de vehiculo; la de predio llama {@link #SUCESION} a lo mismo. */
    HERENCIA;

    /**
     * El tipo con ese nombre, en cualquier caja y con espacios alrededor.
     *
     * <p><b>Una sola definicion para los cuatro sitios que la necesitan</b>: los dos controladores
     * que registran el acto, el importador de la siembra y la lectura del repositorio. El mensaje
     * es el que el borde publica como 422 —{@code Tipo de transferencia desconocido: 'XXXX'}—, y
     * nombra el valor recibido sin nombrar tabla, columna ni restriccion (RNF-033).
     *
     * <p><b>No es una lectura tolerante</b>: no quita tildes ni guiones ni espacios de en medio. Un
     * rotulo del manual que no sea exactamente uno de estos nueve nombres no entra, y el que lo
     * llame tiene que decidir que hace con el —que es lo que hace {@code
     * TIPO_DE_TRANSFERENCIA_DEL_BACKEND} en la interfaz, con una tabla y no con una funcion—.
     */
    public static TipoTransferencia de(String texto) {
        Objects.requireNonNull(texto, "La transferencia necesita su tipo");
        try {
            return valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new IllegalArgumentException(
                    "Tipo de transferencia desconocido: '" + texto + "'");
        }
    }
}
