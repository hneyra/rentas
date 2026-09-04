package kamayuk.rentas.rentas.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Quien tiene derecho a que beneficio, desde cuando, hasta cuando y por que norma (RF-029).
 *
 * <p>Es registro puro: <b>no calcula nada</b>. Cuanto deduce un pensionista o que descuento trae
 * una amnistía es D-02; este tipo solo guarda que el beneficio existe, para quien y con que
 * sustento. Se puede construir sin ninguna cifra normativa.
 *
 * <p><b>Nunca se borra.</b> Un cese dentro deja la fila con {@code vigenciaHasta}, no la quita (V7:
 * {@code beneficio} tiene {@code UPDATE}, no {@code DELETE}).
 *
 * <p>{@code porcentaje} es {@link Alicuota} y no {@link kamayuk.rentas.dominio.Porcentaje}: la
 * columna usa el dominio {@code alicuota} de PostgreSQL, que admite el 0 —«una alícuota puede ser
 * nula por beneficio», dice su propio javadoc— y aquí es exactamente ese caso.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param contribuyenteId el titular del beneficio
 * @param predioId el predio, cuando el ámbito es por predio
 * @param vehiculoId el vehículo, cuando el ámbito es por vehículo
 * @param tipo el nombre del beneficio tal como lo trae el manual: pensionista, monumento histórico,
 *     predio rústico...
 * @param tributo a que tributo se aplica
 * @param clase inafectación, exoneración, deducción o descuento
 * @param porcentaje la proporción que descuenta, si se expresa así
 * @param monto el importe fijo que descuenta, si se expresa así
 * @param vigenciaDesde desde cuando rige
 * @param vigenciaHasta nulo mientras el beneficio está vigente
 * @param baseLegal la norma que lo sustenta; sin ella no se guarda
 * @param documentoOrigen el documento con que se registró
 * @param observacion por que se registra, escrito por quien lo hace (regla 10)
 */
public record Beneficio(
        @Nullable Long id,
        long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        String tipo,
        String tributo,
        Clase clase,
        @Nullable Alicuota porcentaje,
        @Nullable Dinero monto,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta,
        String baseLegal,
        String documentoOrigen,
        Observacion observacion) {

    private static final int TIPO_MAXIMO = 40;
    private static final int TRIBUTO_MAXIMO = 20;
    private static final int BASE_LEGAL_MAXIMA = 200;
    private static final int DOCUMENTO_MAXIMO = 80;

    public Beneficio {
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "Un beneficio tiene un titular: el identificador de contribuyente debe ser"
                            + " positivo");
        }
        Objects.requireNonNull(tipo, "El beneficio necesita su tipo");
        tipo = tipo.strip();
        if (tipo.isEmpty() || tipo.length() > TIPO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El tipo de beneficio va de 1 a "
                            + TIPO_MAXIMO
                            + " caracteres: '"
                            + tipo
                            + "'");
        }
        Objects.requireNonNull(tributo, "El beneficio necesita saber a que tributo se aplica");
        tributo = tributo.strip().toUpperCase(java.util.Locale.ROOT);
        if (tributo.isEmpty() || tributo.length() > TRIBUTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El tributo va de 1 a " + TRIBUTO_MAXIMO + " caracteres: '" + tributo + "'");
        }
        Objects.requireNonNull(clase, "El beneficio necesita su clase");
        if (porcentaje == null && monto == null) {
            throw new IllegalArgumentException(
                    "El beneficio necesita su porcentaje, su monto, o los dos"
                            + " (beneficio_valor_ck)");
        }
        if (monto != null && monto.esNegativo()) {
            throw new IllegalArgumentException("El monto de un beneficio no puede ser negativo");
        }
        Objects.requireNonNull(vigenciaDesde, "El beneficio necesita desde cuando rige");
        if (vigenciaHasta != null && vigenciaHasta.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "Un beneficio no puede dejar de regir antes de empezar: "
                            + vigenciaDesde
                            + ".."
                            + vigenciaHasta);
        }
        Objects.requireNonNull(
                baseLegal, "Un beneficio sin base legal no se guarda (criterio de aceptacion)");
        baseLegal = baseLegal.strip();
        if (baseLegal.isEmpty() || baseLegal.length() > BASE_LEGAL_MAXIMA) {
            throw new IllegalArgumentException(
                    "La base legal va de 1 a " + BASE_LEGAL_MAXIMA + " caracteres");
        }
        Objects.requireNonNull(documentoOrigen, "El beneficio necesita su documento de origen");
        documentoOrigen = documentoOrigen.strip();
        if (documentoOrigen.isEmpty() || documentoOrigen.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento de origen va de 1 a " + DOCUMENTO_MAXIMO + " caracteres");
        }
        Objects.requireNonNull(
                observacion, "Sin observacion no se guarda un beneficio (regla 10, RNF-052)");
    }

    /** Un beneficio nuevo, todavia sin guardar y sin cesar. */
    public static Beneficio nuevo(
            long contribuyenteId,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String tipo,
            String tributo,
            Clase clase,
            @Nullable Alicuota porcentaje,
            @Nullable Dinero monto,
            LocalDate vigenciaDesde,
            String baseLegal,
            String documentoOrigen,
            Observacion observacion) {
        return new Beneficio(
                null,
                contribuyenteId,
                predioId,
                vehiculoId,
                tipo,
                tributo,
                clase,
                porcentaje,
                monto,
                vigenciaDesde,
                null,
                baseLegal,
                documentoOrigen,
                observacion);
    }

    public boolean esNuevo() {
        return id == null;
    }

    public boolean estaVigente() {
        return vigenciaHasta == null;
    }

    /** Si rige en esa fecha. Los dos extremos entran (regla 9). */
    public boolean rigeEn(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Preguntar por la vigencia exige la fecha");
        if (fecha.isBefore(vigenciaDesde)) {
            return false;
        }
        return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
    }

    /**
     * Si el rango de vigencia de este beneficio se cruza con el del otro.
     *
     * <p>Es una funcion pura sobre dos intervalos, para poder probarla sin base de datos. Quien
     * decide con que otros beneficios comparar —mismo contribuyente, mismo tipo— es {@code
     * RegistrarBeneficio}, que trae los candidatos del repositorio.
     */
    public boolean solapaCon(Beneficio otro) {
        boolean empiezaAntesDeQueElOtroTermine =
                otro.vigenciaHasta == null || !vigenciaDesde.isAfter(otro.vigenciaHasta);
        boolean terminaDespuesDeQueElOtroEmpiece =
                vigenciaHasta == null || !vigenciaHasta.isBefore(otro.vigenciaDesde);
        return empiezaAntesDeQueElOtroTermine && terminaDespuesDeQueElOtroEmpiece;
    }

    /**
     * Cierra el beneficio. Sus demas datos no se tocan: lo unico que cambia es hasta cuando rige.
     */
    public Beneficio cesadoEl(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Cesar un beneficio exige la fecha");
        if (!estaVigente()) {
            throw new IllegalStateException("El beneficio ya se ceso el " + vigenciaHasta);
        }
        if (fecha.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "No se puede cesar el "
                            + fecha
                            + " un beneficio que empezo a regir el "
                            + vigenciaDesde);
        }
        return new Beneficio(
                id,
                contribuyenteId,
                predioId,
                vehiculoId,
                tipo,
                tributo,
                clase,
                porcentaje,
                monto,
                vigenciaDesde,
                fecha,
                baseLegal,
                documentoOrigen,
                observacion);
    }
}
