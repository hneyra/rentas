package kamayuk.rentas.rentas.infraestructura.web;

import java.util.Objects;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * Un predio del padron predial de rentas, tal como lo lee {@code predios_rentas} ({@code GET
 * /api/v1/rentas/predios}, #395).
 *
 * <p><b>Sin autovaluo, y no por olvido.</b> El autovaluo de un predio no esta almacenado en ningun
 * sitio ni se puede derivar todavia: {@code determinacion_predio_detalle} (V20) lo guarda declarado
 * por quien determina, y llegar a el desde la ficha exige el cuadro de valores unitarios y la tabla
 * de depreciacion —a las dos les falta una dimension que la norma si tiene, GOB-03 H-14/H-15—, los
 * aranceles de la ordenanza (D-02b) y el {@code % actualizacion}, sin fuente identificada (D-11).
 * Publicar aqui una columna de dinero siempre en blanco seria peor que no publicarla: una cifra
 * ausente y un cero no se distinguen en una grilla. Quien quiera el autovaluo del ejercicio lo
 * encuentra en la determinacion, que es donde se declara.
 *
 * <p>El porcentaje viaja como texto y no como {@code Porcentaje} por lo mismo que en los demas
 * recursos: lo que sale al JSON es la cifra que se dibuja, sin arrastrar el tipo del dominio.
 *
 * <p><b>Las areas viajan tipadas</b> (#607). Las escribe el serializador que {@code
 * ConfiguracionDeJson} registra para {@code AreaM2}, o sea la cifra sola —{@code "360.00"}—, y la
 * unidad la pone la cabecera de la columna. Metida dentro del dato obliga a cada consumidor a
 * recortarla antes de comparar, y era la diferencia por la que el mismo predio decia «360.00 m2»
 * aqui y «360.00» en fiscalizacion.
 *
 * @param predioId el identificador interno, que es con el que se declara el autovaluo al determinar
 * @param codigoReferenciaCatastral el codigo con el que se le nombra en el padron
 * @param tipo URBANO o RUSTICO
 * @param direccion donde esta
 * @param uso para que se usa, de la ficha catastral vigente; nulo si el predio no tiene ficha
 * @param sector el sector catastral; nulo si la ficha no lo trae
 * @param areaTerreno el area del terreno en metros cuadrados; nula si la ficha no la trae
 * @param porcentajePropiedad la cuota del contribuyente sobre el predio, de {@code titularidad}
 * @param condicion en que condicion la tiene: PROPIETARIO_UNICO, COPROPIETARIO, SUCESION...
 */
public record PredioDeRentasResource(
        long predioId,
        String codigoReferenciaCatastral,
        String tipo,
        String direccion,
        @Nullable String uso,
        @Nullable String sector,
        @Nullable AreaM2 areaTerreno,
        String porcentajePropiedad,
        @Nullable String condicion) {

    public PredioDeRentasResource {
        Objects.requireNonNull(codigoReferenciaCatastral, "El predio necesita su codigo");
        Objects.requireNonNull(tipo, "El predio necesita su tipo");
        Objects.requireNonNull(direccion, "El predio necesita su direccion");
        Objects.requireNonNull(porcentajePropiedad, "El predio necesita el % de propiedad");
    }

    public static PredioDeRentasResource de(
            PredioDelContribuyente predio,
            @Nullable CaracteristicasDelPredio rasgos,
            @Nullable String condicion) {
        AreaM2 area = rasgos == null ? null : rasgos.areaTerreno();
        return new PredioDeRentasResource(
                predio.predioId(),
                predio.codigoReferenciaCatastral(),
                predio.tipo(),
                predio.direccion(),
                rasgos == null ? null : rasgos.uso(),
                rasgos == null ? null : rasgos.sectorCodigo(),
                area,
                predio.porcentajeTitularidad().valor().toPlainString(),
                condicion);
    }
}
