package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.util.Objects;
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

    public TerrenoDelFue {
        Objects.requireNonNull(direccion, "El terreno necesita su direccion");
        Objects.requireNonNull(areaTerreno, "El terreno necesita su area");
        Objects.requireNonNull(registradoEn, "La seccion dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        direccion = direccion.strip();
        if (direccion.isEmpty()) {
            throw new IllegalArgumentException("La direccion del terreno no puede estar vacia");
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
