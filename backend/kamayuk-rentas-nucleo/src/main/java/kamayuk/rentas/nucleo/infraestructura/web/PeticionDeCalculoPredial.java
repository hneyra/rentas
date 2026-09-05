package kamayuk.rentas.nucleo.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/rentas/predial/calculo-individual} (#395).
 *
 * <p><b>Lista blanca: lo que no esta aqui no entra.</b> Los importes viajan como texto y se
 * convierten a {@code Dinero} en el controlador, para que ningun {@code double} toque una cifra
 * monetaria (regla 1).
 *
 * <p><b>No hay campo para el porcentaje de propiedad</b>, y es deliberado: sale de {@code
 * titularidad} a la fecha de calculo. Admitirlo aqui dejaria inflar o desinflar la base del
 * contribuyente desde la peticion, y la cifra resultante seria indistinguible de la correcta.
 *
 * <p><b>El autovaluo, en cambio, si se declara</b>, y tambien es deliberado: el sistema no sabe
 * valorizar un predio todavia —faltan el cuadro de valores unitarios y la tabla de depreciacion
 * (GOB-03), los aranceles de la ordenanza (D-02b) y el {@code % actualizacion}, sin fuente (D-11)—,
 * asi que {@code determinacion_predio_detalle} lo guarda declarado desde V20. Hacen falta todos los
 * predios del contribuyente: dejar uno fuera no da una determinacion incompleta, da una mas barata.
 *
 * @param observacion por que se determina (regla 10); obligatoria tambien al simular
 * @param codContribuyente el contribuyente; si falta, se lee el parametro de consulta homonimo
 * @param ejercicio el ejercicio; si falta, se lee el parametro de consulta «ano»
 * @param modalidad el cronograma de cuotas; TRIMESTRAL si no se dice
 * @param simulacion obligatorio: true calcula sin guardar, false asienta la determinacion
 * @param predios el autovaluo declarado de cada predio del contribuyente
 */
public record PeticionDeCalculoPredial(
        @Nullable String observacion,
        @Nullable String codContribuyente,
        @Nullable String ejercicio,
        @Nullable String modalidad,
        @Nullable Boolean simulacion,
        @Nullable List<PredioDelCalculo> predios) {

    /**
     * El autovaluo declarado de un predio.
     *
     * @param predioId el predio, que tiene que estar a nombre del contribuyente
     * @param autovaluo terreno + construccion + obras complementarias (RT-010)
     * @param valuoExonerado la parte no afecta; si falta, ninguna
     */
    public record PredioDelCalculo(
            @Nullable Long predioId, @Nullable String autovaluo, @Nullable String valuoExonerado) {}
}
