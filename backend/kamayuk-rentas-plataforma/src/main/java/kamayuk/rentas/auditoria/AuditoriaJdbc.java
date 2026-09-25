package kamayuk.rentas.auditoria;

import java.time.Clock;
import java.time.OffsetDateTime;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Escribe en {@code auditoria}. Solo inserta.
 *
 * <p>No hay ningun metodo que actualice ni que borre, y no es una omision: la aplicacion tiene
 * sobre esta tabla <b>solo {@code SELECT} e {@code INSERT}</b> (V7), porque quien puede modificar
 * la auditoria puede borrar su propio rastro. Si alguien escribiera aqui un {@code UPDATE}, lo
 * cazaria antes el escaner de fuentes —{@code auditoria} esta en su lista de tablas inmutables— y,
 * si llegara a ejecutarse, el motor lo rechazaria por privilegios.
 *
 * <p>El {@code municipalidad_id} no aparece en ninguna firma: lo pone el motor, como en todo
 * repositorio (ver {@link RepositorioJdbc#MUNICIPALIDAD_ACTUAL}). La auditoria es dato de tenant
 * como cualquier otro, con su RLS (ADR-0008).
 *
 * <p><b>La fecha y el ejercicio de la particion salen del MISMO instante del reloj inyectado</b>
 * (#398). Es una unica fuente de verdad, y no por estetica: si la fecha dice 2027 y el ejercicio
 * 2026, la fila cae en una particion cuya fecha no coincide con la suya, y la bitacora —que filtra
 * por ejercicio <b>y</b> por rango de fecha— no la encuentra. Hasta #398 el ejercicio lo traia el
 * {@link RegistroDeAuditoria}, y cada llamador lo deducia de la fecha que tuviera a mano: la de
 * presentacion de la DJ, la de la transferencia, la de la infraccion. Una transferencia de
 * diciembre registrada en enero iba a una particion que no existe —500, y el acto deshecho—, y una
 * DJ de 2026 anulada en 2027 quedaba archivada en 2026 con fecha de 2027. La regla de la casa es
 * «la particion de la bitacora es el ejercicio del ACTO», y el acto es ahora.
 *
 * <p>El reloj esta en la zona del producto desde #316, asi que el año es el de la municipalidad y
 * no el de UTC: una fila escrita a las 23:30 del 31 de diciembre en Lima es del ejercicio que
 * termina. Tampoco sale de {@code now()} de la base, que es otro reloj; que la columna tuviera
 * {@code DEFAULT now()} lo hacia facil de no ver.
 */
@Component
public class AuditoriaJdbc extends RepositorioJdbc implements Auditoria {

    private final Clock reloj;

    public AuditoriaJdbc(JdbcClient jdbc, Clock reloj) {
        super(jdbc);
        this.reloj = java.util.Objects.requireNonNull(reloj, "La auditoria necesita su reloj");
    }

    @Override
    public void registrar(RegistroDeAuditoria registro) {
        Origen origen = OrigenContext.actual();
        OffsetDateTime ahora = OffsetDateTime.now(reloj);

        jdbc().sql(
                        "INSERT INTO auditoria"
                                + " (municipalidad_id, ejercicio, fecha, tabla, clave, operacion,"
                                + "  usuario_id, origen_equipo, origen_ip, observacion,"
                                + "  datos_anteriores, datos_nuevos)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :ejercicio, :fecha, :tabla, :clave, :operacion,"
                                + " :usuario, :equipo, cast(:ip AS inet), :observacion,"
                                + " cast(:datosAnteriores AS jsonb), cast(:datosNuevos AS jsonb))")
                .param("fecha", ahora)
                .param("ejercicio", Ejercicio.de(ahora.toLocalDate()).valor())
                .param("tabla", registro.tabla())
                .param("clave", registro.clave())
                .param("operacion", registro.operacion().name())
                .param("usuario", origen.usuario())
                .param("equipo", origen.equipo())
                .param("ip", origen.ip())
                .param("observacion", registro.observacion().texto())
                .param("datosAnteriores", registro.datosAnteriores())
                .param("datosNuevos", registro.datosNuevos())
                .update();
    }
}
