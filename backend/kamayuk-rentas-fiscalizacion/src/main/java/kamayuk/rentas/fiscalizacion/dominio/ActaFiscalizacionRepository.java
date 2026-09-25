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
     * El acta, <b>bloqueando su fila</b> hasta el final de la transaccion (#339).
     *
     * <p>Es la lectura de los casos de uso que deciden sobre la visita —anularla, o liquidar y
     * reliquidar sobre ella—. Con {@link #findById} a secas, en READ COMMITTED, anular y liquidar
     * la misma acta a la vez leian las dos «ABIERTA, sin liquidacion» y confirmaban las dos: una
     * liquidacion viva sobre una visita anulada. Bloqueando la fila, la segunda espera a que la
     * primera confirme y lee lo que dejo.
     *
     * <p>Exige una transaccion abierta: fuera de ella el bloqueo se soltaria en la misma sentencia
     * y no ordenaria nada.
     */
    java.util.Optional<ActaFiscalizacion> findByIdParaActualizar(long id);

    /**
     * Anula el acta: la unica escritura que mueve su estado (#214).
     *
     * <p>Mueve <b>una columna</b> y ninguna mas, y no es una convencion: desde V19 {@code
     * kamayuk_app} no tiene UPDATE sobre la tabla sino sobre {@code estado}, asi que un {@code
     * UPDATE} que tocara el area medida o el hallazgo saldria con {@code 42501}. Lo que el
     * fiscalizador midio en campo no se corrige en la base: se levanta otra acta.
     *
     * <p>La transicion la decide el dominio ({@link ActaFiscalizacion#anulada}) <b>antes</b> de
     * escribir, como en {@code DeclaracionJuradaRepository#marcar}: si es ilegal no se escribe
     * nada.
     *
     * @return el acta ya anulada
     * @throws ActaFiscalizacion.TransicionIlegal si ya estaba anulada
     */
    ActaFiscalizacion anular(long id);

    /**
     * La grilla de actas de la municipalidad, paginada y <b>sin ningun filtro</b> (#599, #242).
     *
     * <p>El total del sobre cuenta <b>todas</b> las actas y no las de la pagina, que es lo que
     * cualquier relacion paginada de esta casa promete: contarlo sobre la pagina daria «20» en toda
     * municipalidad que pase de veinte actas. Es el defecto que #25 midio en el resumen de la
     * consulta unificada y #545 en la deteccion de omisos.
     *
     * <p><b>Sin criterio, y hasta #242 lo habia.</b> Era uno solo —el programa—, y estaba para
     * llenar la etapa «Inspeccionados» del embudo con este {@code totalElementos}. No servia para
     * eso: ese numero cuenta <b>actas</b> y el embudo cuenta <b>unidades</b>, asi que refiscalizar
     * un predio lo habria hecho superar a «programados». Lo cuenta bien {@link
     * #prediosConActaEnElPrograma}, por unidad, y el embudo lo publica entero (#196).
     */
    kamayuk.rentas.compartido.Pagina<ActaFiscalizacion> consultar(
            kamayuk.rentas.compartido.Paginacion paginacion);

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
     * Lo que consigna cada una de esas versiones de ficha: el <b>lado declarado</b> del contraste
     * que la pantalla del acta dibuja (#191).
     *
     * <p>Se pide por lote y no una a una porque quien la llama es un <b>listado</b>: una lectura
     * por página, igual que {@code DeteccionDeOmisos} resuelve los titulares de la suya. Una
     * versión que la proyección todavía no tenga simplemente no sale del mapa, y el acta se publica
     * con su lado declarado nulo — que es lo honesto, y distinto de cero.
     *
     * <p>Devuelve lo que consigna la <b>versión</b> y no «lo que el predio tiene hoy»: {@code
     * acta_fiscalizacion.ficha_id} es la versión que regía a la fecha de la visita, y comparar lo
     * hallado contra la ficha actual acusaría de subvaluación a quien declaró correctamente sobre
     * lo que entonces existía (RNF-075). Es el mismo criterio de {@code
     * LectorDeFichas#areaDeLaVersion}.
     *
     * <p>Con el conjunto vacío no hay consulta: un {@code IN ()} no es SQL válido.
     */
    java.util.Map<Long, ActaConLoDeclarado.LoDeclarado> loDeclaradoPorFicha(
            java.util.Set<Long> fichaIds);

    /**
     * Cuántas <b>unidades</b> del programa tienen acta viva: la tercera etapa del embudo (#196).
     *
     * <p>Unidades y no actas. Refiscalizar levanta una segunda acta —versión 2 sobre la misma
     * unidad, que es justamente lo que {@code acta_fisc_version_uq} permite— y contar filas haría
     * que esta etapa superara a «programados», o sea un embudo que se ensancha.
     *
     * <p>Un acta <b>anulada</b> no cuenta, por lo mismo que no cuenta en {@link
     * #prediosConActaEnElEjercicio}: anularla es decir que esa visita no vale.
     */
    int unidadesConActaViva(long programaId);

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
