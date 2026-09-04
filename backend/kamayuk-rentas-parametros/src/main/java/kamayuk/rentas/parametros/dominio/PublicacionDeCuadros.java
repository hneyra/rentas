package kamayuk.rentas.parametros.dominio;

import java.util.Optional;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Puerto de publicacion de los <b>cuadros normativos nacionales</b>: las tablas que no caben en una
 * fila de {@code parametro_tributario} porque tienen miles (D-13, ADR-0017).
 *
 * <h2>Que es una edicion</h2>
 *
 * <p>Una edicion es la resolucion entera —«la Tabla de Valores Referenciales del ejercicio 2026»—,
 * y se representa como <b>una fila de {@code parametro_tributario}</b>: su tipo, su clave, su
 * documento fuente y las dos firmas de ADR-0007. Las miles de filas del cuadro cuelgan de ella por
 * {@code publicacion_id}.
 *
 * <p>Eso es lo que permite que un conjunto municipal la congele <b>sin ningun mecanismo nuevo</b>:
 * componer la edicion en un conjunto es la misma fila de {@code conjunto_parametro_detalle} con la
 * que ya se compone la UIT, y V9 la vuelve inmutable en cuanto el conjunto se sella.
 *
 * <h2>Por que hay un {@code cerrar}</h2>
 *
 * <p>Porque componer congela <b>que</b> edicion se uso, no <b>cuantas filas</b> tenia. Sin cerrar,
 * una edicion ya sellada en el conjunto de una municipalidad podria recibir filas nuevas y el
 * recalculo de 2037 leeria un cuadro mas grande que el que se emitio, sin ningun error de por
 * medio. {@code cerrar} marca {@code parametro_tributario.sellado}, y el disparador de V55 rechaza
 * desde entonces cualquier fila mas.
 *
 * <p>Corregir una edicion cerrada no es editarla: es publicar otra, con su documento fuente y sus
 * dos firmas, y componerla en un conjunto nuevo (ADR-0007).
 *
 * <h2>Quien lo implementa</h2>
 *
 * <p>Solo el perfil {@code batch}, por lo mismo que {@link PublicacionDeParametros}: la unica
 * credencial que puede escribir estas tres tablas es {@code rol_carga_parametros} (V55), y esa
 * credencial la lleva un Job de un solo uso, no el proceso que atiende peticiones.
 */
public interface PublicacionDeCuadros {

    /** La edicion ya publicada con esa llave, si la hay, con su estado. */
    Optional<Edicion> edicionPublicada(LlaveDeParametro llave);

    /**
     * Abre una edicion: escribe su cabecera en {@code parametro_tributario} y devuelve su
     * identificador. Las dos firmas son las del corpus, y la base exige que sean distintas ({@code
     * parametro_doble_verificacion_ck}).
     */
    long abrirEdicion(ParametroTributario cabecera, String transcribio, String verifico);

    /**
     * Una fila del cuadro de depreciacion del Anexo I del Reglamento Nacional de Tasaciones.
     *
     * @param uso la tabla del Anexo I —{@code 01}..{@code 04}—, con el numero de la propia norma
     * @param antiguedadHasta el tope del tramo en anios; <b>nulo</b> es «mas de 50 anios», el tramo
     *     abierto con que cierra cada tabla. Un centinela seria una cifra inventada dentro de un
     *     cuadro normativo, y ademas una que se lee igual que un tope de verdad (V57)
     */
    void agregarDepreciacion(
            long edicion,
            String uso,
            String material,
            String estadoConservacion,
            @Nullable Integer antiguedadHasta,
            Alicuota porcentaje,
            String documentoFuente);

    /** Una fila del cuadro de valores referenciales de vehiculos. */
    void agregarValorReferencial(
            long edicion,
            int ejercicio,
            String categoria,
            String marca,
            String modelo,
            int anioFabricacion,
            Dinero valor,
            String documentoFuente);

    /** Cierra la edicion: desde aqui no admite una fila mas. */
    void cerrar(long edicion);

    /**
     * Una edicion publicada.
     *
     * @param id su identificador, que es el del {@code parametro_tributario} que la encabeza
     * @param cerrada si ya no admite filas
     */
    record Edicion(long id, boolean cerrada) {}
}
