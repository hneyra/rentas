package kamayuk.rentas.sanciones.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Las resoluciones de gerencia contra PostgreSQL. Ningún método recibe la municipalidad (regla 2).
 *
 * <p><b>Solo inserta.</b> V41 no le concede a {@code kamayuk_app} ni {@code UPDATE} ni {@code
 * DELETE} sobre {@code resolucion_gerencia}, por lo mismo que V34 se los negó a {@code
 * acto_coactivo}: la resolución se notifica al administrado, que se lleva el papel. Una equivocada
 * se deja sin efecto con otra, y las dos quedan.
 */
public interface ResolucionDeGerenciaRepository {

    /**
     * Inserta la resolución.
     *
     * <p><b>Una por papeleta solo para la ordinaria y la sancionadora</b> (#384). Son las dos que
     * tienen índice único ({@code resolucion_gerencia_ordinaria_uq} y {@code ..._sancionadora_uq});
     * la {@link TipoDeResolucionDeGerencia#ADMINISTRATIVA} no lo tiene, y no es un olvido: la RIS y
     * la resolución que resuelve cada recurso contra ella son del mismo tipo y conviven. Lo que sí
     * es único para todas es el recurso resuelto ({@code ..._descargo_uq}). Hasta #384 este
     * contrato prometía «una de ese tipo» para las tres, y la corrida lo creyó.
     *
     * @throws ResolucionDuplicada si la papeleta ya tiene su ordinaria o su sancionadora y se
     *     registra otra, o si el descargo ya está resuelto. La garantía son los índices únicos
     *     parciales de V41, no un {@code if}: dos peticiones simultáneas pasan las dos por
     *     cualquier comprobación en Java
     */
    ResolucionDeGerencia registrar(ResolucionDeGerencia resolucion);

    Optional<ResolucionDeGerencia> porNumero(String numero);

    Optional<ResolucionDeGerencia> porId(long id);

    /**
     * La resolución de ese tipo dictada sobre la papeleta, si la hay.
     *
     * <p><b>Solo para un tipo que el índice hace único</b>: la ordinaria o la sancionadora (#384).
     * Con la {@link TipoDeResolucionDeGerencia#ADMINISTRATIVA} puede haber varias, y entonces lanza
     * {@code IncorrectResultSizeDataAccessException}, que ningún reintento arregla. Quien necesita
     * la administrativa lee {@link #dePapeleta(long)} y le pregunta a la política que decide cuál
     * vale —{@link CorridaDeValores#laQueOrdenaLaCobranza} para la cobranza—.
     */
    Optional<ResolucionDeGerencia> dePapeleta(long papeletaId, TipoDeResolucionDeGerencia tipo);

    /** Todas las resoluciones de una papeleta, de la más antigua a la más reciente. */
    List<ResolucionDeGerencia> dePapeleta(long papeletaId);

    /** La resolución que resolvió ese descargo, si ya se dictó. */
    Optional<ResolucionDeGerencia> queResuelve(long descargoId);

    /** La papeleta ya tiene su ordinaria o su sancionadora, o el descargo ya está resuelto. */
    final class ResolucionDuplicada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ResolucionDuplicada(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
