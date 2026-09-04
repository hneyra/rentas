package kamayuk.rentas.sanciones.aplicacion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.PuntoDeFirma;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.sanciones.dominio.Internamiento;
import kamayuk.rentas.sanciones.dominio.TipoDeMovimientoDeInternamiento;
import kamayuk.rentas.tesoreria.TasaCobrada;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se imprime en un acta del depósito municipal, sin decir en qué formato (#50, RF-064,
 * RF-132).
 *
 * <h2>El número del acta no está en el modelo, y es a propósito</h2>
 *
 * <p>El número lo asigna {@code EmitirDocumento} al emitir, y el modelo es lo que se le entrega
 * <b>antes</b>. Meterlo dentro exigiría conocerlo antes de pedirlo, es decir una segunda numeración
 * propia —que es exactamente lo que V34 retiró de {@code acto_coactivo} y V41 de {@code
 * internamiento.acta}—. El acta de <b>ingreso</b> sí aparece en el acta de salida, porque para
 * entonces ya existe.
 *
 * <h2>Ninguna cifra que no venga de la caja</h2>
 *
 * <p>El importe de la custodia que el acta imprime <b>es el del recibo</b>, tal como {@code
 * tesoreria} lo devuelve, con la fecha en que se cobró (regla 9, RNF-075). No se recompone aquí
 * multiplicando días por tarifa: la tarifa es dato de la ordenanza (D-02b) y recomponerla daría una
 * cifra que no coincide con ningún recibo —que es exactamente lo que el administrado enseñaría—.
 *
 * <h2>D-05: la firma digital</h2>
 *
 * <p>El acta sale <b>sin firma digital</b>. El régimen de firma sigue siendo D-05, abierta; dónde
 * entra ya está resuelto y es {@link PuntoDeFirma}. El pie lleva el bloque de firmas manuscritas
 * del inspector y de quien retira.
 */
final class ModeloDelActaDeInternamiento {

    private ModeloDelActaDeInternamiento() {}

    /** El acta de ingreso al depósito. */
    static ModeloDeDocumento delIngreso(
            String placa,
            String deposito,
            Instant fechaIngreso,
            String tasaCustodia,
            @Nullable String numeroDePapeleta,
            LocalDate aLaFecha,
            String motivo) {

        List<Campo> cabecera = new ArrayList<>();
        cabecera.add(Campo.de("Placa", placa));
        cabecera.add(Campo.de("Deposito", deposito));
        cabecera.add(Campo.de("Fecha de ingreso", fechaIngreso.toString()));
        cabecera.add(Campo.de("Papeleta", numeroDePapeleta == null ? "" : numeroDePapeleta));
        cabecera.add(Campo.de("Concepto de custodia", tasaCustodia));
        cabecera.add(Campo.de("Motivo del internamiento", motivo));

        return new ModeloDeDocumento(
                "Acta de internamiento de vehiculo",
                placa,
                aLaFecha,
                cabecera,
                List.of(
                        Tabla.de(
                                "Requisitos para la liberacion",
                                List.of("Requisito", "Detalle"),
                                List.of(
                                        List.of(
                                                "Multa cancelada",
                                                "Recibo de la papeleta que dispuso la medida"),
                                        List.of(
                                                "Custodia cancelada",
                                                "Recibo del concepto " + tasaCustodia),
                                        List.of("Titularidad acreditada", "Tarjeta de propiedad"),
                                        List.of("SOAT vigente", "Copia del certificado")))),
                pieDe("Inspector", "Conductor"),
                null,
                null);
    }

    /** El acta de liberación o la declaración de abandono. */
    static ModeloDeDocumento delMovimiento(
            Internamiento internamiento,
            @Nullable String numeroDePapeleta,
            TipoDeMovimientoDeInternamiento tipo,
            LocalDate fecha,
            int dias,
            @Nullable String personaRetira,
            @Nullable String documentoRetira,
            boolean soatAcreditado,
            @Nullable TasaCobrada custodia) {

        List<Campo> cabecera = new ArrayList<>();
        cabecera.add(Campo.de("Acta de ingreso", internamiento.acta()));
        cabecera.add(Campo.de("Placa", internamiento.placa()));
        cabecera.add(Campo.de("Deposito", internamiento.deposito()));
        cabecera.add(Campo.de("Fecha de ingreso", internamiento.fechaIngreso().toString()));
        cabecera.add(Campo.de("Papeleta", numeroDePapeleta == null ? "" : numeroDePapeleta));
        cabecera.add(Campo.de("Fecha del acto", fecha.toString()));
        cabecera.add(Campo.de("Dias en deposito", String.valueOf(dias)));
        cabecera.add(Campo.de("Persona que retira", personaRetira == null ? "" : personaRetira));
        cabecera.add(
                Campo.de(
                        "Documento de quien retira",
                        documentoRetira == null ? "" : documentoRetira));
        cabecera.add(Campo.de("SOAT vigente acreditado", soatAcreditado ? "SI" : "NO"));

        List<List<String>> filas = new ArrayList<>();
        if (custodia == null) {
            filas.add(List.of(internamiento.tasaCustodia(), "", "", "0.00"));
        } else {
            filas.add(
                    List.of(
                            custodia.codigoDeTasa(),
                            custodia.numeroDeRecibo(),
                            custodia.fecha().toString(),
                            custodia.importe().valor().toPlainString()));
        }

        return new ModeloDeDocumento(
                tipo.titulo(),
                internamiento.placa(),
                fecha,
                cabecera,
                List.of(
                        Tabla.de(
                                "Custodia cancelada",
                                List.of("Concepto", "Recibo", "Fecha del cobro", "Importe (S/)"),
                                filas)),
                pieDe("Inspector", personaRetira == null ? "Testigo" : "Quien retira"),
                null,
                null);
    }

    // ------------------------------------------------------------------

    private static List<String> pieDe(String izquierda, String derecha) {
        return List.of(
                "Reglamento Nacional de Transito, D.S. 016-2009-MTC.",
                "TUO de la Ley 27444 — Ley del Procedimiento Administrativo General.",
                "",
                "_______________________________        _______________________________",
                "        " + izquierda + "                            " + derecha,
                "",
                "Documento sin firma digital: el regimen de firma es la decision D-05, abierta.");
    }
}
