package kamayuk.rentas.licencias.aplicacion;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.licencias.dominio.FilaDelResumenAnual;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;

/**
 * El padron y el resumen anual como documento imprimible y exportable (#54, RF-115, RF-132).
 *
 * <h2>Los tres formatos salen de aqui, y no se escribe nada por formato</h2>
 *
 * <p>Esta clase construye {@link ModeloDeDocumento}, y {@code GeneradorDeDocumentos} lo dibuja en
 * PDF, en hoja de calculo o en texto enriquecido con los renderizadores que ya existen. Es la misma
 * infraestructura que emite los trece reportes del sistema: un reporte nuevo se exporta a los tres
 * <b>sin escribir nada</b> para cada uno. Si cada formato se construyera aparte, un dia el PDF
 * llevaria una columna que la hoja de calculo no, y nadie sabria cual de los dos esta bien.
 *
 * <p>El escapado de lo que no es ASCII —«PEÑA GARCÍA» en un certificado, «Nº» en una cabecera— lo
 * hace {@code RenderizadorRtf}, que es donde tiene que estar: aqui todo es texto y este paquete no
 * sabe en que formato saldra.
 *
 * <h2>Ni una cifra recompuesta</h2>
 *
 * <p>Las celdas se escriben con lo que el modelo de negocio ya calculo. Ninguna se suma, se resta
 * ni se redondea al dibujarla: recomponer una cifra en la capa de presentacion es como se acaba
 * imprimiendo un total que no coincide con el de la pantalla, y es el defecto que las pruebas de
 * los trece reportes cazan alterando una celda numerica al dibujarla.
 *
 * <p>La fecha de corte va en {@code aLaFecha} del modelo, que es lo que los tres renderizadores
 * imprimen como «Datos al …» (regla 9, RNF-075).
 */
public final class ModeloDeLosReportesDeLicencias {

    private ModeloDeLosReportesDeLicencias() {}

    /** El titulo del padron, tal como lo declara la pantalla {@code licencia_padron}. */
    public static final String TITULO_DEL_PADRON = "Padron de licencias de funcionamiento";

    /** El titulo del resumen, tal como lo declara {@code licencia_resumen_anual}. */
    public static final String TITULO_DEL_RESUMEN = "Resumen de licencias por año";

    /** El padron, con su resumen en la cabecera y sus filas en la tabla. */
    public static ModeloDeDocumento delPadron(ConsultaDeLicencias.Padron padron) {
        List<Campo> cabecera =
                List.of(
                        Campo.de("Fecha de corte", padron.aLaFecha().toString()),
                        Campo.de("Licencias", String.valueOf(padron.resumen().licencias())),
                        Campo.de("Vigentes", String.valueOf(padron.resumen().vigentes())),
                        Campo.de("Vencidas", String.valueOf(padron.resumen().vencidas())),
                        Campo.de("Canceladas", String.valueOf(padron.resumen().canceladas())));

        List<List<String>> filas = new ArrayList<>();
        for (ConsultaDeLicencias.LicenciaEnConsulta fila : padron.pagina().contenido()) {
            LicenciaDeFuncionamiento licencia = fila.licencia();
            filas.add(
                    List.of(
                            licencia.numero(),
                            licencia.fechaEmision().toString(),
                            fila.nombreDelTitular(),
                            licencia.nombreComercial(),
                            giroPrincipalDe(licencia),
                            licencia.direccion(),
                            fila.estado().name()));
        }

        return new ModeloDeDocumento(
                TITULO_DEL_PADRON,
                null,
                padron.aLaFecha(),
                cabecera,
                List.of(
                        Tabla.de(
                                "Licencias del padron",
                                List.of(
                                        "N certificado",
                                        "Fecha",
                                        "Contribuyente",
                                        "Nombre comercial",
                                        "Giro",
                                        "Direccion",
                                        "Estado"),
                                filas)),
                List.of(
                        "Los recuentos de la cabecera cubren TODAS las licencias del filtro; la"
                                + " tabla trae la pagina pedida.",
                        "El estado de cada licencia esta derivado al "
                                + padron.aLaFecha()
                                + ": reimprimir este padron con la misma fecha da el mismo"
                                + " resultado."),
                null,
                null);
    }

    /** El resumen anual, un año por fila. */
    public static ModeloDeDocumento delResumen(ResumenAnualDeLicencias.Resumen resumen) {
        List<List<String>> filas = new ArrayList<>();
        for (FilaDelResumenAnual fila : resumen.filas()) {
            filas.add(
                    List.of(
                            String.valueOf(fila.ejercicio().valor()),
                            String.valueOf(fila.emitidas()),
                            String.valueOf(fila.canceladas()),
                            String.valueOf(fila.duplicados()),
                            String.valueOf(fila.vigentesAlCierre()),
                            // La cifra tal cual la calculo el servicio, o la raya. NUNCA un cero:
                            // un cero se lee como «no se recaudo nada» y esta hoja concilia la
                            // caja (#48).
                            fila.derechoDeTramite() == null
                                    ? "—"
                                    : fila.derechoDeTramite().valor().toPlainString(),
                            fila.alCierre().toString()));
        }

        return new ModeloDeDocumento(
                TITULO_DEL_RESUMEN,
                null,
                resumen.aLaFecha(),
                List.of(Campo.de("Fecha de corte", resumen.aLaFecha().toString())),
                List.of(
                        Tabla.de(
                                "Licencias por año",
                                List.of(
                                        "Año",
                                        "Emitidas",
                                        "Canceladas",
                                        "Duplicados",
                                        "Vigentes al cierre",
                                        "Derecho de tramite S/",
                                        "Al cierre"),
                                filas)),
                pieDelResumen(resumen),
                null,
                null);
    }

    // ------------------------------------------------------------------

    /**
     * El pie del resumen, con el motivo de cada año cuya recaudacion no se pudo resolver.
     *
     * <p>El «—» de la celda dice que falta; esta lista dice <b>por que</b>, nombrando la llave. Sin
     * ella, quien recibe la hoja no puede saber si el año no tuvo recaudacion o si el sistema no
     * pudo calcularla, que son cosas distintas y se arreglan de maneras distintas.
     */
    private static List<String> pieDelResumen(ResumenAnualDeLicencias.Resumen resumen) {
        List<String> pie = new ArrayList<>();
        pie.add(
                "La recaudacion es lo que la caja cobro por el concepto del TUPA en cada año, no lo"
                        + " que costaron las licencias emitidas en el.");
        for (FilaDelResumenAnual fila : resumen.filas()) {
            String motivo = fila.derechoNoDisponible();
            if (motivo != null) {
                pie.add(fila.ejercicio().valor() + ": sin recaudacion calculable. " + motivo);
            }
        }
        return pie;
    }

    private static String giroPrincipalDe(LicenciaDeFuncionamiento licencia) {
        return licencia.giros().stream()
                .filter(giro -> giro.principal())
                .map(giro -> giro.codigo() == null ? "" : giro.codigo())
                .findFirst()
                .orElse("");
    }
}
