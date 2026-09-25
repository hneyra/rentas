package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Medida;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Los datos urbanos del FUE: donde se construye (#48, RF-113).
 *
 * <p>Se <b>versiona</b>, no se edita (V43 §8). Mientras el expediente se tramita, lo que el
 * administrado declaro primero y lo que corrigio despues son los dos datos, y el que se pierde con
 * un {@code UPDATE} es justo el que explica una observacion del evaluador.
 *
 * @param id nulo mientras no se haya guardado
 * @param fueId el expediente al que pertenece
 * @param version 1 la primera vez que se completa la seccion, 2 la siguiente, y asi
 * @param codigoCatastral el codigo de referencia catastral, cuando el terreno lo tiene
 * @param direccion la direccion del terreno
 * @param manzana la manzana; se filtra por prefijo desde la pantalla
 * @param lote el lote
 * @param areaTerreno el area del terreno
 * @param zonificacion la zona declarada
 * @param partidaRegistral la partida donde consta el dominio
 * @param frente el frente del lote, en metros lineales
 * @param fondo el fondo del lote, en metros lineales
 * @param registradoEn el instante de registro, del reloj inyectado
 * @param usuarioRegistro quien lo registro
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record TerrenoDelFue(
        @Nullable Long id,
        long fueId,
        int version,
        @Nullable String codigoCatastral,
        String direccion,
        @Nullable String manzana,
        @Nullable String lote,
        AreaM2 areaTerreno,
        @Nullable String zonificacion,
        @Nullable String partidaRegistral,
        @Nullable Medida frente,
        @Nullable Medida fondo,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /**
     * {@code edificacion_terreno.manzana varchar(10)} (V1).
     *
     * <p>El ancho de la columna, topado aqui y no en la base (#422): hasta entonces un texto de mas
     * llegaba al {@code INSERT}, el motor lo rechazaba con 22001 y el borde contestaba 500 con
     * incidencia ERROR. La {@code IllegalArgumentException} sale 422 con su mensaje, que nombra el
     * campo y no la tabla. Aqui se topa lo que llega; no se ensancha la base.
     */
    public static final int MANZANA_MAXIMA = 10;

    /** {@code edificacion_terreno.lote varchar(10)} (V1). Ver {@link #MANZANA_MAXIMA} (#422). */
    public static final int LOTE_MAXIMO = 10;

    /**
     * {@code edificacion_terreno.cod_catastral}, que desde V29 es el dominio {@code cod_catastral}:
     * de 18 a 25 digitos, el mismo {@code CHECK} (#408).
     *
     * <p>Hasta #408 la columna era {@code varchar(20)} y aqui no se validaba nada: ningun codigo
     * real cabia —D-10 duda entre 21 y 23 posiciones— y la base lo rechazaba con 22001, que el
     * borde contestaba 500. Validado aqui, un codigo mal escrito sale 422 con su mensaje.
     *
     * <p><b>No</b> se valida con {@link kamayuk.rentas.dominio.CodigoReferenciaCatastral#de}: fija
     * las 23 posiciones del manual y rechazaria las 21 del prototipo, que es cerrar D-10 por la
     * puerta de atras. Mientras siga abierta, lo que se exige es lo que la columna admite.
     */
    private static final Pattern CODIGO_CATASTRAL_ADMITIDO = Pattern.compile("[0-9]{18,25}");

    public TerrenoDelFue {
        Objects.requireNonNull(direccion, "El terreno necesita su direccion");
        Objects.requireNonNull(areaTerreno, "El terreno necesita su area");
        Objects.requireNonNull(registradoEn, "La seccion dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        direccion = direccion.strip();
        if (direccion.isEmpty()) {
            throw new IllegalArgumentException("La direccion del terreno no puede estar vacia");
        }
        if (codigoCatastral != null
                && !CODIGO_CATASTRAL_ADMITIDO.matcher(codigoCatastral).matches()) {
            throw new IllegalArgumentException(
                    "El codigo catastral '"
                            + codigoCatastral
                            + "' no es un codigo de referencia catastral: van de 18 a 25 digitos,"
                            + " sin letras ni separadores");
        }
        if (manzana != null && manzana.length() > MANZANA_MAXIMA) {
            throw new IllegalArgumentException(
                    "La manzana '" + manzana + "' excede los " + MANZANA_MAXIMA + " caracteres");
        }
        if (lote != null && lote.length() > LOTE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El lote '" + lote + "' excede los " + LOTE_MAXIMO + " caracteres");
        }
        if (version < 1) {
            throw new IllegalArgumentException(
                    "La primera version de una seccion es la 1; llego " + version);
        }
        if (areaTerreno.valor().signum() <= 0) {
            throw new IllegalArgumentException(
                    "Un terreno de cero metros cuadrados no admite ninguna obra");
        }
    }
}
