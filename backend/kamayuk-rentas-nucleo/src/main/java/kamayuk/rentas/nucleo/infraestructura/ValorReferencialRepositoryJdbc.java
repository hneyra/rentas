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
import kamayuk.rentas.nucleo.dominio.ValorReferencialRepository.ValorReferencialAmbiguo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
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
 * municipalidades. Desde P5B vive en {@code normativa}, y lo que estas consultas leen es {@code
 * normativa_valor_referencial} —la copia local del conjunto SELLADO, descargada una vez y
 * verificada por su sha256 (ADR-0025 §1)—. <b>La firma de los metodos no cambio con P5B</b>, y eso
 * es lo importante: quien lee sigue teniendo que decir de que conjunto habla.
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
     * El valor referencial de un vehiculo, si el cuadro sellado lo trae: el contrato esta en el
     * puerto.
     *
     * <p><b>Acota por la categoria cuando el vehiculo la tiene</b> (#360). Hasta entonces la
     * consulta filtraba por conjunto, marca, modelo y ano, y el javadoc decia que acotar por {@code
     * vehiculo.categoria} «espera a D-02a»; D-02a se cerro el 2026-08-25 y la premisa vencio. Sin
     * el filtro, un CHEVROLET SPARK registrado en A1 daba dos candidatos —A1 y A2— y la operacion
     * contestaba 500, aunque el padron dijera cual era.
     *
     * <p>Lo que queda sin resolver —un vehiculo sin categoria cuyo modelo el anexo publica en
     * varias con cifras distintas— se para con {@link ValorReferencialAmbiguo}, que nombra las
     * categorias. Si todas traen la misma cifra, se devuelve esa: la base no depende de cual sea.
     */
    @Override
    public Optional<ValorReferencial> buscar(
            IdentificadorDeConjunto conjunto,
            String marca,
            String modelo,
            int anioFabricacion,
            @Nullable String categoria) {
        List<Candidato> candidatos =
                candidatos(conjunto, marca, modelo, anioFabricacion, categoria);
        if (candidatos.isEmpty()) {
            return Optional.empty();
        }
        ValorReferencial primero = candidatos.getFirst().valor();
        boolean unaSolaCifra =
                candidatos.stream()
                        .allMatch(candidato -> candidato.valor().valor().equals(primero.valor()));
        if (!unaSolaCifra) {
            throw new ValorReferencialAmbiguo(
                    marca,
                    modelo,
                    anioFabricacion,
                    categoria,
                    candidatos.stream().map(Candidato::categoria).distinct().toList());
        }
        return Optional.of(primero);
    }

    /**
     * El cuadro publica un mismo modelo en varias categorias: sin la del vehiculo pueden salir
     * varios. El orden por categoria no elige nada —solo se devuelve el primero cuando todos valen
     * lo mismo— pero hace que el mensaje de la ambiguedad salga siempre igual.
     */
    private List<Candidato> candidatos(
            IdentificadorDeConjunto conjunto,
            String marca,
            String modelo,
            int anioFabricacion,
            @Nullable String categoria) {
        return jdbc().sql(
                        """
                        SELECT v.ejercicio, v.categoria, v.marca, v.modelo, v.anio_fabricacion,
                               v.valor, v.documento_fuente
                          FROM normativa_valor_referencial v
                         WHERE v.conjunto_id = :conjunto
                           AND v.marca = :marca
                           AND v.modelo = :modelo
                           AND v.anio_fabricacion = :anio
                           AND (CAST(:categoria AS varchar) IS NULL OR v.categoria = :categoria)
                         ORDER BY v.categoria
                        """)
                .param("conjunto", conjunto.valor())
                .param("marca", marca)
                .param("modelo", modelo)
                .param("anio", anioFabricacion)
                .param("categoria", categoria)
                .query(
                        (ResultSet fila, int numero) ->
                                new Candidato(fila.getString("categoria"), mapear(fila, numero)))
                .list();
    }

    /** Una fila del cuadro con la categoria con que el anexo la publica. */
    private record Candidato(String categoria, ValorReferencial valor) {}

    @Override
    public List<String> categorias(IdentificadorDeConjunto conjunto) {
        return jdbc().sql(
                        """
                        SELECT DISTINCT v.categoria
                          FROM normativa_valor_referencial v
                         WHERE v.conjunto_id = :conjunto
                         ORDER BY v.categoria
                        """)
                .param("conjunto", conjunto.valor())
                .query((ResultSet fila, int numero) -> fila.getString("categoria"))
                .list();
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
