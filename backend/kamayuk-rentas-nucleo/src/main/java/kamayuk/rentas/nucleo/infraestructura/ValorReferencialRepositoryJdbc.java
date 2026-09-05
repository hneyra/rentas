package kamayuk.rentas.nucleo.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.MarcaYModelo;
import kamayuk.rentas.nucleo.dominio.ValorReferencial;
import kamayuk.rentas.nucleo.dominio.ValorReferencialRepository;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los valores referenciales, leidos siempre por conjunto.
 *
 * <p>No hay ninguna consulta que acepte solo el ejercicio, y no es una omision: es lo que impide
 * que alguien la escriba «para el caso simple» y acabe leyendo una version sellada distinta de la
 * que uso la determinacion.
 *
 * <p>La tabla es nacional (D-13, ADR-0017): la aprueba el MEF y se carga una vez para todas las
 * municipalidades. Desde P5B vive en {@code normativa}, y lo que estas dos consultas leen es {@code
 * normativa_valor_referencial} —la copia local del conjunto SELLADO, descargada una vez y
 * verificada por su sha256 (ADR-0025 §1)—. <b>La firma de los metodos no cambia</b>, y eso es lo
 * importante: quien lee sigue teniendo que decir de que conjunto habla.
 *
 * <p>Desaparece el {@code JOIN} con {@code conjunto_parametro_detalle}, que servia para ver solo la
 * edicion que el conjunto compuso: la copia local YA ES esa edicion. Y el aislamiento se mantiene
 * por el mismo mecanismo de antes —la copia es tabla de tenant con su politica RLS—, de modo que
 * preguntar por el conjunto de otra municipalidad sigue sin devolver nada.
 */
@Repository
public class ValorReferencialRepositoryJdbc extends RepositorioJdbc
        implements ValorReferencialRepository {

    public ValorReferencialRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    /**
     * El valor referencial de un vehiculo, si el cuadro sellado lo trae.
     *
     * <p><b>Puede fallar en vez de devolver uno de dos.</b> El anexo del MEF publica «OTROS
     * MODELOS» dentro de cada categoria —A1, CAMIONES, CAMIONETAS…— con un valor distinto en cada
     * una, asi que {@code (marca, modelo, ano)} no siempre identifica una sola fila. Cuando no la
     * identifica, esto lanza en vez de quedarse con la primera: un camion valorizado con la cifra
     * de una camioneta no produce ningun error, produce otra base imponible (ARQ-09 §2.5).
     *
     * <p>Resolverlo es acotar por {@code vehiculo.categoria}, que V2 ya tiene y hoy es nulable; esa
     * es la regla vehicular y espera a D-02a. Hasta entonces, esta consulta se para y dice cual es
     * la ambiguedad.
     */
    @Override
    public Optional<ValorReferencial> buscar(
            IdentificadorDeConjunto conjunto, String marca, String modelo, int anioFabricacion) {
        List<ValorReferencial> candidatos = candidatos(conjunto, marca, modelo, anioFabricacion);
        if (candidatos.size() > 1) {
            throw new ValorReferencialAmbiguo(marca, modelo, anioFabricacion, candidatos.size());
        }
        return candidatos.stream().findFirst();
    }

    /** El cuadro publica «OTROS MODELOS» por categoria: aqui pueden salir varios. */
    private List<ValorReferencial> candidatos(
            IdentificadorDeConjunto conjunto, String marca, String modelo, int anioFabricacion) {
        return jdbc().sql(
                        """
                        SELECT v.ejercicio, v.marca, v.modelo, v.anio_fabricacion, v.valor,
                               v.documento_fuente
                          FROM normativa_valor_referencial v
                         WHERE v.conjunto_id = :conjunto
                           AND v.marca = :marca
                           AND v.modelo = :modelo
                           AND v.anio_fabricacion = :anio
                        """)
                .param("conjunto", conjunto.valor())
                .param("marca", marca)
                .param("modelo", modelo)
                .param("anio", anioFabricacion)
                .query(ValorReferencialRepositoryJdbc::mapear)
                .list();
    }

    /**
     * El cuadro trae mas de una fila para ese vehiculo y falta la categoria para elegir.
     *
     * <p>No es un fallo del dato: es como la norma lo publica. Lo que falta es el otro lado, la
     * categoria del vehiculo del padron.
     */
    public static final class ValorReferencialAmbiguo extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ValorReferencialAmbiguo(
                String marca, String modelo, int anioFabricacion, int candidatos) {
            super(
                    "El cuadro sellado trae "
                            + candidatos
                            + " valores para "
                            + marca
                            + " "
                            + modelo
                            + " del "
                            + anioFabricacion
                            + ", uno por categoria del anexo. Elegir uno sin saber la categoria del"
                            + " vehiculo daria otra base imponible sin ningun error de por medio"
                            + " (ARQ-09 §2.5)");
        }
    }

    @Override
    public List<MarcaYModelo> catalogo(IdentificadorDeConjunto conjunto) {
        return jdbc().sql(
                        """
                        SELECT DISTINCT v.marca, v.modelo
                          FROM normativa_valor_referencial v
                         WHERE v.conjunto_id = :conjunto
                         ORDER BY v.marca, v.modelo
                        """)
                .param("conjunto", conjunto.valor())
                .query(
                        (ResultSet fila, int numero) ->
                                new MarcaYModelo(fila.getString("marca"), fila.getString("modelo")))
                .list();
    }

    private static ValorReferencial mapear(ResultSet fila, int numero) throws SQLException {
        return new ValorReferencial(
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getString("marca"),
                fila.getString("modelo"),
                new Ejercicio(fila.getInt("anio_fabricacion")),
                new Dinero(fila.getBigDecimal("valor")),
                fila.getString("documento_fuente"));
    }
}
