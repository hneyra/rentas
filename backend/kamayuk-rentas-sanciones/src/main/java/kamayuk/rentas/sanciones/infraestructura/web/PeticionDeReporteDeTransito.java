package kamayuk.rentas.sanciones.infraestructura.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/transito/reportes} (#396, RF-068, RF-073, RF-074). <b>Lista
 * blanca</b>: lo que no está aquí no entra.
 *
 * <p>Es el «emisor de reportes» del manual: una sola pantalla que emite nueve, y por eso el tipo de
 * reporte viaja en el cuerpo.
 *
 * <p><b>Un criterio que el reporte no usa se rechaza, no se ignora.</b> {@link
 * TipoDeReporteDeTransito} declara los suyos y {@link #criteriosDeMas} devuelve los que sobran, con
 * su nombre, para que el 422 diga cuál. La alternativa —aceptarlos y no mirarlos— devolvería una
 * hoja correcta a una pregunta que no es la que se hizo: pedir el resumen de recaudación «de la
 * placa NB-21169» daría el de todas las placas, y nada en el papel lo diría.
 *
 * @param reporte cuál de los nueve se emite
 * @param desde primer día del intervalo
 * @param hasta último día del intervalo
 * @param estado acota el estado de la papeleta
 * @param nDeConstancia número de la constancia libre
 * @param usuarioQueEmitio quién emitió la constancia
 * @param licencia licencia de conducir del infractor, para el record de conductor
 * @param documento documento del infractor, alternativo a la licencia
 * @param placa placa del vehículo, para el record vehicular
 * @param ano ejercicio del resumen de recaudación
 * @param agrupadoPor cómo se agrupa el resumen de papeletas
 * @param codigoDeInfraccion el código del catálogo, para el resumen por código
 * @param iniciales2Letras las dos primeras letras de la placa, para el resumen por placa
 * @param formato PDF, XLS o RTF; si falta, sale el JSON en vez del documento (RF-132)
 */
public record PeticionDeReporteDeTransito(
        @Nullable String reporte,
        @Nullable String desde,
        @Nullable String hasta,
        @Nullable String estado,
        @Nullable String nDeConstancia,
        @Nullable String usuarioQueEmitio,
        @Nullable String licencia,
        @Nullable String documento,
        @Nullable String placa,
        @Nullable String ano,
        @Nullable String agrupadoPor,
        @Nullable String codigoDeInfraccion,
        @Nullable String iniciales2Letras,
        @Nullable String formato) {

    /**
     * Los criterios que llegaron con valor y que ese reporte no usa, por su nombre.
     *
     * <p>Se miran <b>solo los que traen valor</b>: la pantalla manda su formulario entero y los
     * campos en blanco no son una pregunta. Un 422 por un campo vacío impediría emitir cualquier
     * hoja desde el emisor.
     */
    public java.util.List<String> criteriosDeMas(TipoDeReporteDeTransito reporte) {
        Map<String, String> conValor = new LinkedHashMap<>();
        poner(conValor, "desde", desde);
        poner(conValor, "hasta", hasta);
        poner(conValor, "estado", estado);
        poner(conValor, "nDeConstancia", nDeConstancia);
        poner(conValor, "usuarioQueEmitio", usuarioQueEmitio);
        poner(conValor, "licencia", licencia);
        poner(conValor, "documento", documento);
        poner(conValor, "placa", placa);
        poner(conValor, "ano", ano);
        poner(conValor, "agrupadoPor", agrupadoPor);
        poner(conValor, "codigoDeInfraccion", codigoDeInfraccion);
        poner(conValor, "iniciales2Letras", iniciales2Letras);

        return conValor.keySet().stream().filter(criterio -> !reporte.admite(criterio)).toList();
    }

    private static void poner(Map<String, String> destino, String nombre, @Nullable String valor) {
        if (valor != null && !valor.isBlank()) {
            destino.put(nombre, valor.strip());
        }
    }
}
