package kamayuk.rentas.licencias.aplicacion;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.PuntoDeFirma;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.licencias.dominio.Certificado;
import kamayuk.rentas.licencias.dominio.ParametrosUrbanisticos;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se imprime en un certificado, sin decir en que formato (#54, RF-115, RF-132).
 *
 * <h2>Por que esto y no una plantilla de texto</h2>
 *
 * <p>{@link ModeloDeDocumento} es lo que hace que el PDF, la hoja de calculo y el texto enriquecido
 * digan lo mismo, y —lo que aqui importa mas— es lo que {@code EmitirDocumento} <b>guarda</b>. La
 * reimpresion de un certificado de 2026 pedida en 2034 vuelve a dibujar <b>estos</b> datos: no
 * vuelve a leer el padron de predios, ni el nombre del solicitante, ni el plano de zonificacion,
 * que para entonces pueden ser otros. Es lo que hace que la reimpresion sea el mismo papel y no uno
 * nuevo con el mismo numero (AC 2 de #54).
 *
 * <h2>{@code aLaFecha} es la fecha de emision, y ahi esta el defecto que #34 dejo documentado</h2>
 *
 * <p>El renderizador escribe «Datos al {@code aLaFecha}» en el papel. Esa fecha es la de la
 * <b>emision</b> y viene dentro del certificado; no sale de {@code LocalDate.now()} ni del reloj de
 * quien pide la reimpresion. Si saliera de ahi, el duplicado que un administrado pide en 2034
 * llevaria impresa la fecha de 2034 sobre unos datos de 2026, y ese papel se contradice solo.
 *
 * <h2>La cifra va con su fecha</h2>
 *
 * <p>El derecho de tramite es la unica cifra del papel, y se imprime con {@code derechoA} al lado
 * (regla 9, RNF-075): sin ella, «S/ 35,00» no se puede defender el dia que el TUPA suba la tarifa.
 *
 * <h2>D-05: la firma digital</h2>
 *
 * <p>El certificado sale <b>sin firma digital</b>, y es imprimible igual. El regimen de firma es la
 * decision D-05 y sigue abierta; lo que ya esta resuelto es <b>donde</b> entra, y es {@link
 * PuntoDeFirma}, entre generar los bytes y entregarlos.
 */
final class ModeloDelCertificado {

    private ModeloDelCertificado() {}

    /** El modelo del certificado. */
    static ModeloDeDocumento de(
            Certificado certificado, String solicitante, String codigoDelSolicitante) {

        List<Campo> cabecera = new ArrayList<>();
        cabecera.add(Campo.de("Numero de certificado", certificado.numero()));
        cabecera.add(Campo.de("Tipo", certificado.tipo().etiqueta()));
        cabecera.add(Campo.de("Solicitante", solicitante));
        cabecera.add(Campo.de("Codigo de contribuyente", codigoDelSolicitante));
        cabecera.add(Campo.de("Codigo predial", certificado.codigoPredial()));
        cabecera.add(Campo.de("Direccion del predio", certificado.direccion()));
        cabecera.add(Campo.de("Expediente", vacioSiFalta(certificado.expediente())));
        cabecera.add(Campo.de("Fecha de emision", certificado.fechaEmision().toString()));
        cabecera.add(Campo.de("Vigencia hasta", certificado.vigenciaHasta().toString()));
        // La cifra y su fecha, juntas y en el mismo campo: separarlas dejaria un importe que se
        // puede leer sin ver de cuando es, que es exactamente lo que la regla 9 prohibe.
        cabecera.add(
                Campo.de(
                        "Derecho de tramite (S/)",
                        certificado.derecho().valor().toPlainString()
                                + " al "
                                + certificado.derechoA()));

        return new ModeloDeDocumento(
                certificado.tipo().etiqueta(),
                certificado.numero(),
                certificado.fechaEmision(),
                cabecera,
                List.of(tablaDeParametros(certificado.parametros())),
                pieDelCertificado(certificado),
                null,
                null);
    }

    /**
     * Los parametros urbanisticos certificados, como tabla.
     *
     * <p>Va siempre, aunque no haya ninguno declarado: un certificado de numeracion imprime la
     * tabla con la fila «No se certifican parametros urbanisticos». Omitirla dejaria dos formas del
     * mismo documento —una con tabla y otra sin ella— y el lector no sabria si el certificado no
     * los lleva o si se perdieron al imprimir.
     */
    private static Tabla tablaDeParametros(ParametrosUrbanisticos parametros) {
        List<List<String>> filas = new ArrayList<>();
        if (parametros.estanVacios()) {
            filas.add(List.of("—", "Este certificado no consigna parametros urbanisticos"));
        } else {
            agregar(filas, "Zonificacion", parametros.zonificacion());
            agregar(filas, "Altura maxima permitida", parametros.alturaMaxima());
            agregar(filas, "Area libre minima", parametros.areaLibreMinima());
            agregar(filas, "Retiro municipal", parametros.retiroMunicipal());
            agregar(filas, "Coeficiente de edificacion", parametros.coeficienteEdificacion());
        }
        return Tabla.de("Parametros certificados", List.of("Parametro", "Valor"), filas);
    }

    private static void agregar(List<List<String>> filas, String etiqueta, @Nullable String valor) {
        if (valor != null) {
            filas.add(List.of(etiqueta, valor));
        }
    }

    private static List<String> pieDelCertificado(Certificado certificado) {
        return List.of(
                "Ley 29090 — Ley de Regulacion de Habilitaciones Urbanas y de Edificaciones.",
                "El presente certificado vence el "
                        + certificado.vigenciaHasta()
                        + " y no acredita derecho de propiedad.",
                "Derecho de tramite pagado con el recibo consignado en el expediente.",
                "",
                "_______________________________",
                "     Gerencia de desarrollo urbano",
                "",
                "Documento sin firma digital: el regimen de firma de resoluciones es la decision"
                        + " D-05, abierta.");
    }

    private static String vacioSiFalta(@Nullable String texto) {
        return texto == null ? "" : texto;
    }
}
