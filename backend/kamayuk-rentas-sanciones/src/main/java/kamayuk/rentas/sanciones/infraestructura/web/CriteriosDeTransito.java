package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.sanciones.dominio.CriterioDeConstancias;
import kamayuk.rentas.sanciones.dominio.CriterioDePadron;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;

/**
 * El criterio de cada reporte de tránsito, en un solo sitio (#53, #396).
 *
 * <h2>Existe porque el emisor pide lo mismo que los GET</h2>
 *
 * <p>{@code POST /transito/reportes} es la pantalla «emisor de reportes» del manual: una sola
 * opción que emite las nueve hojas que los tres controladores de lectura ya sirven una a una. Si
 * armara sus criterios por su cuenta habría <b>dos</b> caminos para la misma cuenta —el del GET y
 * el del emisor—, y el que se mira menos es el que se queda mal: el día que alguien corrigiera el
 * rango por omisión del padrón, la hoja emitida y la pantalla dirían cosas distintas sin que nada
 * lo señalara.
 *
 * <p>Los dos rechazos que viven aquí —el record sin sujeto— son parte del criterio y no del
 * controlador: un record de conductor sin licencia ni documento es el padrón entero con otro
 * título, se pida por GET o por el emisor.
 */
final class CriteriosDeTransito {

    private CriteriosDeTransito() {}

    /** El padrón corriente y el de coactiva: lo que los distingue es {@code conValorEmitido}. */
    static CriterioDePadron delPadron(
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String estado,
            @Nullable Boolean conValorEmitido) {

        return new CriterioDePadron(
                Familia.TRANSITO,
                PeticionesDeSanciones.fechaSiViene(desde, "desde"),
                PeticionesDeSanciones.fechaSiViene(hasta, "hasta"),
                PeticionesDeSanciones.enumeradoSiViene(EstadoDePapeleta.class, estado, "estado"),
                null,
                null,
                null,
                null,
                null,
                conValorEmitido,
                false);
    }

    /** El padrón de constancias libres de infracciones. */
    static CriterioDeConstancias deConstancias(
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String numero,
            @Nullable String usuarioQueEmitio) {

        return new CriterioDeConstancias(
                PeticionesDeSanciones.fechaSiViene(desde, "desde"),
                PeticionesDeSanciones.fechaSiViene(hasta, "hasta"),
                PeticionesDeSanciones.vacioEsNulo(numero),
                PeticionesDeSanciones.vacioEsNulo(usuarioQueEmitio),
                null);
    }

    /** El record de conductor. Exige a quién: sin sujeto sería el padrón entero con otro título. */
    static CriterioDePadron delConductor(@Nullable String licencia, @Nullable String documento) {

        String licenciaLimpia = PeticionesDeSanciones.vacioEsNulo(licencia);
        String documentoLimpio = PeticionesDeSanciones.vacioEsNulo(documento);
        if (licenciaLimpia == null && documentoLimpio == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El record de conductor necesita a quien: la licencia de conducir o el"
                            + " documento del infractor. Sin ninguno de los dos, esto seria el"
                            + " padron entero con otro titulo");
        }

        return new CriterioDePadron(
                Familia.TRANSITO,
                null,
                null,
                null,
                null,
                null,
                null,
                licenciaLimpia,
                documentoLimpio,
                null,
                false);
    }

    /** El record vehicular. Exige la placa, por el mismo motivo. */
    static CriterioDePadron delVehiculo(@Nullable String placa) {
        String placaLimpia = PeticionesDeSanciones.vacioEsNulo(placa);
        if (placaLimpia == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El record vehicular necesita la placa. Sin ella esto seria el padron entero"
                            + " con otro titulo");
        }

        return new CriterioDePadron(
                Familia.TRANSITO,
                null,
                null,
                null,
                null,
                placaLimpia,
                null,
                null,
                null,
                null,
                false);
    }

    /**
     * El criterio de los tres resúmenes de papeletas, con el rango por omisión.
     *
     * <p>Sin rango, el ejercicio en curso, y el resumen <b>dice cuál</b>: el {@code desde} y el
     * {@code hasta} viajan en la respuesta y salen impresos en la hoja (regla 9, RNF-075).
     *
     * <p>Las iniciales entran como {@code prefijoDePlaca}, que el repositorio escribe como rango
     * con {@code ~&gt;=~} / {@code ~&lt;~}: bajo RLS un {@code LIKE 'AB%'} no llega al índice
     * (DAT-01 §0, tercer hallazgo).
     */
    static CriterioDePadron delResumen(
            LocalDate hoy,
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String estado,
            @Nullable String codigo,
            @Nullable String prefijoDePlaca) {

        LocalDate inicio =
                desde == null || desde.isBlank()
                        ? LocalDate.of(hoy.getYear(), 1, 1)
                        : PeticionesDeSanciones.fechaDe(desde, "desde");
        LocalDate fin =
                hasta == null || hasta.isBlank()
                        ? LocalDate.of(hoy.getYear(), 12, 31)
                        : PeticionesDeSanciones.fechaDe(hasta, "hasta");

        try {
            return new CriterioDePadron(
                    Familia.TRANSITO,
                    inicio,
                    fin,
                    PeticionesDeSanciones.enumeradoSiViene(
                            EstadoDePapeleta.class, estado, "estado"),
                    PeticionesDeSanciones.vacioEsNulo(codigo),
                    null,
                    PeticionesDeSanciones.vacioEsNulo(prefijoDePlaca),
                    null,
                    null,
                    null,
                    false);
        } catch (IllegalArgumentException invalido) {
            throw PeticionesDeSanciones.invalido(invalido);
        }
    }

    /** El ejercicio de un «Año» de la pantalla, o el del reloj cuando no viene. */
    static int ejercicioDe(@Nullable String ano, LocalDate hoy) {
        if (ano == null || ano.isBlank()) {
            return hoy.getYear();
        }
        try {
            return Integer.parseInt(ano.strip());
        } catch (NumberFormatException noEsUnNumero) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El ano va como un entero de cuatro cifras: " + ano);
        }
    }
}
