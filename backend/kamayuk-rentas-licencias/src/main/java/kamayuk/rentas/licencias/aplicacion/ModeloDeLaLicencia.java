package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.PuntoDeFirma;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.licencias.dominio.DuplicadoDeLicencia;
import kamayuk.rentas.licencias.dominio.GiroDeLaLicencia;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se imprime en una licencia y en sus resoluciones, sin decir en que formato (#44, RF-111,
 * RF-132).
 *
 * <h2>Por que esto y no una plantilla de texto</h2>
 *
 * <p>{@link ModeloDeDocumento} es lo que hace que el PDF, la hoja de calculo y el texto enriquecido
 * digan lo mismo, y —lo que aqui importa mas— es lo que {@code EmitirDocumento} <b>guarda</b>. El
 * duplicado de una licencia de 2026 pedido en 2034 vuelve a dibujar <b>estos</b> datos: no vuelve a
 * leer el catalogo CIIU, ni la ficha del predio, ni el nombre del titular, que para entonces pueden
 * ser otros. Es lo que hace que un duplicado sea el mismo papel y no un papel nuevo con el mismo
 * numero.
 *
 * <h2>Ninguna cifra</h2>
 *
 * <p>Una licencia de funcionamiento no lleva importes: el derecho de tramite se pago antes, y su
 * cifra esta en el recibo. Lo que si lleva es la <b>referencia</b> al recibo, que es lo que permite
 * comprobar el pago sin recomponerlo. {@code aLaFecha} del modelo es la fecha de emision.
 *
 * <h2>D-05: la firma digital</h2>
 *
 * <p>La licencia sale <b>sin firma digital</b>, y es imprimible igual. El regimen de firma es la
 * decision D-05 y sigue abierta; lo que ya esta resuelto es <b>donde</b> entra, y es {@link
 * PuntoDeFirma}, entre generar los bytes y entregarlos.
 */
final class ModeloDeLaLicencia {

    private ModeloDeLaLicencia() {}

    /** El titulo del papel de la licencia. */
    static final String TITULO = "Licencia municipal de funcionamiento";

    /** El modelo de la licencia. */
    static ModeloDeDocumento de(
            LicenciaDeFuncionamiento licencia,
            String titular,
            String codigoDelTitular,
            String documentoDelTitular,
            String numeroDeRecibo,
            List<GiroDeLaLicencia> giros) {

        List<Campo> cabecera = new ArrayList<>();
        cabecera.add(Campo.de("Numero de licencia", licencia.numero()));
        cabecera.add(Campo.de("Tipo de licencia", licencia.tipoLicencia().etiqueta()));
        cabecera.add(Campo.de("Titular", titular));
        cabecera.add(Campo.de("Codigo de contribuyente", codigoDelTitular));
        cabecera.add(Campo.de("Documento", documentoDelTitular));
        cabecera.add(Campo.de("Denominacion comercial", licencia.nombreComercial()));
        cabecera.add(Campo.de("Direccion del establecimiento", licencia.direccion()));
        cabecera.add(
                Campo.de(
                        "Area del establecimiento (m2)",
                        licencia.areaSolicitada().valor().toPlainString()));
        cabecera.add(Campo.de("Zonificacion", vacioSiFalta(licencia.zonificacion())));
        cabecera.add(
                Campo.de(
                        "Aforo autorizado",
                        licencia.aforo() == null ? "" : String.valueOf(licencia.aforo())));
        cabecera.add(Campo.de("Fecha de emision", licencia.fechaEmision().toString()));
        cabecera.add(
                Campo.de(
                        "Vigencia hasta",
                        licencia.vigenciaHasta() == null
                                ? "Indefinida"
                                : licencia.vigenciaHasta().toString()));
        cabecera.add(Campo.de("Expediente", vacioSiFalta(licencia.expediente())));
        cabecera.add(Campo.de("Recibo del derecho de tramite", numeroDeRecibo));

        return new ModeloDeDocumento(
                TITULO,
                licencia.numero(),
                licencia.fechaEmision(),
                cabecera,
                List.of(tablaDeGiros(giros)),
                pieDeLaLicencia(),
                null,
                null);
    }

    /** El modelo de la resolucion que cancela la licencia (RF-111). */
    static ModeloDeDocumento deLaCancelacion(
            LicenciaDeFuncionamiento licencia,
            String titular,
            String codigoDelTitular,
            LocalDate fecha,
            String motivo) {

        List<Campo> cabecera =
                List.of(
                        Campo.de("Licencia cancelada", licencia.numero()),
                        Campo.de("Titular", titular),
                        Campo.de("Codigo de contribuyente", codigoDelTitular),
                        Campo.de("Denominacion comercial", licencia.nombreComercial()),
                        Campo.de("Direccion del establecimiento", licencia.direccion()),
                        Campo.de(
                                "Fecha de emision de la licencia",
                                licencia.fechaEmision().toString()),
                        Campo.de("Fecha de la cancelacion", fecha.toString()),
                        Campo.de("Motivo", motivo));

        return new ModeloDeDocumento(
                "Resolucion de cancelacion de licencia de funcionamiento",
                licencia.numero(),
                fecha,
                cabecera,
                List.of(),
                pieDeLaResolucion(
                        "La licencia queda sin efecto desde la fecha de esta resolucion. La"
                                + " licencia NO se elimina del registro: queda cancelada, con esta"
                                + " resolucion como sustento (RNF-051)."),
                null,
                null);
    }

    /** El modelo de la resolucion que autoriza el duplicado (RF-111). */
    static ModeloDeDocumento delDuplicado(
            LicenciaDeFuncionamiento licencia,
            String titular,
            String codigoDelTitular,
            DuplicadoDeLicencia duplicado,
            String numeroDeRecibo) {

        List<Campo> cabecera =
                List.of(
                        Campo.de("Licencia", licencia.numero()),
                        Campo.de("Duplicado numero", String.valueOf(duplicado.numero())),
                        Campo.de("Titular", titular),
                        Campo.de("Codigo de contribuyente", codigoDelTitular),
                        Campo.de("Denominacion comercial", licencia.nombreComercial()),
                        Campo.de("Fecha de la autorizacion", duplicado.fecha().toString()),
                        Campo.de("Motivo", duplicado.motivo()),
                        Campo.de("Recibo del derecho de tramite", numeroDeRecibo));

        return new ModeloDeDocumento(
                "Resolucion de duplicado de licencia de funcionamiento",
                licencia.numero(),
                duplicado.fecha(),
                cabecera,
                List.of(),
                pieDeLaResolucion(
                        "El duplicado conserva el numero de la licencia original, "
                                + licencia.numero()
                                + ", y sale marcado como duplicado. No es una licencia nueva."),
                null,
                null);
    }

    private static Tabla tablaDeGiros(List<GiroDeLaLicencia> giros) {
        List<List<String>> filas = new ArrayList<>();
        for (GiroDeLaLicencia giro : giros) {
            filas.add(
                    List.of(
                            giro.codigo() == null ? "" : giro.codigo(),
                            giro.descripcion() == null ? "" : giro.descripcion(),
                            giro.principal() ? "PRINCIPAL" : "SECUNDARIO"));
        }
        return Tabla.de(
                "Giros CIIU autorizados", List.of("Codigo", "Actividad", "Condicion"), filas);
    }

    private static List<String> pieDeLaLicencia() {
        return List.of(
                "Ley 28976 — Ley Marco de Licencia de Funcionamiento.",
                "La presente licencia debe exhibirse en lugar visible del establecimiento.",
                "",
                "_______________________________",
                "        Gerencia municipal",
                "",
                "Documento sin firma digital: el regimen de firma de resoluciones es la decision"
                        + " D-05, abierta.");
    }

    private static List<String> pieDeLaResolucion(String nota) {
        return List.of(
                "Ley 28976 — Ley Marco de Licencia de Funcionamiento.",
                nota,
                "",
                "_______________________________",
                "        Gerencia municipal",
                "",
                "Documento sin firma digital: el regimen de firma de resoluciones es la decision"
                        + " D-05, abierta.");
    }

    private static String vacioSiFalta(@Nullable String texto) {
        return texto == null ? "" : texto;
    }
}
