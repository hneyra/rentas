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
import kamayuk.rentas.fiscalizacion.dominio.TotalesDeLaDeterminacion;
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
 * <h2>Los tres totales, y por que los suma el backend (#193)</h2>
 *
 * <p>Hasta #193 esta respuesta publicaba {@code lineas[]} y <b>ningun agregado de la resolucion
 * entera</b>, asi que «Insoluto omitido», «Multa tributaria» y «Total liquidado» —tres de los seis
 * campos de solo lectura de su pantalla— decian «no publicado». Sumarlos en el navegador daria tres
 * cifras al centimo indistinguibles de unas liquidadas, sobre el papel que vuelve una diferencia
 * deuda exigible; los suma {@link TotalesDeLaDeterminacion}, que es quien puede cuadrarlas con lo
 * que se asienta en el libro. Con cualquier sumando ausente el total sale <b>nulo</b>, nunca
 * parcial.
 *
 * <p>Lo mismo dentro de cada linea con {@code baseOmitida}, que es la columna «Base omitida S/»:
 * los dos sumandos ya viajaban y la resta no.
 *
 * <h2>Lo que sigue sin publicarse, y no por falta de DTO: el INTERES</h2>
 *
 * <p>El artboard dibuja dos sitios de interes —el campo «Interes» y la columna «Interes S/»— y
 * <b>nada del backend puede llenarlos</b>: no hay en este sistema ninguna tasa de interes moratorio
 * sellada, y ponerle una seria inventar un valor normativo (regla 5). El javadoc de {@code
 * ModeloDeLaResolucionDeDeterminacion} ya lo dejaba escrito: el cuadro que <b>se imprime</b> lleva
 * {@code Multa S/} donde el prototipo decia Interes, y el JSON es identico al impreso. O se sella
 * la tasa o se quita del artboard; las dos son decisiones y ninguna se toma desde aqui.
 *
 * @param numero el numero de la resolucion, que es el de su documento
 * @param fecha el dia del acto
 * @param aLaFecha el dia al que estan las cifras; el mismo, y dicho aparte para no dejarlo
 *     implicito
 * @param actaId el acta de inspeccion de la que salio la liquidacion. <b>Es un identificador
 *     interno y no el numero de un documento</b>: un acta no se numera —lo que la identifica es su
 *     programa, su unidad y su version— asi que esto sirve para enlazar hacia atras, no para
 *     escribirlo en un campo rotulado «N.º de acta» (#193)
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
 * @param insolutoOmitido la suma del tributo dejado de pagar de todas las lineas; nulo hasta D-02a
 * @param multaTributaria la suma de las multas de todas las lineas; nulo hasta D-02a y D-02c
 * @param totalLiquidado la suma de los dos anteriores; nulo si falta cualquiera
 * @param esperaSusCifras si los totales siguen pendientes, para que la pantalla escriba «sin cifra»
 *     en vez de un cero — que un contribuyente leeria como «no debe nada»
 * @param lineas el cuadro de la determinacion, ejercicio por ejercicio
 * @param cargosAsentados cuantos cargos genero; solo en la respuesta de la transferencia
 */
public record ResolucionResource(
        String numero,
        String fecha,
        String aLaFecha,
        long actaId,
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
        @Nullable String insolutoOmitido,
        @Nullable String multaTributaria,
        @Nullable String totalLiquidado,
        boolean esperaSusCifras,
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

        TotalesDeLaDeterminacion totales = TotalesDeLaDeterminacion.de(consultada.lineas());

        return new ResolucionResource(
                resolucion.numero(),
                resolucion.fecha().toString(),
                resolucion.fecha().toString(),
                consultada.liquidacion().actaId(),
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
                LineaDeterminadaResource.cifra(totales.insolutoOmitido()),
                LineaDeterminadaResource.cifra(totales.multaTributaria()),
                LineaDeterminadaResource.cifra(totales.total()),
                totales.esperaSusCifras(),
                List.copyOf(lineas),
                cargos);
    }

    /**
     * Una fila del cuadro que la pantalla {@code resolucion_determinacion_fisc} pinta.
     *
     * @param ejercicio el ejercicio determinado
     * @param determinado la base que resulta de lo hallado; nula hasta D-02a (#198)
     * @param declarado la base que consta declarada; nula hasta D-02a
     * @param baseOmitida la base que no se declaro —{@code determinado} menos {@code declarado}—,
     *     hecha aqui y no en la pantalla; nula hasta D-02a y nunca negativa (#193)
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
            @Nullable String baseOmitida,
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
                    cifra(linea.baseOmitida()),
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
        static @Nullable String cifra(@Nullable Object valor) {
            return switch (valor) {
                case null -> null;
                case Dinero dinero -> dinero.valor().toPlainString();
                default -> valor.toString();
            };
        }
    }
}
