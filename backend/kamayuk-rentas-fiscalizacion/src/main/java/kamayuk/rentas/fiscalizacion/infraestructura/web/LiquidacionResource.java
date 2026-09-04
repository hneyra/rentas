package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeLiquidaciones;
import kamayuk.rentas.fiscalizacion.dominio.CambioEntreVersiones;
import kamayuk.rentas.fiscalizacion.dominio.DiferenciaEntreLiquidaciones;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import org.jspecify.annotations.Nullable;

/**
 * Una liquidación de fiscalización tal como sale por HTTP. Campos en español {@code camelCase}.
 *
 * <h2>Ninguna cifra, y por eso ningún {@code Dinero}</h2>
 *
 * <p>Los importes de una liquidación —base declarada, base hallada, insoluto omitido, multa— son
 * D-02a y D-02c (#198) y hoy no existen. Viajan como {@code null} y con el nombre que la pantalla
 * les da, más la bandera {@code esperaSusCifras} para que la interfaz pueda escribir «sin cifra» en
 * vez de dibujar un cero, que un contribuyente leería como «no debe nada».
 *
 * <p>Como no hay ningún {@code Dinero} en este DTO, la regla {@code
 * TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} no tiene nada que exigir aquí; el día que #198 traiga los
 * importes, esa regla obligará a que viajen con su fecha o con {@code ImporteActualizado}.
 *
 * @param numero el «Nº Liquidación»
 * @param actaId el acta que la origina
 * @param version la liquidación número N de ese acta
 * @param liquidacionAnterior el número de la que reliquida; {@code null} en la versión 1
 * @param periodoDesde primer ejercicio fiscalizado
 * @param periodoHasta último ejercicio fiscalizado
 * @param tipoDeFiscalizacion cómo se determinó lo hallado
 * @param motivoDeterminante por qué se fiscalizó
 * @param fecha el día de la liquidación
 * @param numeroNotificacion el «Nº Notificación», cuando ya se notificó
 * @param estado el derivado del historial
 * @param esperaSusCifras si alguna línea sigue sin importes (D-02a)
 * @param lineas el contraste, una por unidad y ejercicio
 * @param historial la traza de estados
 */
public record LiquidacionResource(
        String numero,
        long actaId,
        int version,
        @Nullable Long liquidacionAnterior,
        int periodoDesde,
        int periodoHasta,
        String tipoDeFiscalizacion,
        String motivoDeterminante,
        String fecha,
        @Nullable String numeroNotificacion,
        String estado,
        boolean esperaSusCifras,
        List<LineaResource> lineas,
        List<MovimientoResource> historial) {

    public static LiquidacionResource de(ConsultaDeLiquidaciones.LiquidacionConsultada consultada) {
        Liquidacion liquidacion = consultada.liquidacion();
        List<LineaResource> lineas = new ArrayList<>();
        for (LineaDeLiquidacion linea : consultada.lineas()) {
            lineas.add(LineaResource.de(linea));
        }
        List<MovimientoResource> historial = new ArrayList<>();
        for (MovimientoDeLiquidacion movimiento : consultada.historial()) {
            historial.add(MovimientoResource.de(movimiento));
        }
        return new LiquidacionResource(
                liquidacion.numero(),
                liquidacion.actaId(),
                liquidacion.version(),
                liquidacion.liquidacionAnteriorId(),
                liquidacion.ejercicioDesde().valor(),
                liquidacion.ejercicioHasta().valor(),
                liquidacion.tipo().name(),
                liquidacion.motivoDeterminante(),
                liquidacion.fecha().toString(),
                liquidacion.numeroNotificacion(),
                consultada.estado().name(),
                consultada.esperaSusCifras(),
                List.copyOf(lineas),
                List.copyOf(historial));
    }

    /**
     * Una línea del contraste.
     *
     * @param ejercicio el ejercicio fiscalizado
     * @param predioId la unidad, si es predial
     * @param vehiculoId la unidad, si es vehicular
     * @param condicion lo que sale de comparar los dos lados
     * @param areaDeclarada superficie declarada
     * @param areaHallada superficie medida en campo
     * @param diferenciaDeArea la diferencia, nunca negativa; {@code null} si falta un lado
     * @param usoDeclarado uso declarado
     * @param usoHallado uso observado
     * @param insolutoOmitido siempre {@code null} hasta D-02a (#198)
     * @param multaTributaria siempre {@code null} hasta D-02a y D-02c (#198)
     */
    public record LineaResource(
            int ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String condicion,
            @Nullable AreaM2 areaDeclarada,
            @Nullable AreaM2 areaHallada,
            @Nullable AreaM2 diferenciaDeArea,
            @Nullable String usoDeclarado,
            @Nullable String usoHallado,
            @Nullable String insolutoOmitido,
            @Nullable String multaTributaria) {

        static LineaResource de(LineaDeLiquidacion linea) {
            return new LineaResource(
                    linea.ejercicio().valor(),
                    linea.predioId(),
                    linea.vehiculoId(),
                    linea.condicion().name(),
                    linea.areaDeclarada(),
                    linea.areaHallada(),
                    linea.diferenciaDeArea(),
                    linea.usoDeclarado(),
                    linea.usoHallado(),
                    cifra(linea.insolutoOmitido()),
                    cifra(linea.multaTributaria()));
        }

        /**
         * La cifra desnuda, sin moneda: {@code "120.00"}.
         *
         * <p>Las superficies ya no pasan por aqui: viajan como {@link AreaM2} y las escribe el
         * serializador que {@code ConfiguracionDeJson} registra, que es lo que hace que las cuatro
         * proyecciones del modulo digan lo mismo (#546). Lo que sigue pasando son los importes, que
         * son {@code Dinero} y no pueden viajar tipados porque la regla de ArchUnit
         * TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA exigiria su {@code actualizadoA}, y estos no lo
         * tienen -son cifras congeladas del contraste, no deuda a una fecha-.
         */
        private static @Nullable String cifra(@Nullable Object valor) {
            return switch (valor) {
                case null -> null;
                case kamayuk.rentas.dominio.Dinero dinero -> dinero.valor().toPlainString();
                default -> valor.toString();
            };
        }
    }

    /**
     * Un movimiento del historial.
     *
     * @param tipo apertura o cambio de estado
     * @param estado en qué estado deja la liquidación
     * @param fecha el día del acto
     * @param motivo por qué
     * @param usuario quién
     */
    public record MovimientoResource(
            String tipo, String estado, String fecha, String motivo, @Nullable String usuario) {

        static MovimientoResource de(MovimientoDeLiquidacion movimiento) {
            return new MovimientoResource(
                    movimiento.tipo().name(),
                    movimiento.estado().name(),
                    movimiento.fecha().toString(),
                    movimiento.motivo(),
                    movimiento.usuarioRegistro());
        }
    }

    /**
     * Una versión dentro del histórico, con lo que la separa de la anterior (AC 2 y AC 5).
     *
     * @param version la liquidación
     * @param cambios qué cambió respecto de la anterior; vacío en la primera
     * @param importesSinCifra qué líneas siguen esperando a D-02a
     */
    public record VersionResource(
            LiquidacionResource version,
            List<CambioResource> cambios,
            List<String> importesSinCifra) {

        public static VersionResource de(ConsultaDeLiquidaciones.VersionDelProceso version) {
            DiferenciaEntreLiquidaciones diferencia = version.diferencia();
            List<CambioResource> cambios = new ArrayList<>();
            List<String> pendientes = List.of();
            if (diferencia != null) {
                for (CambioEntreVersiones cambio : diferencia.cambios()) {
                    cambios.add(
                            new CambioResource(
                                    cambio.concepto(), cambio.antes(), cambio.despues()));
                }
                pendientes = diferencia.importesSinCifra();
            }
            return new VersionResource(
                    LiquidacionResource.de(version.version()),
                    List.copyOf(cambios),
                    List.copyOf(pendientes));
        }
    }

    /**
     * Un cambio entre versiones.
     *
     * @param concepto qué cambió
     * @param antes lo que decía la anterior
     * @param despues lo que dice la nueva
     */
    public record CambioResource(
            String concepto, @Nullable String antes, @Nullable String despues) {}
}
