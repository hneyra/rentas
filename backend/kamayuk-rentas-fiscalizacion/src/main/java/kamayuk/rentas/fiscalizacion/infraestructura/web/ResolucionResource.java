package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeResoluciones;
import kamayuk.rentas.fiscalizacion.aplicacion.TransferirARentas;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import org.jspecify.annotations.Nullable;

/**
 * La resolucion de determinacion de fiscalizacion tal como sale por HTTP (#52, RF-054, RF-057).
 *
 * <h2>Las cifras van como texto, y las que faltan van nulas</h2>
 *
 * <p>Mismo criterio que {@code LiquidacionResource}: la cifra desnuda, sin unidad y sin moneda, que
 * es lo que la pantalla sabe pintar. Y lo que <b>no</b> se hace es rellenar con cero lo que D-02a
 * todavia no permite determinar: un cero se lee como «no debe nada» y esto es un valor notificable.
 *
 * <p>{@code aLaFecha} esta en la raiz y no repetido en cada linea: todas las cifras de esta
 * respuesta son del dia de la resolucion, que es cuando se congelaron (regla 9, RNF-075).
 *
 * @param numero el numero de la resolucion, que es el de su documento
 * @param fecha el dia del acto
 * @param aLaFecha el dia al que estan las cifras; el mismo, y dicho aparte para no dejarlo
 *     implicito
 * @param nLiquidacion la liquidacion que transfirio
 * @param versionDeLaLiquidacion que version de esa liquidacion
 * @param periodoDesde primer ejercicio fiscalizado
 * @param periodoHasta ultimo ejercicio fiscalizado
 * @param codContribuyente el codigo del obligado
 * @param contribuyente su nombre
 * @param predioId la unidad, si es predial
 * @param vehiculoId la unidad, si es vehicular
 * @param documentoSustento el papel que sustenta el acto (AC 3)
 * @param sustento el fundamento
 * @param baseLegal la norma que la ampara
 * @param fichaAnteriorId la version de ficha que cerro; nula en una vehicular
 * @param fichaNuevaId la version de ficha que abrio; nula en una vehicular
 * @param usuarioRegistro quien la registro
 * @param observacion por que se registro (RNF-052)
 * @param lineas el cuadro de la determinacion, ejercicio por ejercicio
 * @param cargosAsentados cuantos cargos genero; solo en la respuesta de la transferencia
 */
public record ResolucionResource(
        String numero,
        String fecha,
        String aLaFecha,
        String nLiquidacion,
        int versionDeLaLiquidacion,
        int periodoDesde,
        int periodoHasta,
        @Nullable String codContribuyente,
        @Nullable String contribuyente,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        String documentoSustento,
        String sustento,
        String baseLegal,
        @Nullable Long fichaAnteriorId,
        @Nullable Long fichaNuevaId,
        @Nullable String usuarioRegistro,
        String observacion,
        List<LineaDeterminadaResource> lineas,
        @Nullable Integer cargosAsentados) {

    /** La resolucion leida, sin el recuento de cargos —que es del acto, no de la consulta—. */
    public static ResolucionResource de(ConsultaDeResoluciones.ResolucionConsultada consultada) {
        return componer(consultada, null);
    }

    /** La resolucion recien dictada, con lo que la transferencia movio en el libro. */
    public static ResolucionResource de(
            ConsultaDeResoluciones.ResolucionConsultada consultada,
            TransferirARentas.Transferencia transferencia) {
        return componer(consultada, transferencia.cargosAsentados());
    }

    private static ResolucionResource componer(
            ConsultaDeResoluciones.ResolucionConsultada consultada, @Nullable Integer cargos) {
        ResolucionDeDeterminacion resolucion = consultada.resolucion();
        ResumenDeContribuyente obligado = consultada.contribuyente();

        List<LineaDeterminadaResource> lineas = new ArrayList<>();
        for (LineaDeLiquidacion linea : consultada.lineas()) {
            lineas.add(LineaDeterminadaResource.de(linea));
        }

        return new ResolucionResource(
                resolucion.numero(),
                resolucion.fecha().toString(),
                resolucion.fecha().toString(),
                consultada.liquidacion().numero(),
                consultada.liquidacion().version(),
                consultada.liquidacion().ejercicioDesde().valor(),
                consultada.liquidacion().ejercicioHasta().valor(),
                obligado == null ? null : obligado.codigo(),
                obligado == null ? null : obligado.nombre(),
                resolucion.predioId(),
                resolucion.vehiculoId(),
                resolucion.documentoSustento(),
                resolucion.sustento(),
                resolucion.baseLegal(),
                resolucion.fichaAnteriorId(),
                resolucion.fichaNuevaId(),
                resolucion.usuarioRegistro(),
                resolucion.observacion().texto(),
                List.copyOf(lineas),
                cargos);
    }

    /**
     * Una fila del cuadro que la pantalla {@code resolucion_determinacion_fisc} pinta.
     *
     * @param ejercicio el ejercicio determinado
     * @param determinado la base que resulta de lo hallado; nula hasta D-02a (#198)
     * @param declarado la base que consta declarada; nula hasta D-02a
     * @param diferencia el tributo que se dejo de pagar; nula hasta D-02a
     * @param multa la multa del art. 176; nula hasta D-02a y D-02c
     * @param total la suma de las dos anteriores; nula si falta cualquiera
     * @param condicion la condicion hallada, que si se conoce siempre
     * @param areaDeclarada la superficie que constaba declarada
     * @param areaHallada la superficie medida en campo
     */
    public record LineaDeterminadaResource(
            int ejercicio,
            @Nullable String determinado,
            @Nullable String declarado,
            @Nullable String diferencia,
            @Nullable String multa,
            @Nullable String total,
            String condicion,
            @Nullable AreaM2 areaDeclarada,
            @Nullable AreaM2 areaHallada) {

        static LineaDeterminadaResource de(LineaDeLiquidacion linea) {
            return new LineaDeterminadaResource(
                    linea.ejercicio().valor(),
                    cifra(linea.baseHallada()),
                    cifra(linea.baseDeclarada()),
                    cifra(linea.insolutoOmitido()),
                    cifra(linea.multaTributaria()),
                    cifra(total(linea)),
                    linea.condicion().name(),
                    linea.areaDeclarada(),
                    linea.areaHallada());
        }

        /**
         * La suma de la diferencia y la multa, y solo si las dos se conocen.
         *
         * <p>Sumar una cifra con una ausencia daria la cifra, y la pantalla mostraria un total que
         * no incluye lo que falta. Mientras falte cualquiera de las dos, el total tambien esta
         * pendiente. Es la misma regla que el papel aplica.
         */
        private static @Nullable Dinero total(LineaDeLiquidacion linea) {
            Dinero diferencia = linea.insolutoOmitido();
            Dinero multa = linea.multaTributaria();
            return diferencia == null || multa == null ? null : diferencia.mas(multa);
        }

        /**
         * La cifra desnuda, sin moneda: la moneda la pinta la pantalla, que sabe en que columna.
         *
         * <p>Las dos superficies ya no pasan por aqui: viajan como {@link AreaM2} y las escribe el
         * serializador de {@code ConfiguracionDeJson} (#546).
         */
        private static @Nullable String cifra(@Nullable Object valor) {
            return switch (valor) {
                case null -> null;
                case Dinero dinero -> dinero.valor().toPlainString();
                default -> valor.toString();
            };
        }
    }
}
