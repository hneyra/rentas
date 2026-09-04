package kamayuk.rentas.catastro.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * Una actividad economica declarada en la unidad catastral (RF-002).
 *
 * <p><b>La licencia entra por numero, no por identificador.</b> {@code catastro} no depende de
 * {@code licencias} (ARQ-01 §4): si aqui hubiera una clave ajena, el tecnico no podria anotar el
 * numero del cartel que ve en la puerta hasta que el otro contexto hubiera cargado la licencia. Y
 * que el numero apunte a una licencia inexistente no es un error de integridad —es un hallazgo de
 * fiscalizacion, que es justo para lo que sirve esta ficha—.
 *
 * <p>Quien conduce el negocio puede no ser el titular del predio: el arrendatario tiene la licencia
 * y el propietario paga el predial. Por eso el conductor es un texto y no el contribuyente de la
 * titularidad.
 */
public record ActividadEconomica(
        @Nullable Long id,
        @Nullable Long fichaId,
        String conductor,
        @Nullable String nombreComercial,
        @Nullable String ciiu,
        @Nullable AreaM2 areaOcupada,
        @Nullable String licenciaNumero,
        @Nullable LocalDate licenciaFecha,
        @Nullable String anuncioNumero,
        @Nullable LocalDate anuncioFecha,
        @Nullable LocalDate vigenciaDesde) {

    private static final int CONDUCTOR_MAXIMO = 200;
    private static final int NOMBRE_MAXIMO = 200;
    private static final int CIIU_MAXIMO = 10;
    private static final int NUMERO_MAXIMO = 20;

    public ActividadEconomica {
        Objects.requireNonNull(conductor, "La actividad necesita saber quien la conduce");

        conductor = conductor.strip();
        if (conductor.isEmpty() || conductor.length() > CONDUCTOR_MAXIMO) {
            throw new IllegalArgumentException(
                    "El conductor va de 1 a " + CONDUCTOR_MAXIMO + " caracteres");
        }
        nombreComercial = recortado(nombreComercial, NOMBRE_MAXIMO, "El nombre comercial");
        ciiu = recortado(ciiu, CIIU_MAXIMO, "El codigo CIIU");
        licenciaNumero = recortado(licenciaNumero, NUMERO_MAXIMO, "El numero de licencia");
        anuncioNumero = recortado(anuncioNumero, NUMERO_MAXIMO, "El numero de autorizacion");

        if (licenciaFecha != null && licenciaNumero == null) {
            throw new IllegalArgumentException(
                    "Una fecha de licencia sin numero no permite comprobar nada; o van las dos o no"
                            + " va ninguna");
        }
        if (anuncioFecha != null && anuncioNumero == null) {
            throw new IllegalArgumentException(
                    "Una fecha de autorizacion de anuncio sin numero no permite comprobar nada");
        }
    }

    public static ActividadEconomica de(String conductor, String ciiu) {
        return new ActividadEconomica(
                null, null, conductor, null, ciiu, null, null, null, null, null, null);
    }

    /** La misma actividad con su licencia declarada. */
    public ActividadEconomica conLicencia(String numero, LocalDate fecha) {
        return new ActividadEconomica(
                id,
                fichaId,
                conductor,
                nombreComercial,
                ciiu,
                areaOcupada,
                numero,
                fecha,
                anuncioNumero,
                anuncioFecha,
                vigenciaDesde);
    }

    /** La misma actividad colgada de otra version, al versionar. */
    public ActividadEconomica enLaFicha(long otraFichaId) {
        return new ActividadEconomica(
                null,
                otraFichaId,
                conductor,
                nombreComercial,
                ciiu,
                areaOcupada,
                licenciaNumero,
                licenciaFecha,
                anuncioNumero,
                anuncioFecha,
                vigenciaDesde);
    }

    /** Si declara licencia de funcionamiento. Lo contrario es el hallazgo que se busca. */
    public boolean declaraLicencia() {
        return licenciaNumero != null;
    }

    private static @Nullable String recortado(@Nullable String valor, int maximo, String que) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        if (limpio.isEmpty()) {
            return null;
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(que + " excede " + maximo + " caracteres");
        }
        return limpio;
    }
}
