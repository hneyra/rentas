package kamayuk.rentas.sanciones.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.PuntoDeFirma;
import kamayuk.rentas.documentos.Tabla;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se imprime en una constancia libre de infracciones, sin decir en qué formato (#53, RF-068,
 * RF-132).
 *
 * <h2>La fecha de verificación está en el papel, no solo en la fila</h2>
 *
 * <p>{@code aLaFecha} del modelo <b>es</b> {@code verificada_al}, y sale también como campo de la
 * cabecera con su nombre. «No registra papeletas pendientes» es cierto o falso según el día (regla
 * 9, RNF-075): una constancia que no dijera a qué día acredita afirmaría algo sin fecha, y el
 * vehículo podría tener una papeleta impuesta esa misma tarde.
 *
 * <h2>Sin firma digital</h2>
 *
 * <p>El régimen de firma sigue siendo D-05, abierta; dónde entra ya está resuelto y es {@link
 * PuntoDeFirma}. El pie lleva el bloque de firma manuscrita, como las actas de #50.
 */
final class ModeloDeLaConstanciaLibre {

    private ModeloDeLaConstanciaLibre() {}

    /** El título del documento, y también el del asiento del padrón. */
    static final String TITULO = "Constancia de no registrar infracciones de transito";

    static ModeloDeDocumento de(
            String placa,
            @Nullable String solicitante,
            LocalDate verificadaAl,
            LocalDate fechaEmision) {

        List<Campo> cabecera = new ArrayList<>();
        cabecera.add(Campo.de("Placa", placa));
        cabecera.add(Campo.de("Solicitante", solicitante == null ? "" : solicitante));
        cabecera.add(Campo.de("Verificado al", verificadaAl.toString()));
        cabecera.add(Campo.de("Fecha de emision", fechaEmision.toString()));

        return new ModeloDeDocumento(
                TITULO,
                placa,
                verificadaAl,
                cabecera,
                List.of(
                        Tabla.de(
                                "Papeletas de transito pendientes de pago",
                                List.of("Numero", "Fecha", "Infraccion", "Estado"),
                                // Vacia, y por eso se emite la constancia: si hubiera una sola
                                // fila que poner aqui, EmitirConstanciaLibre habria negado la
                                // constancia antes de llegar a dibujarla.
                                List.of())),
                pie(verificadaAl),
                null,
                null);
    }

    private static List<String> pie(LocalDate verificadaAl) {
        return List.of(
                "Se deja constancia de que, al "
                        + verificadaAl
                        + ", el vehiculo no registra papeletas de infraccion de transito"
                        + " pendientes de pago en esta municipalidad.",
                "Esta constancia acredita esa situacion a esa fecha y no a otra.",
                "Reglamento Nacional de Transito, D.S. 016-2009-MTC.",
                "",
                "_______________________________",
                "        Unidad de Transito",
                "",
                "Documento sin firma digital: el regimen de firma es la decision D-05, abierta.");
    }
}
