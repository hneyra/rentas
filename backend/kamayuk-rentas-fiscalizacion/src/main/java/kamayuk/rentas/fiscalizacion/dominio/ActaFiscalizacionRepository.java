package kamayuk.rentas.fiscalizacion.dominio;

public interface ActaFiscalizacionRepository {

    ActaFiscalizacion insertar(ActaFiscalizacion acta);

    /**
     * Un acta por su identificador (#49).
     *
     * <p>Existe porque liquidar parte del acta: de ella salen el contribuyente, la unidad
     * fiscalizada, el area medida en campo y el hallazgo. Vacio si no existe o es de otra
     * municipalidad —lo segundo lo decide la politica RLS, no un {@code WHERE}—.
     */
    java.util.Optional<ActaFiscalizacion> findById(long id);

    /**
     * La grilla de actas, paginada (#599).
     *
     * <p>El total del sobre cuenta <b>todas</b> las actas que el criterio deja pasar y no las de la
     * pagina: la etapa «Inspeccionados» del embudo del programa se llena con ese numero, y contarlo
     * sobre la pagina daria «20 inspeccionados» en todo programa que pase de veinte actas. Es el
     * defecto que #25 midio en el resumen de la consulta unificada y #545 en la deteccion de
     * omisos.
     */
    kamayuk.rentas.compartido.Pagina<ActaFiscalizacion> consultar(
            CriterioDeActas criterio, kamayuk.rentas.compartido.Paginacion paginacion);

    /**
     * La próxima versión de un acta para esta <b>unidad</b> dentro de este programa: 1 si nunca se
     * la visitó, o la mayor existente más uno. Es lo que permite refiscalizar sin borrar la visita
     * anterior ({@code acta_fisc_version_uq}).
     *
     * <p><b>La unidad va en la llave desde {@code V60}</b>, y no es un detalle: llaveada sólo por
     * contribuyente, la primera acta de su segundo predio nacería en versión 2 y el papel diría que
     * es una reinspección que nunca ocurrió.
     */
    int siguienteVersion(
            long programaId,
            long contribuyenteId,
            @org.jspecify.annotations.Nullable Long predioId,
            @org.jspecify.annotations.Nullable Long vehiculoId);

    /**
     * Cuáles de esos predios ya tienen acta viva en ese programa (#481).
     *
     * <p>Es de donde la grilla de la muestra deriva su columna «Estado»: guardarlo en la fila
     * dejaría dos verdades sobre lo mismo, y la que se lee en pantalla sería la que nadie
     * recalculó.
     */
    java.util.Set<Long> prediosConActaEnElPrograma(long programaId, java.util.Set<Long> predios);

    /**
     * Cuáles de esos predios ya se fiscalizaron dentro del ejercicio, por la fecha de la visita
     * (#481): la segunda mitad de la exclusión. Un acta anulada no cuenta, porque anularla es
     * justamente decir que esa visita no vale.
     */
    java.util.Set<Long> prediosConActaEnElEjercicio(
            kamayuk.rentas.dominio.Ejercicio ejercicio, java.util.Set<Long> predios);
}
