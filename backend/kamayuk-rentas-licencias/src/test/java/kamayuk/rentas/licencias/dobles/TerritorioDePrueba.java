package kamayuk.rentas.licencias.dobles;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.catastro.ItseDelPredio;
import kamayuk.rentas.catastro.RiesgoDelPredio;
import kamayuk.rentas.catastro.RiesgoYItseDelPredio;
import kamayuk.rentas.catastro.ZonaDelPredio;
import kamayuk.rentas.catastro.ZonificacionDelPredio;
import kamayuk.rentas.licencias.aplicacion.ComprobarElTerritorio;

/**
 * El territorio que contesta lo que la prueba necesite (#43).
 *
 * <h2>Por que existe uno «en regla» y no se usa el fixture de memoria por omision</h2>
 *
 * <p>Las pruebas que ya existian miden <b>otra cosa</b> —el recibo del derecho, el correlativo, el
 * papel, la cancelacion— y no el territorio. Si el doble contestara «no consta», todas exigirian la
 * autorizacion expresa de #43 y estarian midiendo el territorio sin querer: el rojo de cualquiera
 * de ellas dejaria de hablar de lo suyo.
 *
 * <p>Asi que la premisa que se les escribe es la que necesitan —«este predio esta comprobado y todo
 * esta en regla»— y el territorio se mide donde toca, en las pruebas que #43 anade. Es la misma
 * decision que {@code CatastroEnMemoria} explica: el fixture existe para escribir la premisa.
 */
public final class TerritorioDePrueba {

    private TerritorioDePrueba() {}

    /** Todo comprobado y nada que se oponga, para cualquier predio y cualquier fecha. */
    public static ComprobarElTerritorio enRegla(String zona) {
        ZonificacionDelPredio zonificacion =
                (predioId, aLaFecha) ->
                        new ZonaDelPredio(
                                aLaFecha,
                                zona,
                                "Zona " + zona,
                                "PDU-DEMO",
                                "ORD-2024-DEMO",
                                LocalDate.of(2020, 1, 1),
                                null,
                                List.of());
        RiesgoYItseDelPredio riesgoYItse =
                new RiesgoYItseDelPredio() {
                    @Override
                    public RiesgoDelPredio riesgoDe(long predioId, LocalDate aLaFecha) {
                        return new RiesgoDelPredio(predioId, aLaFecha, false, List.of(), List.of());
                    }

                    @Override
                    public ItseDelPredio itseVigenteEn(long predioId, LocalDate aLaFecha) {
                        return new ItseDelPredio(predioId, aLaFecha, List.of());
                    }
                };
        return new ComprobarElTerritorio(zonificacion, riesgoYItse);
    }
}
