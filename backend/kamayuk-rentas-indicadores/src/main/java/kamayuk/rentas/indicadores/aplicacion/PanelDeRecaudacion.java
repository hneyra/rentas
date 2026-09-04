package kamayuk.rentas.indicadores.aplicacion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeSet;
import kamayuk.rentas.cuentacorriente.CargadoEnElLibro;
import kamayuk.rentas.cuentacorriente.CarteraDelLibro;
import kamayuk.rentas.cuentacorriente.CarteraPendiente;
import kamayuk.rentas.cuentacorriente.PendienteDeUnTributo;
import kamayuk.rentas.cuentacorriente.RecaudacionDeUnTributo;
import kamayuk.rentas.cuentacorriente.RecaudacionDelLibro;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.indicadores.dominio.AvanceDeCobranza;
import kamayuk.rentas.indicadores.dominio.AvanceDeRecaudacion;
import kamayuk.rentas.indicadores.dominio.Cartera;
import kamayuk.rentas.indicadores.dominio.FormatoDeCifra;
import kamayuk.rentas.indicadores.dominio.Indicador;
import kamayuk.rentas.indicadores.dominio.LineaDeCartera;
import kamayuk.rentas.tesoreria.AvanceDeCaja;
import kamayuk.rentas.tesoreria.RecaudadoEnCaja;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compone el panel de recaudacion del ejercicio (#56, RF-130).
 *
 * <h2>No calcula: agrega</h2>
 *
 * <p>Las cuatro lecturas que hace son de <b>APIs publicas</b> de otros modulos, y ninguna de ellas
 * es una tabla. Lo recaudado, lo cargado y la cartera pendiente vienen del libro —la cartera desde
 * #639: antes salia de la proyeccion del saldo (#23), que no sabe aplicar una fecha de corte—; el
 * avance del dia, de la caja (#36). Este servicio no suma asientos, no calcula deuda y no consulta
 * el esquema: si lo hiciera, la pantalla de inicio podria decir una cifra y la de recaudacion otra,
 * y no habria forma de saber cual esta mal.
 *
 * <h2>Una sola transaccion, una sola foto</h2>
 *
 * <p>{@code @Transactional(readOnly = true)} en el metodo, y las cuatro lecturas se unen a ella
 * —{@code REQUIRED} es la propagacion por omision—. Con cuatro transacciones separadas, cada cifra
 * saldria de un instante distinto y el panel podria mostrar una cartera que ya recogio un pago que
 * lo recaudado todavia no cuenta. Ademas, sin transaccion no hay {@code SET LOCAL} y la politica
 * RLS no puede evaluar {@code app.municipalidad_id}: la consulta <b>falla</b>.
 *
 * <p><b>Sin bloquear nada.</b> Ninguna de las cuatro pide {@code FOR UPDATE}. El panel se mira
 * mientras la ventanilla cobra, y una lectura que tomara la fila del turno pondria la cola a
 * esperar por la pantalla de inicio.
 *
 * <h2>Lo que el panel NO dice</h2>
 *
 * <p>No hay <b>meta</b> de recaudacion ni porcentaje de cumplimiento. El prototipo dibuja las dos y
 * el esquema no tiene de donde sacarlas: una meta se aprueba y se firma, y darle como valor «lo
 * cargado» produciria un cumplimiento que nadie firmo (regla 5). Lo que si consta es contra que se
 * cobra —los cargos del libro—, y eso es lo que el panel publica.
 *
 * <p>Tampoco dice «deuda»: lo que publica como cartera es el <b>insoluto pendiente a la fecha de
 * corte</b>, que es la suma de {@code deudaActualizadaA(fecha).insoluto()} sobre el padron. La
 * deuda actualizada entera —con su reajuste y su interes— exigiria calcularlos obligacion por
 * obligacion en cada carga de la pantalla, que es exactamente lo que el AC 4 de #56 prohibe. Hoy
 * las dos cifras coinciden porque la unica {@code PoliticaDeMora} implementada no acumula nada
 * (D-02a), y por eso {@code CarteraCuadraConLaConsultaJdbcTest} compara el <b>insoluto</b> y no el
 * total: comparar totales pasaria hoy por un motivo que dejaria de ser cierto.
 */
@Service
public class PanelDeRecaudacion {

    /** El texto que acompaña a una cifra que no se puede dar. */
    private static final String SIN_BASE = "sin cargos asentados en el ejercicio";

    private final RecaudacionDelLibro recaudacion;
    private final CarteraDelLibro cartera;
    private final AvanceDeCaja caja;

    public PanelDeRecaudacion(
            RecaudacionDelLibro recaudacion, CarteraDelLibro cartera, AvanceDeCaja caja) {
        this.recaudacion = recaudacion;
        this.cartera = cartera;
        this.caja = caja;
    }

    /**
     * El panel del ejercicio, leido a esa fecha y a ese instante.
     *
     * <p>Las dos marcas de tiempo entran como argumento y no se leen del reloj aqui: es lo que
     * permite pedir el panel «tal como estaba» en una prueba y obtener siempre lo mismo. Quien
     * consulta el reloj es el controlador, una sola vez, y las dos marcas describen la misma
     * lectura.
     */
    @Transactional(readOnly = true)
    public AvanceDeRecaudacion del(Ejercicio ejercicio, LocalDate aLaFecha, Instant leidoEn) {
        Objects.requireNonNull(ejercicio, "El panel siempre es de un ejercicio");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        Objects.requireNonNull(leidoEn, "El panel dice tambien a que hora se leyo");

        LocalDate primerDia = LocalDate.of(ejercicio.valor(), 1, 1);
        LocalDate ultimoDia = LocalDate.of(ejercicio.valor(), 12, 31);

        RecaudadoEnElLibro recaudado = recaudacion.recaudadoDeTodos(primerDia, ultimoDia, aLaFecha);
        CargadoEnElLibro cargado = cartera.cargadoPorTributo(ejercicio, aLaFecha);
        CarteraPendiente pendiente = cartera.pendientePorTributo(ejercicio, aLaFecha);
        RecaudadoEnCaja hoy = caja.delDia(aLaFecha, aLaFecha);

        return new AvanceDeRecaudacion(
                ejercicio,
                aLaFecha,
                leidoEn,
                // Lo emitido del ejercicio, como campo (#549). La misma cifra sigue dentro de la
                // nota del KPI de avance —el texto es para leer—, y `elCampoDiceLoMismoQueLaNota`
                // compara las dos: mientras salgan del mismo `cargado.total()` no pueden divergir.
                cargado.total(),
                // La fecha con la que el libro contesto, no la del panel. Hoy son la misma por
                // construccion; declararla asi es lo que impide que dejen de serlo en silencio.
                cargado.aLaFecha(),
                indicadores(ejercicio, recaudado, cargado, pendiente, hoy, aLaFecha),
                List.of(
                        porTributo(ejercicio, recaudado, cargado, pendiente, aLaFecha),
                        porMes(ejercicio, recaudado, cargado, aLaFecha)));
    }

    // ------------------------------------------------------------------
    //  Las cifras grandes
    // ------------------------------------------------------------------

    private List<Indicador> indicadores(
            Ejercicio ejercicio,
            RecaudadoEnElLibro recaudado,
            CargadoEnElLibro cargado,
            CarteraPendiente pendiente,
            RecaudadoEnCaja hoy,
            LocalDate aLaFecha) {

        Dinero delEjercicio = recaudadoDelPropioEjercicio(ejercicio, recaudado);
        OptionalInt avance = AvanceDeCobranza.de(delEjercicio, cargado.total());

        List<Indicador> indicadores = new ArrayList<>();
        indicadores.add(
                new Indicador(
                        "Recaudado " + ejercicio,
                        FormatoDeCifra.importe(recaudado.total()),
                        FormatoDeCifra.cantidad(recaudado.abonos())
                                + " abonos · "
                                + FormatoDeCifra.importe(delEjercicio)
                                + " de deuda del propio ejercicio",
                        recaudado.total(),
                        aLaFecha));

        indicadores.add(
                new Indicador(
                        "Avance de cobranza",
                        avance.isPresent()
                                ? FormatoDeCifra.porcentaje(avance.getAsInt())
                                : FormatoDeCifra.SIN_CIFRA,
                        avance.isPresent()
                                // Contra lo CARGADO, nunca contra una meta: no hay ninguna
                                // en el esquema y la que se inventara acabaria en un informe.
                                ? "de " + FormatoDeCifra.importe(cargado.total()) + " cargados"
                                : SIN_BASE + ": no hay avance que medir",
                        null,
                        aLaFecha));

        indicadores.add(
                new Indicador(
                        "Cartera pendiente",
                        FormatoDeCifra.importe(pendiente.total()),
                        notaDeLaCartera(pendiente),
                        pendiente.total(),
                        aLaFecha));

        indicadores.add(
                new Indicador(
                        "Recaudado hoy en caja",
                        FormatoDeCifra.importe(hoy.neto()),
                        "cobrado "
                                + FormatoDeCifra.importe(hoy.cobrado())
                                + " · anulado "
                                + FormatoDeCifra.importe(hoy.anulado()),
                        hoy.neto(),
                        hoy.aLaFecha()));

        return List.copyOf(indicadores);
    }

    /**
     * Cuantas obligaciones componen la cartera y a que fecha esta cortada.
     *
     * <p>Hasta #639 esta nota decia «insoluto proyectado desde …», porque la cifra salia de un
     * cache (ADR-0006) que podia llevar dias parado. Ahora sale del libro con la fecha de corte
     * aplicada, asi que lo unico que hay que declarar es esa fecha —que es ademas la que hace que
     * la cifra cambie: la cartera al 1 de junio y al 1 de diciembre no son la misma—.
     */
    private static String notaDeLaCartera(CarteraPendiente pendiente) {
        if (pendiente.obligaciones() == 0) {
            return "sin obligaciones pendientes en el ejercicio";
        }
        return FormatoDeCifra.cantidad(pendiente.obligaciones())
                + " obligaciones · insoluto pendiente al "
                + pendiente.aLaFecha();
    }

    // ------------------------------------------------------------------
    //  Los bloques
    // ------------------------------------------------------------------

    /**
     * Una fila por tributo, con lo recaudado de su ejercicio y lo que sigue pendiente.
     *
     * <p>Los tributos son la <b>union</b> de los tres agregados, no los de uno solo. Un tributo con
     * cargos y sin un solo pago tiene que aparecer —es justo el que hay que mirar—, y uno cobrado
     * entero, cuya cartera ya esta a cero, tambien.
     *
     * <h2>La barra sale del libro por sus dos lados</h2>
     *
     * <p>{@code cobrado / cargado}, las dos cifras de la misma tabla y con el mismo criterio de
     * reversion. La alternativa que parecia mejor —{@code (cargado − pendiente) / cargado}, usando
     * la proyeccion— tiene un modo de fallo silencioso: la proyeccion <b>no tiene fila</b> ni
     * cuando la obligacion esta cancelada ni cuando nadie la ha proyectado todavia, y esas dos
     * cosas se leen igual. Un tributo recien emitido, con su cargo puesto y sin proyectar,
     * dibujaria una barra al <b>100 %</b> sin que se hubiera cobrado un centimo. Con las dos cifras
     * del libro, el caso imposible no existe.
     *
     * <p>La proyeccion sigue estando donde no puede mentir: en el {@code sub} de la fila y en el
     * indicador de cartera, cada uno con la fecha desde la que esta proyectado.
     */
    private static Cartera porTributo(
            Ejercicio ejercicio,
            RecaudadoEnElLibro recaudado,
            CargadoEnElLibro cargado,
            CarteraPendiente pendiente,
            LocalDate aLaFecha) {

        Map<String, Dinero> recaudadoPorTributo = new LinkedHashMap<>();
        for (RecaudacionDeUnTributo linea : recaudado.lineas()) {
            if (linea.ejercicio().equals(ejercicio)) {
                recaudadoPorTributo.merge(linea.tributo(), linea.recaudado(), Dinero::mas);
            }
        }
        Map<String, PendienteDeUnTributo> pendientePorTributo = new LinkedHashMap<>();
        for (PendienteDeUnTributo linea : pendiente.lineas()) {
            pendientePorTributo.put(linea.tributo(), linea);
        }

        TreeSet<String> tributos = new TreeSet<>(recaudadoPorTributo.keySet());
        cargado.lineas().forEach(linea -> tributos.add(linea.tributo()));
        tributos.addAll(pendientePorTributo.keySet());

        List<LineaDeCartera> filas = new ArrayList<>();
        for (String tributo : tributos) {
            Dinero suCargado = cargado.de(tributo);
            PendienteDeUnTributo suPendiente = pendientePorTributo.get(tributo);
            Dinero pendienteDe = suPendiente == null ? Dinero.CERO : suPendiente.pendiente();
            Dinero cobrado = recaudadoPorTributo.getOrDefault(tributo, Dinero.CERO);

            filas.add(
                    new LineaDeCartera(
                            tributo,
                            detalleDelTributo(suCargado, pendienteDe),
                            FormatoDeCifra.importe(cobrado),
                            cobrado,
                            // Las dos cifras que el detalle ya decia con palabras, ahora tambien
                            // sin redactar (#549). Salen de las mismas variables que redactan el
                            // texto: no hay una segunda suma que pueda divergir de la primera.
                            suCargado,
                            pendienteDe,
                            AvanceDeCobranza.de(cobrado, suCargado),
                            aLaFecha));
        }

        return new Cartera(
                "Recaudacion por tributo",
                "Ejercicio "
                        + ejercicio
                        + " · la barra es la parte del insoluto cargado que ya se cobro",
                filas);
    }

    private static String detalleDelTributo(Dinero cargado, Dinero pendiente) {
        if (!cargado.esPositivo()) {
            return SIN_BASE;
        }
        return "cargado "
                + FormatoDeCifra.importe(cargado)
                + " · pendiente "
                + FormatoDeCifra.importe(pendiente);
    }

    /**
     * Una fila por mes con movimiento, con lo cobrado en el.
     *
     * <p>El mes es el de la <b>fecha valor</b> del abono, no el del ejercicio de la obligacion: un
     * recibo de marzo que cobra deuda de 2025 cae en marzo, y en el ejercicio 2025. La barra mide
     * la misma base que el bloque anterior —lo cargado del ejercicio—, para que las dos se puedan
     * comparar: doce barras que sumaran 100 % entre ellas dirian otra cosa y se leerian igual.
     */
    private static Cartera porMes(
            Ejercicio ejercicio,
            RecaudadoEnElLibro recaudado,
            CargadoEnElLibro cargado,
            LocalDate aLaFecha) {

        Map<Integer, Dinero> importes = new LinkedHashMap<>();
        Map<Integer, Long> abonos = new LinkedHashMap<>();
        for (RecaudacionDeUnTributo linea : recaudado.lineas()) {
            importes.merge(linea.mes(), linea.recaudado(), Dinero::mas);
            abonos.merge(linea.mes(), linea.abonos(), Long::sum);
        }

        List<LineaDeCartera> filas = new ArrayList<>();
        for (Integer mes : new TreeSet<>(importes.keySet())) {
            Dinero delMes = importes.getOrDefault(mes, Dinero.CERO);
            filas.add(
                    new LineaDeCartera(
                            "Mes " + mes,
                            FormatoDeCifra.cantidad(abonos.getOrDefault(mes, 0L)) + " abonos",
                            FormatoDeCifra.importe(delMes),
                            delMes,
                            // Un mes no tiene cargado ni pendiente propios: lo cargado es del
                            // ejercicio entero y la cartera no se reparte por mes de cobro. Van
                            // NULOS y no en cero, porque un cero aqui afirmaria que ese mes cargo
                            // cero (#549, el precedente de `avanceConocido`).
                            null,
                            null,
                            AvanceDeCobranza.de(delMes, cargado.total()),
                            aLaFecha));
        }

        return new Cartera(
                "Recaudacion por mes",
                "Ejercicio "
                        + ejercicio
                        + " · la barra es la parte del insoluto cargado que entro ese mes",
                filas);
    }

    /**
     * Lo cobrado durante el ejercicio que corresponde a deuda del <b>propio</b> ejercicio.
     *
     * <p>Es distinto del total, y las dos cifras son ciertas: por caja entra tambien lo que se
     * cobra de anos anteriores. Publicar solo el total haria pensar que todo lo recaudado
     * corresponde a la emision del ano, que es la lectura que un panel de inicio invita a hacer.
     */
    private static Dinero recaudadoDelPropioEjercicio(
            Ejercicio ejercicio, RecaudadoEnElLibro recaudado) {
        Dinero total = Dinero.CERO;
        for (RecaudacionDeUnTributo linea : recaudado.lineas()) {
            if (linea.ejercicio().equals(ejercicio)) {
                total = total.mas(linea.recaudado());
            }
        }
        return total;
    }
}
