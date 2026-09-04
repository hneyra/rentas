package kamayuk.rentas.tesoreria.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.cuentacorriente.AbonadoEnElLibro;
import kamayuk.rentas.cuentacorriente.AbonoAsentado;
import kamayuk.rentas.cuentacorriente.ConciliacionDeCaja;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.ReversionDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;

/**
 * Un {@link RegistroDeAbonos} de mentira que <b>se comporta como el libro</b>: lo que abona lo
 * descuenta, y una segunda cobranza de la misma obligacion ya no encuentra nada.
 *
 * <p>Reproducir esa propiedad en el doble no es adorno. Lo que se prueba con el es que la caja
 * <b>no traiga su propia cifra</b>: si {@code CobrarDeuda} guardara el importe que leyo antes en
 * vez de usar el que el libro devuelve, la segunda cobranza seguiria emitiendo un recibo con
 * dinero. La demostracion de que el bloqueo funciona bajo concurrencia real es otra cosa, y esa la
 * hace {@code CajaJdbcTest} contra PostgreSQL.
 */
public final class LibroDeMentira implements RegistroDeAbonos, ConciliacionDeCaja {

    private final Map<SeleccionDeObligacion, Dinero[]> deuda = new LinkedHashMap<>();
    private final List<String> documentosOrigen = new ArrayList<>();
    private final Map<String, List<AbonoAsentado>> cobradoPorDocumento = new LinkedHashMap<>();
    private final List<String> documentosReversados = new ArrayList<>();

    /**
     * Lo que cada documento <b>sigue</b> abonando (#36).
     *
     * <p>Reproduce la semantica del repositorio de verdad, que es la que hace util el cuadre del
     * cierre: al reversar, el documento de la cobranza pasa a cero —sus abonos siguen en el libro,
     * pero alguien los reverso— en vez de desaparecer. Un doble que lo borrara haria pasar en verde
     * un cuadre que en produccion distinguiria «recibo anulado» de «recibo que nunca abono», y esos
     * dos casos llevan a sitios distintos.
     */
    private final Map<String, Dinero> abonadoPorDocumento = new LinkedHashMap<>();

    /** Declara la deuda de una obligacion, con su desglose. */
    public LibroDeMentira con(
            SeleccionDeObligacion obligacion,
            Dinero insoluto,
            Dinero reajuste,
            Dinero interes,
            Dinero gasto) {
        deuda.put(obligacion, new Dinero[] {insoluto, reajuste, interes, gasto});
        return this;
    }

    /** Los documentos con que se asentaron los abonos: sirve para ver que numero llego al libro. */
    public List<String> documentosOrigen() {
        return List.copyOf(documentosOrigen);
    }

    /** Los documentos que se reversaron, en orden. */
    public List<String> documentosReversados() {
        return List.copyOf(documentosReversados);
    }

    /** Si esa obligacion vuelve a tener deuda: es lo que la anulacion tiene que conseguir. */
    public boolean tieneDeuda(SeleccionDeObligacion obligacion) {
        return deuda.containsKey(obligacion);
    }

    @Override
    public List<AbonoAsentado> abonarPagoIntegro(
            long contribuyenteId,
            List<SeleccionDeObligacion> obligaciones,
            LocalDate fechaDePago,
            String documentoOrigen,
            Observacion observacion) {

        List<AbonoAsentado> abonados = new ArrayList<>();
        for (SeleccionDeObligacion obligacion : obligaciones) {
            Dinero[] partes = deuda.get(obligacion);
            if (partes == null) {
                continue;
            }
            // Abonar lo deja en cero: el libro no vuelve a tener nada que cobrar ahi.
            deuda.remove(obligacion);
            abonados.add(
                    new AbonoAsentado(
                            obligacion, fechaDePago, partes[0], partes[1], partes[2], partes[3]));
        }
        if (abonados.isEmpty()) {
            throw new SinDeudaQueAbonar(
                    "Ninguna de las "
                            + obligaciones.size()
                            + " obligaciones marcadas tenia deuda al "
                            + fechaDePago);
        }
        documentosOrigen.add(documentoOrigen);
        cobradoPorDocumento.put(documentoOrigen, List.copyOf(abonados));
        Dinero total = Dinero.CERO;
        for (AbonoAsentado abono : abonados) {
            total = total.mas(abono.total());
        }
        abonadoPorDocumento.merge(documentoOrigen, total, Dinero::mas);
        return List.copyOf(abonados);
    }

    /**
     * Deshace lo que ese documento abono, devolviendo la deuda a donde estaba.
     *
     * <p>Que el doble <b>reponga la deuda</b> es lo que hace util la prueba del caso de uso: si
     * {@code AnularRecibo} se saltara la reversion, la obligacion seguiria en cero y la
     * comprobacion de que vuelve a estar pendiente se pondria roja.
     */
    @Override
    public ReversionDeAbonos reversarAbonos(
            String documentoOrigen,
            String documentoDeLaReversion,
            LocalDate fecha,
            Observacion observacion) {

        if (documentoOrigen.equalsIgnoreCase(documentoDeLaReversion)) {
            throw new IllegalArgumentException(
                    "La reversion tiene que llevar un documento de origen distinto del que"
                            + " reversa");
        }
        List<AbonoAsentado> abonados = cobradoPorDocumento.remove(documentoOrigen);
        if (abonados == null || abonados.isEmpty()) {
            throw new SinAbonosQueReversar(
                    "El documento '" + documentoOrigen + "' no origino ningun asiento reversable");
        }
        Dinero abonado = Dinero.CERO;
        for (AbonoAsentado abono : abonados) {
            deuda.put(
                    abono.obligacion(),
                    new Dinero[] {
                        abono.insoluto(), abono.reajuste(), abono.interes(), abono.gasto()
                    });
            abonado = abonado.mas(abono.total());
        }
        documentosReversados.add(documentoOrigen);
        // Lo que ese documento SIGUE abonando pasa a ser cero: sus abonos estan reversados.
        abonadoPorDocumento.put(documentoOrigen, Dinero.CERO);
        return new ReversionDeAbonos(abonados.size(), abonado, fecha);
    }

    /**
     * Lo que cada documento sigue abonando, para el cuadre del cierre (#36).
     *
     * <p>Un documento que no asento nada no aparece en el mapa, igual que en el repositorio de
     * verdad: es {@link AbonadoEnElLibro} quien decide que eso vale cero. Reproducirlo asi es lo
     * que hace que la prueba del cuadre distinga «no abono» de «abono cero», que es exactamente la
     * distincion que un recibo de tasas obliga a hacer.
     */
    @Override
    public AbonadoEnElLibro abonadoPor(
            java.util.Collection<String> documentosOrigen, LocalDate aLaFecha) {
        Map<String, Dinero> encontrados = new LinkedHashMap<>();
        for (String documento : documentosOrigen) {
            Dinero abonado = abonadoPorDocumento.get(documento);
            if (abonado != null) {
                encontrados.put(documento, abonado);
            }
        }
        return new AbonadoEnElLibro(encontrados, aLaFecha);
    }
}
