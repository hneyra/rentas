package kamayuk.rentas.tesoreria.infraestructura;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.AplicacionDeRecibos;
import kamayuk.rentas.tesoreria.ReciboYaAplicado;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link AplicacionDeRecibos} contra {@code recibo_aplicado} (V28, #383).
 *
 * <p><b>Lee, decide e inserta, y la carrera la decide el indice.</b> Lee lo ya aplicado de ese
 * recibo y ese concepto —la suma de unidades y el ultimo {@code orden}—, rechaza si no alcanza, e
 * inserta con {@code orden = max + 1}. Dos transacciones que gastan a la vez el ultimo saldo leen
 * el mismo maximo e insertan el mismo {@code orden}: la segunda espera a la primera en {@code
 * recibo_aplicado_uq} y, cuando la primera confirma, choca y sale {@link ReciboYaAplicado}. Con
 * solo la lectura, las dos pasarian — que es exactamente lo que pasaba sin tabla.
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE} ni un {@code DELETE}: V28 le concede a
 * {@code kamayuk_app} {@code SELECT} e {@code INSERT}, y el escaner de fuentes vigila las dos
 * cadenas.
 *
 * <p>La municipalidad no pasa por Java (regla 2): la pone el motor con {@link
 * RepositorioJdbc#MUNICIPALIDAD_ACTUAL}, y la lectura la filtra la politica RLS.
 */
@Repository
public class AplicacionDeRecibosJdbc extends RepositorioJdbc implements AplicacionDeRecibos {

    private final Clock reloj;

    public AplicacionDeRecibosJdbc(JdbcClient jdbc, Clock reloj) {
        super(jdbc);
        this.reloj = Objects.requireNonNull(reloj, "La constancia dice cuando se aplico");
    }

    @Override
    public void aplicar(
            String numeroDeRecibo,
            String concepto,
            int unidadesQueConsume,
            int unidadesCobradas,
            Acto acto) {
        Objects.requireNonNull(numeroDeRecibo, "Se aplica un recibo con su numero");
        Objects.requireNonNull(concepto, "Se aplica un recibo por un concepto");
        Objects.requireNonNull(acto, "Se aplica un recibo a un acto");

        Aplicado yaAplicado =
                jdbc().sql(
                                "SELECT coalesce(sum(unidades), 0) AS unidades,"
                                        + " coalesce(max(orden), 0) AS orden"
                                        + " FROM recibo_aplicado"
                                        + " WHERE numero_recibo = :numero AND concepto = :concepto")
                        .param("numero", numeroDeRecibo)
                        .param("concepto", concepto)
                        .query(
                                (fila, n) ->
                                        new Aplicado(fila.getInt("unidades"), fila.getInt("orden")))
                        .single();

        ReciboYaAplicado.exigirSaldo(
                numeroDeRecibo,
                concepto,
                yaAplicado.unidades(),
                unidadesQueConsume,
                unidadesCobradas);

        try {
            jdbc().sql(
                            "INSERT INTO recibo_aplicado"
                                    + " (municipalidad_id, numero_recibo, concepto, orden,"
                                    + "  unidades, tabla, acto_id, usuario_registro,"
                                    + "  fecha_registro)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :numero, :concepto, :orden, :unidades, :tabla, :acto,"
                                    + "  :usuario, :registrado)")
                    .param("numero", numeroDeRecibo)
                    .param("concepto", concepto)
                    .param("orden", yaAplicado.orden() + 1)
                    .param("unidades", unidadesQueConsume)
                    .param("tabla", acto.tabla())
                    .param("acto", acto.id())
                    .param("usuario", UsuarioDeLaSesion.actual())
                    .param("registrado", Timestamp.from(reloj.instant()))
                    .update();
        } catch (DuplicateKeyException otraLoGasto) {
            throw ReciboYaAplicado.porLaCarrera(numeroDeRecibo, concepto, otraLoGasto);
        }
    }

    /** Lo que ya se gasto de un recibo y un concepto: las unidades y el ultimo orden. */
    private record Aplicado(int unidades, int orden) {}
}
