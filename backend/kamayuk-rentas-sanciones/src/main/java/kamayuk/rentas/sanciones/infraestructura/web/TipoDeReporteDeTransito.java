package kamayuk.rentas.sanciones.infraestructura.web;

import java.util.List;
import java.util.Set;

/**
 * Los reportes que emite {@code transito_reportes} (#396, RF-068, RF-073, RF-074).
 *
 * <p>Un enumerado y no texto libre: el «tipo de reporte» decide qué consulta se ejecuta, y un texto
 * que no coincida con ninguno tiene que salir como 422 nombrando los que hay, no como una respuesta
 * vacía que parece que no hay datos.
 *
 * <h2>Cada tipo declara los criterios que usa, y los demás se rechazan</h2>
 *
 * <p>Es la diferencia con {@code TipoDeReporteAdministrativo}, y la impone el módulo: allí los tres
 * reportes son de la misma familia y del mismo rango, y un criterio de más no cambia lo que se
 * emite. Aquí hay <b>nueve</b> hojas con criterios que no se parecen —una placa, una licencia, un
 * número de constancia, un año, un agrupador—, y mandar «placa» pidiendo el resumen de recaudación
 * es <b>una pregunta distinta de la que se contesta</b>. Ignorarla en silencio devolvería la
 * recaudación de todas las placas bajo una hoja que quien la pidió cree acotada a una, que es el
 * mismo defecto que {@code PadronesDeTransitoController} rechaza con el ejecutor.
 */
public enum TipoDeReporteDeTransito {

    /** Padrón de papeletas de tránsito por fechas y estado. */
    PADRON(Set.of("desde", "hasta", "estado")),

    /** Padrón de las que ya tienen su resolución de multa emitida. */
    PADRON_COACTIVA(Set.of("desde", "hasta")),

    /** Relación de constancias libres de infracciones emitidas. */
    PADRON_CONSTANCIAS(Set.of("desde", "hasta", "nDeConstancia", "usuarioQueEmitio")),

    /** Historial de infracciones de un conductor. */
    RECORD_CONDUCTOR(Set.of("licencia", "documento")),

    /** Historial de papeletas de un vehículo. */
    RECORD_VEHICULAR(Set.of("placa")),

    /** Lo recaudado por papeletas, según el libro. */
    RESUMEN_RECAUDACION(Set.of("ano")),

    /** Cuántas papeletas hay y por cuánto, agrupadas. */
    RESUMEN_PAPELETAS(Set.of("desde", "hasta", "agrupadoPor")),

    /** El mismo resumen acotado a un código de infracción. */
    RESUMEN_CODIGO(Set.of("codigoDeInfraccion", "desde", "hasta", "estado")),

    /** El mismo resumen agrupado por las dos letras iniciales de la placa. */
    RESUMEN_PLACA(Set.of("iniciales2Letras", "desde", "hasta", "estado"));

    private final Set<String> criterios;

    TipoDeReporteDeTransito(Set<String> criterios) {
        this.criterios = Set.copyOf(criterios);
    }

    /** Los criterios que este reporte usa. {@code reporte} y {@code formato} valen para todos. */
    public Set<String> criterios() {
        return criterios;
    }

    /** Si este reporte usa ese criterio. */
    public boolean admite(String criterio) {
        return criterios.contains(criterio);
    }

    /** Los criterios, ordenados, para poder nombrarlos en el mensaje de un rechazo. */
    public List<String> criteriosOrdenados() {
        return criterios.stream().sorted().toList();
    }
}
