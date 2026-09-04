package kamayuk.rentas.rentas;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Un beneficio tal como cruza la frontera del modulo (#42, RF-107).
 *
 * <p>Es la proyeccion de {@code Beneficio} —que vive en {@code .dominio} y no cruza— reducida a lo
 * que otro contexto necesita para <b>nombrarlo</b>: que beneficio es, de que clase, a que tributo
 * se aplica, con que norma se sustenta y desde cuando rige.
 *
 * <p>{@link #porcentajeDeclarado} y {@link #montoDeclarado} son <b>lo que la norma dice</b>,
 * transcrito al registrar el beneficio. No son un descuento calculado, y quien los consuma no puede
 * convertirlos en uno: sobre que base se aplican, en que orden y con que redondeo es D-02b (#191).
 * Viajan para que la pantalla pueda escribir «AMNISTIA COACTIVA 2026 — 50 % (Ordenanza 015-2026)»
 * junto a una deuda sin fingir haberla recalculado.
 *
 * @param tipo el nombre del beneficio tal como lo trae el manual
 * @param clase inafectacion, exoneracion, deduccion o descuento
 * @param tributo a que tributo se aplica
 * @param porcentajeDeclarado la proporcion que la norma declara, si se expresa asi
 * @param montoDeclarado el importe fijo que la norma declara, si se expresa asi
 * @param baseLegal la norma que lo sustenta
 * @param vigenciaDesde desde cuando rige
 * @param vigenciaHasta hasta cuando; nulo mientras siga vigente
 */
public record BeneficioRegistrado(
        String tipo,
        String clase,
        String tributo,
        @Nullable Alicuota porcentajeDeclarado,
        @Nullable Dinero montoDeclarado,
        String baseLegal,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta) {

    public BeneficioRegistrado {
        Objects.requireNonNull(tipo, "El beneficio necesita su tipo");
        Objects.requireNonNull(clase, "El beneficio necesita su clase");
        Objects.requireNonNull(tributo, "El beneficio necesita su tributo");
        Objects.requireNonNull(
                baseLegal,
                "Un beneficio sin base legal no se muestra: no se sabria por que existe");
        Objects.requireNonNull(vigenciaDesde, "El beneficio dice desde cuando rige");
    }

    /** Si rige en esa fecha. Los dos extremos entran (regla 9). */
    public boolean rigeEn(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Preguntar por la vigencia exige la fecha");
        if (fecha.isBefore(vigenciaDesde)) {
            return false;
        }
        return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
    }
}
