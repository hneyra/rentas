package kamayuk.rentas.sanciones.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.cuentacorriente.RecaudacionDeUnTributo;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.PuntoDeFirma;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.sanciones.dominio.ConstanciaLibre;
import kamayuk.rentas.sanciones.dominio.LineaDelResumen;
import kamayuk.rentas.sanciones.dominio.NotificacionDelPadron;
import kamayuk.rentas.sanciones.dominio.PapeletaDelPadron;
import kamayuk.rentas.sanciones.dominio.ResumenDePapeletas;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se imprime en los padrones y resúmenes de sanciones, sin decir en qué formato (#53,
 * RF-073, RF-074, RF-132).
 *
 * <h2>Un solo sitio para los cinco reportes</h2>
 *
 * <p>Los cinco dibujan lo mismo: una cabecera con el criterio y su fecha, una tabla y un pie. Cinco
 * clases con cinco cabeceras acabarían con cinco fechas escritas de cinco maneras, y el día que
 * alguien mirara dos reportes seguidos no sabría si dicen lo mismo.
 *
 * <h2>Estos reportes NO se registran como documento emitido</h2>
 *
 * <p>Se miran, no se emiten. Numerar cada vez que alguien abre un padrón llenaría el correlativo de
 * ruido. Lo que sí se registra —y se reimprime idéntico— son los valores, los recibos y las
 * constancias, que es para lo que existe {@code EmitirDocumento}. Es la misma decisión que {@code
 * catastro.ReporteController} dejó escrita.
 *
 * <h2>Ninguna celda numérica se recompone al dibujar</h2>
 *
 * <p>Cada cifra que sale aquí es la que el repositorio o el libro devolvieron, formateada y nada
 * más. Sumar o restar al dibujar produciría un papel que no cuadra con la pantalla de la que salió,
 * y el papel es el que se lleva quien discute.
 *
 * <h2>Sin firma digital</h2>
 *
 * <p>D-05 sigue abierta; dónde entra ya está resuelto y es {@link PuntoDeFirma}.
 */
public final class ModelosDeLosReportesDeSanciones {

    private ModelosDeLosReportesDeSanciones() {}

    private static final String SIN_DATO = "";

    /** El padrón de papeletas, el de coactiva y los dos records: la misma hoja con otro título. */
    public static ModeloDeDocumento delPadronDePapeletas(
            String titulo,
            List<Campo> criterio,
            Pagina<PapeletaDelPadron> pagina,
            LocalDate aLaFecha) {

        List<List<String>> filas = new ArrayList<>();
        for (PapeletaDelPadron papeleta : pagina.contenido()) {
            filas.add(
                    List.of(
                            papeleta.numero(),
                            papeleta.fechaInfraccion().toString(),
                            texto(papeleta.placa()),
                            papeleta.codigoInfraccion(),
                            papeleta.descripcionInfraccion(),
                            texto(papeleta.obligadoNombre()),
                            papeleta.estado().name(),
                            papeleta.importeAPagar().toString(),
                            texto(papeleta.valorNumero())));
        }

        return new ModeloDeDocumento(
                titulo,
                null,
                aLaFecha,
                conElRecuento(criterio, pagina),
                List.of(
                        Tabla.de(
                                "Papeletas",
                                List.of(
                                        "Numero",
                                        "Fecha",
                                        "Placa",
                                        "Codigo",
                                        "Infraccion",
                                        "Obligado",
                                        "Estado",
                                        "Importe del acta",
                                        "Resolucion de multa"),
                                filas)),
                pie(aLaFecha, "El importe es el del acta, congelado al registrar la papeleta."),
                null,
                null);
    }

    /** El padrón de constancias libres de infracciones. */
    public static ModeloDeDocumento delPadronDeConstancias(
            List<Campo> criterio, Pagina<ConstanciaLibre> pagina, LocalDate aLaFecha) {

        List<List<String>> filas = new ArrayList<>();
        for (ConstanciaLibre constancia : pagina.contenido()) {
            filas.add(
                    List.of(
                            constancia.numero(),
                            constancia.fechaEmision().toString(),
                            constancia.placa(),
                            constancia.verificadaAl().toString(),
                            texto(constancia.usuarioRegistro())));
        }

        return new ModeloDeDocumento(
                "Padron de constancias libres de infracciones",
                null,
                aLaFecha,
                conElRecuento(criterio, pagina),
                List.of(
                        Tabla.de(
                                "Constancias emitidas",
                                List.of(
                                        "Numero",
                                        "Emitida el",
                                        "Placa",
                                        "Verificada al",
                                        "Usuario que emitio"),
                                filas)),
                pie(
                        aLaFecha,
                        "Cada constancia acredita la situacion del vehiculo al dia que indica su"
                                + " columna «Verificada al», y no a otro."),
                null,
                null);
    }

    /** El padrón de notificaciones administrativas, con la papeleta que las siguió. */
    public static ModeloDeDocumento delPadronDeNotificaciones(
            List<Campo> criterio, Pagina<NotificacionDelPadron> pagina, LocalDate aLaFecha) {

        List<List<String>> filas = new ArrayList<>();
        for (NotificacionDelPadron fila : pagina.contenido()) {
            filas.add(
                    List.of(
                            fila.numero(),
                            fila.fecha().toString(),
                            fila.direccion(),
                            fila.estado().name(),
                            texto(fila.papeletaNumero()),
                            fila.papeletaEstado() == null ? SIN_DATO : fila.papeletaEstado().name(),
                            fila.importeDeLaPapeleta() == null
                                    ? SIN_DATO
                                    : fila.importeDeLaPapeleta().toString()));
        }

        return new ModeloDeDocumento(
                "Padron de notificaciones administrativas",
                null,
                aLaFecha,
                conElRecuento(criterio, pagina),
                List.of(
                        Tabla.de(
                                "Notificaciones emitidas",
                                List.of(
                                        "Numero",
                                        "Fecha",
                                        "Direccion",
                                        "Estado",
                                        "Papeleta",
                                        "Estado de la papeleta",
                                        "Importe del acta"),
                                filas)),
                pie(
                        aLaFecha,
                        "Las tres ultimas columnas solo tienen valor cuando a la notificacion ya"
                                + " le siguio una papeleta."),
                null,
                null);
    }

    /**
     * Un resumen de papeletas: cuántas y por cuánto, agrupadas.
     *
     * <p>Ninguna columna se llama «recaudado», y no es un descuido: lo que hay aquí son importes de
     * acta agrupados por estado. Lo recaudado sale del libro, y tiene su propia hoja ({@link
     * #deLaRecaudacion}).
     */
    public static ModeloDeDocumento delResumenDePapeletas(
            String titulo, List<Campo> criterio, ResumenDePapeletas resumen) {

        List<List<String>> filas = new ArrayList<>();
        for (LineaDelResumen linea : resumen.lineas()) {
            filas.add(
                    List.of(
                            linea.clave(),
                            texto(linea.descripcion()),
                            linea.ano() == null ? SIN_DATO : String.valueOf(linea.ano()),
                            String.valueOf(linea.cantidad()),
                            linea.importe().toString(),
                            String.valueOf(linea.pagadas()),
                            linea.importeDeLasPagadas().toString(),
                            String.valueOf(linea.pendientes()),
                            linea.importeDeLasPendientes().toString(),
                            String.valueOf(linea.enCoactiva()),
                            linea.importeEnCoactiva().toString()));
        }

        List<Campo> cabecera = new ArrayList<>(criterio);
        cabecera.add(Campo.de("Agrupado por", resumen.agrupacion().name()));
        cabecera.add(Campo.de("Papeletas", String.valueOf(resumen.total())));
        cabecera.add(Campo.de("Importe total de las actas", resumen.importeTotal().toString()));

        return new ModeloDeDocumento(
                titulo,
                null,
                resumen.aLaFecha(),
                cabecera,
                List.of(
                        Tabla.de(
                                "Resumen",
                                List.of(
                                        "Clave",
                                        "Descripcion",
                                        "Ano",
                                        "Papeletas",
                                        "Importe de las actas",
                                        "Pagadas",
                                        "Importe de las pagadas",
                                        "Pendientes",
                                        "Importe de las pendientes",
                                        "En coactiva",
                                        "Importe en coactiva"),
                                filas)),
                pie(
                        resumen.aLaFecha(),
                        "Los importes son los de las actas, no lo cobrado: lo cobrado esta en el"
                                + " resumen de recaudacion, que sale del libro."),
                null,
                null);
    }

    /**
     * La hoja informativa de una papeleta: una fila por concepto, con su importe (#396, RF-068).
     *
     * <p>Es la única de las hojas de sanciones que habla de <b>un</b> registro y no de un listado,
     * y aun así sale por el mismo camino: cabecera con el criterio, una tabla y el pie con la fecha
     * y el punto de firma. Una hoja propia con su propia cabecera sería la sexta manera de escribir
     * la fecha, y el día que alguien mirara dos hojas seguidas no sabría si dicen lo mismo.
     *
     * <p>Ninguna celda se recompone: los seis importes son los del acta, y el pie dice a qué fecha
     * lo son.
     */
    public static ModeloDeDocumento deLaHojaDePapeleta(ConsultaDeLaHojaDePapeleta.Hoja hoja) {
        var papeleta = hoja.papeleta();
        List<List<String>> filas =
                List.of(
                        List.of(
                                "Codigo de infraccion",
                                hoja.codigo() == null
                                        ? SIN_DATO
                                        : hoja.codigo().codigo()
                                                + " — "
                                                + hoja.codigo().descripcion(),
                                SIN_DATO),
                        List.of(
                                "Base imponible",
                                "UIT aplicada en el acta",
                                papeleta.baseImponible().toString()),
                        List.of(
                                "Porcentaje de la infraccion",
                                papeleta.porcentajeInfraccion().valor().toPlainString(),
                                papeleta.importeInfraccion().toString()),
                        List.of(
                                "Porcentaje a cobrar",
                                papeleta.porcentajeACobrar().valor().toPlainString(),
                                papeleta.importeAPagar().toString()),
                        List.of(
                                "Importe con beneficio",
                                papeleta.importeConBeneficio() == null
                                        ? "Sin beneficio en el acta"
                                        : "Descuento registrado en el acta",
                                papeleta.importeConBeneficio() == null
                                        ? SIN_DATO
                                        : papeleta.importeConBeneficio().toString()));

        List<Campo> cabecera =
                List.of(
                        Campo.de("N.o de papeleta", papeleta.numero()),
                        Campo.de("Fecha de la infraccion", papeleta.fechaInfraccion().toString()),
                        Campo.de(
                                "Hora",
                                papeleta.horaInfraccion() == null
                                        ? SIN_DATO
                                        : papeleta.horaInfraccion().toString()),
                        Campo.de("Lugar", papeleta.lugar()),
                        Campo.de("Placa", texto(papeleta.placa())),
                        Campo.de("Licencia de conducir", texto(papeleta.licenciaConducir())),
                        Campo.de(
                                "Obligado",
                                hoja.obligado() == null ? SIN_DATO : hoja.obligado().nombre()),
                        Campo.de(
                                "Documento",
                                hoja.obligado() == null ? SIN_DATO : hoja.obligado().documento()),
                        Campo.de("Domicilio", texto(hoja.domicilioDelObligado())),
                        Campo.de("Estado", papeleta.estado().name()));

        return new ModeloDeDocumento(
                "Hoja informativa de papeleta de infraccion",
                null,
                hoja.emitidaEl(),
                cabecera,
                List.of(Tabla.de("Detalle", List.of("Concepto", "Detalle", "Importe"), filas)),
                pie(
                        papeleta.fechaInfraccion(),
                        "Los importes son los del acta, congelados al registrar la papeleta. Lo"
                                + " que se debe hoy es otra cifra, y la publica el estado de"
                                + " cuenta."),
                null,
                null);
    }

    /** El resumen de recaudación: lo que dice el libro, tal cual. */
    public static ModeloDeDocumento deLaRecaudacion(
            String titulo, List<Campo> criterio, RecaudadoEnElLibro recaudado) {

        List<List<String>> filas = new ArrayList<>();
        for (RecaudacionDeUnTributo linea : recaudado.lineas()) {
            filas.add(
                    List.of(
                            linea.tributo(),
                            String.valueOf(linea.ejercicio().valor()),
                            String.valueOf(linea.mes()),
                            linea.fase(),
                            String.valueOf(linea.abonos()),
                            linea.recaudado().toString()));
        }

        List<Campo> cabecera = new ArrayList<>(criterio);
        cabecera.add(Campo.de("Desde", recaudado.desde().toString()));
        cabecera.add(Campo.de("Hasta", recaudado.hasta().toString()));
        cabecera.add(Campo.de("Abonos", String.valueOf(recaudado.abonos())));
        cabecera.add(Campo.de("Total recaudado", recaudado.total().toString()));

        return new ModeloDeDocumento(
                titulo,
                null,
                recaudado.aLaFecha(),
                cabecera,
                List.of(
                        Tabla.de(
                                "Recaudacion",
                                List.of(
                                        "Tributo",
                                        "Ejercicio",
                                        "Mes",
                                        "Tipo de cobranza",
                                        "Abonos",
                                        "Recaudado"),
                                filas)),
                pie(
                        recaudado.aLaFecha(),
                        "Cada cifra es la suma de los abonos vivos del libro: no cuenta los"
                                + " recibos anulados ni los movimientos que solo mueven deuda."),
                null,
                null);
    }

    // ------------------------------------------------------------------

    /**
     * La cabecera del criterio más el recuento de la página.
     *
     * <p>El total y la página van en el papel porque un padrón exportado sin ellos no se puede
     * distinguir del padrón entero: quien lo reciba creería tener las 40 000 filas cuando tiene 20.
     */
    private static List<Campo> conElRecuento(List<Campo> criterio, Pagina<?> pagina) {
        List<Campo> cabecera = new ArrayList<>(criterio);
        cabecera.add(Campo.de("Filas en total", String.valueOf(pagina.totalElementos())));
        cabecera.add(
                Campo.de(
                        "Pagina",
                        (pagina.pagina() + 1) + " de " + Math.max(pagina.totalPaginas(), 1)));
        cabecera.add(Campo.de("Filas en esta hoja", String.valueOf(pagina.contenido().size())));
        return cabecera;
    }

    private static List<String> pie(LocalDate aLaFecha, String nota) {
        return List.of(
                nota,
                "Cifras al "
                        + aLaFecha
                        + ". Una cifra sin su fecha es una cifra que manana es otra"
                        + " (RNF-075).",
                "",
                "_______________________________",
                "        Unidad responsable",
                "",
                "Documento sin firma digital: el regimen de firma es la decision D-05, abierta.");
    }

    private static String texto(@Nullable String valor) {
        return valor == null ? SIN_DATO : valor;
    }
}
