package kamayuk.rentas.tesoreria.infraestructura;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.AvanceDeCaja;
import kamayuk.rentas.tesoreria.RecaudadoEnCaja;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Lo que la ventanilla lleva cobrado hoy, pedido a {@code caja} (P5D).
 *
 * <p>Sustituye a {@code AvanceDeCajaTesoreria}, que agregaba {@code recibo_detalle} por turno. Esas
 * tablas se fueron con `V7`. <b>El puerto no cambio</b>: {@code PanelDeRecaudacion} de {@code
 * indicadores} —#56, la pantalla de inicio— no cambio ni una linea.
 *
 * <h2>La ruta y sus parametros, decididos con la medida delante (#27 AC-1)</h2>
 *
 * <p>Hasta #27 esta clase pedia {@code ?dia=&aLaFecha=}, y {@code caja} <b>no admite ninguno de los
 * dos</b>: medido contra las dos aplicaciones levantadas, contesta {@code 422 «Parametros
 * desconocidos: 'aLaFecha', 'dia'»} y con el {@code GET /rentas/api/v1/indicadores/recaudacion} en
 * <b>500</b>. La ruta existia —contestaba 422 y no 404—; lo que no cuadraba eran los nombres.
 *
 * <p>De las dos salidas que #27 planteaba se toma la segunda —<b>este consumidor pasa a pedir con
 * los que {@code caja} ya admite</b>—, y no por ser la mas barata:
 *
 * <ul>
 *   <li>{@code caja} ya publica exactamente esta pregunta. Su {@code GET /recaudacion/avance} suma
 *       un <b>rango</b> con {@code desde} y {@code hasta}, y «el dia» es ese rango con los dos
 *       extremos iguales. Anadirle un {@code dia} seria una segunda forma de decir lo mismo, y dos
 *       formas de acotar el mismo periodo acaban discrepando en el caso raro.
 *   <li>{@code aLaFecha} <b>no puede ser un parametro de la peticion</b>, y eso es una decision del
 *       proveedor que hay que respetar: es la fecha a la que {@code caja} leyo, la pone su reloj y
 *       viaja con la cifra (regla 9, RNF-075). Un consumidor que pudiera dictarla estaria pidiendo
 *       que la respuesta se fechara con un dia que no es el de la lectura.
 * </ul>
 *
 * <p>Lo que este lado hace con la {@code aLaFecha} que recibe el puerto es <b>comprobarla</b>, no
 * mandarla: ver {@link #delDia}.
 *
 * <h2>Aqui NO hay 404 que valga</h2>
 *
 * <p>«Hoy no ha entrado nada todavia» es una respuesta legitima —la que un panel da a las ocho de
 * la manana— y {@code caja} la da con ceros. Un 404, o una conexion que no contesta, es otra cosa,
 * y sale como {@link ClienteHttpDeCaja.CajaInalcanzable}: publicar ceros ahi pondria el panel de
 * recaudacion en «0,00 cobrado hoy» con la ventanilla cobrando, que es una cifra plausible y falsa
 * al lado de las del libro, que si estarian bien (#48, #56).
 *
 * <h2>Y tampoco hay campo que se lea con valor por omision (#41)</h2>
 *
 * <p>{@code caja} publica lo cobrado y lo anulado como {@code ImporteActualizado} —{@code {importe,
 * actualizadoA}}—, no como escalares. Leerlos con {@code path("cobrado").asString("0")} <b>no
 * falla</b>: un {@code ObjectNode} no es un escalar y {@code asString} devuelve el valor por
 * omision, asi que el panel publicaria dos ceros sin un solo error. Las cuatro cifras se leen con
 * {@link ClienteHttpDeCaja#exigirDinero} y {@link ClienteHttpDeCaja#exigirFecha}, que <b>fallan
 * nombrando el campo</b>; y la {@code CajaInalcanzable} que sale de ahi la caza {@code
 * PanelDeRecaudacion}, de modo que el panel dice que no pudo leer el avance en vez de inventarse un
 * cero (#41 AC-5).
 */
@Component
public class AvanceDeCajaHttp implements AvanceDeCaja {

    private final ClienteHttpDeCaja caja;

    public AvanceDeCajaHttp(ClienteHttpDeCaja caja) {
        this.caja = caja;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Las dos fechas que vuelven se <b>comprueban</b> contra las que se pidieron, igual que
     * {@code RiesgoYItseDelPredio} hace con {@code catastro} desde `catastro`#18:
     *
     * <ul>
     *   <li>{@code desde} y {@code hasta} tienen que ser el dia que se pidio. Un rango distinto es
     *       una suma de otros dias publicada como la de hoy, y no se distingue de la buena.
     *   <li>{@code aLaFecha} tiene que ser la del puerto. Es la fecha con que la cifra se publica
     *       (regla 9): si el reloj de {@code caja} dice otra cosa —la medianoche, un despliegue con
     *       otra zona horaria—, la respuesta no es «a la fecha» que quien pregunta necesita, y
     *       publicarla como tal es la cifra plausible y falsa otra vez.
     * </ul>
     *
     * <p>Cuando no cuadran, esto <b>no devuelve nada</b>: lanza. El panel ya sabe quedarse sin esa
     * cifra y decirlo (#25); lo que no puede es publicar una que no leyo.
     */
    @Override
    public RecaudadoEnCaja delDia(LocalDate dia, LocalDate aLaFecha) {
        String que = "leer el avance de caja del " + dia;
        try {
            // `desde` y `hasta` con el mismo dia: es el rango de un dia, que es lo que
            // `caja` sabe sumar. Ver la cabecera de la clase.
            JsonNode cuerpo = caja.pedir("/recaudacion/avance?desde=" + dia + "&hasta=" + dia, que);

            LocalDate desde = ClienteHttpDeCaja.exigirFecha(cuerpo, "desde", que);
            LocalDate hasta = ClienteHttpDeCaja.exigirFecha(cuerpo, "hasta", que);
            if (!desde.equals(dia) || !hasta.equals(dia)) {
                throw new ClienteHttpDeCaja.CajaInalcanzable(
                        que
                                + ": se pidio el rango ["
                                + dia
                                + ", "
                                + dia
                                + "] y `caja` contesta ["
                                + desde
                                + ", "
                                + hasta
                                + "]. Es la suma de otros dias, y publicada como la de hoy no se"
                                + " distinguiria de la buena",
                        null);
            }

            LocalDate respondidaA = ClienteHttpDeCaja.exigirFecha(cuerpo, "aLaFecha", que);
            if (!respondidaA.equals(aLaFecha)) {
                throw new ClienteHttpDeCaja.CajaInalcanzable(
                        que
                                + ": se pidio a la fecha "
                                + aLaFecha
                                + " y `caja` leyo a la fecha "
                                + respondidaA
                                + ". Toda cifra indica su fecha (regla 9, RNF-075) y esta no es la"
                                + " que se pregunto",
                        null);
            }

            Dinero cobrado = ClienteHttpDeCaja.exigirDinero(cuerpo, "cobrado.importe", que);
            Dinero anulado = ClienteHttpDeCaja.exigirDinero(cuerpo, "anulado.importe", que);
            // Los dos componentes del `ImporteActualizado`, no solo el importe (#41 AC-1). La
            // fecha de cada cifra es parte de la cifra: si `caja` fechara lo cobrado un dia y
            // lo anulado otro, el neto seria una resta entre dos instantes distintos.
            exigirFechada(cuerpo, "cobrado.actualizadoA", respondidaA, que);
            exigirFechada(cuerpo, "anulado.actualizadoA", respondidaA, que);

            return new RecaudadoEnCaja(cobrado, anulado, dia, respondidaA);
        } catch (ClienteHttpDeCaja.CajaInalcanzable noSePudo) {
            // Se traduce al tipo del PUERTO, no al del transporte: es lo mismo que
            // `OrdenesDeCobroHttp` hace desde P5D, y lo que permite que `indicadores` la cace sin
            // conocer este subpaquete. El MOTIVO viaja intacto, porque quien lo lee en el registro
            // necesita separar «falta una variable de entorno» de «la caja se cayo» (#25, AC-4).
            throw new AvanceDeCaja.CajaInalcanzable(
                    noSePudo.motivo(), noSePudo.getMessage(), noSePudo);
        }
    }

    /** Que la cifra venga fechada, y con la misma fecha con la que se contesta. */
    private static void exigirFechada(
            JsonNode cuerpo, String camino, LocalDate aLaFecha, String que) {
        LocalDate suya = ClienteHttpDeCaja.exigirFecha(cuerpo, camino, que);
        if (!suya.equals(aLaFecha)) {
            throw new ClienteHttpDeCaja.CajaInalcanzable(
                    que
                            + ": `caja` fecha «"
                            + camino
                            + "» el "
                            + suya
                            + " y contesta a la fecha "
                            + aLaFecha
                            + ". Dos cifras del mismo avance con dos fechas no se pueden restar",
                    null);
        }
    }
}
