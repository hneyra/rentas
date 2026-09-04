package kamayuk.rentas.sanciones.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.PuntoDeFirma;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.sanciones.dominio.Descargo;
import kamayuk.rentas.sanciones.dominio.EfectoSobreLaMulta;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.SentidoDelFallo;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se imprime en una resolución de gerencia, sin decir en qué formato (#50, RF-065, RF-074,
 * RF-132).
 *
 * <h2>Por qué esto y no una plantilla de texto</h2>
 *
 * <p>{@link ModeloDeDocumento} es lo que hace que el PDF, la hoja de cálculo y el texto enriquecido
 * digan lo mismo, y —lo que aquí importa más— es lo que {@code EmitirDocumento} <b>guarda</b>.
 * Reimprimir la resolución de 2026 en 2036 vuelve a dibujar estos datos, no vuelve a calcularlos:
 * la deuda recalculada diez años después daría otra cifra, y el administrado tiene el papel de
 * entonces en la mano.
 *
 * <p>Por eso todo lo que entra aquí es <b>texto ya formateado</b>, con su fecha pegada: {@code
 * aLaFecha} del modelo es el día al que está la deuda impresa (regla 9, RNF-075).
 *
 * <h2>D-05: la firma digital</h2>
 *
 * <p>La resolución sale <b>sin firma digital</b>, y es imprimible igual. El régimen de firma sigue
 * siendo la decisión D-05, abierta; lo que ya está resuelto es <b>dónde</b> entra, y es {@link
 * PuntoDeFirma}, entre generar los bytes y entregarlos. El pie lleva el bloque de firmas
 * manuscritas del gerente y del secretario, que es lo que la resolución necesita mientras tanto.
 */
final class ModeloDeLaResolucionDeGerencia {

    private ModeloDeLaResolucionDeGerencia() {}

    /**
     * El modelo de la resolución.
     *
     * @param papeleta la multa sobre la que se resuelve
     * @param tipo cuál de las tres resoluciones es; de él sale el título
     * @param obligado el nombre del obligado, ya resuelto contra el padrón
     * @param codigoDelObligado su código de contribuyente
     * @param documentoDelObligado su documento de identidad
     * @param domicilio dónde se le notifica
     * @param descargo el recurso que resuelve, si resuelve alguno
     * @param sentido con qué sentido lo resuelve
     * @param efecto qué le pasa a la multa
     * @param sancionAccesoria la sanción no pecuniaria que se deriva, si la hay
     * @param plazo el plazo que la ordinaria concede, leído del conjunto sellado; nulo en las demás
     * @param deuda cuánto se debe, con el día al que está (regla 9); nulo si ya no debe nada
     * @param aLaFecha el día al que se leyó la deuda
     * @param sustento el fundamento de la resolución
     */
    static ModeloDeDocumento de(
            Papeleta papeleta,
            TipoDeResolucionDeGerencia tipo,
            String obligado,
            String codigoDelObligado,
            String documentoDelObligado,
            @Nullable String domicilio,
            @Nullable Descargo descargo,
            @Nullable SentidoDelFallo sentido,
            @Nullable EfectoSobreLaMulta efecto,
            @Nullable String sancionAccesoria,
            @Nullable Plazo plazo,
            @Nullable ObligacionPublica deuda,
            LocalDate aLaFecha,
            String sustento) {

        List<Campo> cabecera = new ArrayList<>();
        cabecera.add(Campo.de("Papeleta", papeleta.numero()));
        cabecera.add(Campo.de("Familia", papeleta.familia().name()));
        cabecera.add(Campo.de("Fecha de la infraccion", papeleta.fechaInfraccion().toString()));
        cabecera.add(Campo.de("Lugar", papeleta.lugar()));
        if (papeleta.placa() != null) {
            cabecera.add(Campo.de("Placa", papeleta.placa()));
        }
        cabecera.add(Campo.de("Obligado", obligado));
        cabecera.add(Campo.de("Codigo de contribuyente", codigoDelObligado));
        cabecera.add(Campo.de("Documento", documentoDelObligado));
        cabecera.add(Campo.de("Domicilio", domicilio == null ? "" : domicilio));
        if (descargo != null) {
            cabecera.add(Campo.de("Expediente del recurso", descargo.numeroExpediente()));
            cabecera.add(Campo.de("Recurso", descargo.tipoRecurso().name()));
            cabecera.add(Campo.de("Presentado el", descargo.fecha().toString()));
            cabecera.add(
                    Campo.de(
                            "Presentado dentro del plazo",
                            descargo.enPlazo()
                                    ? "SI, el plazo vencia el " + descargo.presentadoHasta()
                                    : "NO, el plazo vencio el " + descargo.presentadoHasta()));
        }
        if (sentido != null) {
            cabecera.add(Campo.de("Sentido del fallo", sentido.name()));
        }
        if (efecto != null) {
            cabecera.add(Campo.de("Efecto sobre la multa", efecto.name()));
        }
        if (sancionAccesoria != null) {
            cabecera.add(Campo.de("Sancion accesoria", sancionAccesoria));
        }
        if (plazo != null) {
            // El plazo se IMPRIME tal como el parametro sellado lo dice ("7 DIAS_HABILES"), no
            // como una frase compuesta aqui: si manana la norma lo cambia, cambia el parametro y
            // el papel sale con la cifra nueva sin tocar una linea (regla 5).
            cabecera.add(Campo.de("Plazo de pago", plazo.toString()));
        }
        cabecera.add(Campo.de("Sustento", sustento));

        List<Tabla> tablas = List.of(tablaDeLaDeuda(deuda, aLaFecha));

        List<String> pie =
                List.of(
                        "TUO de la Ley 27444 — Ley del Procedimiento Administrativo General.",
                        "TUO del Codigo Tributario, D.S. 133-2013-EF.",
                        "",
                        "_______________________________        _______________________________",
                        "            Gerente                            Secretario",
                        "",
                        "Documento sin firma digital: el regimen de firma de resoluciones es la"
                                + " decision D-05, abierta.");

        return new ModeloDeDocumento(
                tipo.titulo(), papeleta.numero(), aLaFecha, cabecera, tablas, pie, null, null);
    }

    /**
     * El desglose, con su fecha en el título.
     *
     * <p>La fecha va <b>dentro</b> del título de la tabla además de en {@code aLaFecha} del modelo:
     * quien recorta el cuadro de deuda de un PDF para pegarlo en un informe se lleva la fecha con
     * él (regla 9).
     */
    private static Tabla tablaDeLaDeuda(@Nullable ObligacionPublica deuda, LocalDate aLaFecha) {
        List<List<String>> filas =
                deuda == null
                        ? List.of(
                                fila("Insoluto", Dinero.CERO),
                                fila("Reajuste", Dinero.CERO),
                                fila("Interes moratorio", Dinero.CERO),
                                fila("Gastos administrativos", Dinero.CERO),
                                fila("TOTAL EXIGIBLE", Dinero.CERO))
                        : List.of(
                                fila("Insoluto", deuda.insoluto()),
                                fila("Reajuste", deuda.reajuste()),
                                fila("Interes moratorio", deuda.interes()),
                                fila("Gastos administrativos", deuda.gasto()),
                                fila("TOTAL EXIGIBLE", deuda.total()));
        return Tabla.de(
                "Deuda actualizada al " + aLaFecha, List.of("Concepto", "Importe (S/)"), filas);
    }

    private static List<String> fila(String concepto, Dinero importe) {
        return List.of(concepto, importe.valor().toPlainString());
    }
}
