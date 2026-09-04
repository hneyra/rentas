package kamayuk.rentas.auditoria;

import java.time.Clock;
import java.time.OffsetDateTime;
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
 * <p><b>La fecha sale del reloj inyectado, no de {@code now()} de la base.</b> Tiene que ser el
 * mismo reloj que decidio el ejercicio de la particion: si uno dice 2026 y el otro 2027 —y en
 * Nochevieja pasa—, la fila cae en una particion cuya fecha no coincide con la suya, y la consulta
 * de auditoria por rango no la encuentra. Que la columna tuviera {@code DEFAULT now()} lo hacia
 * facil de no ver.
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
                .param("fecha", OffsetDateTime.now(reloj))
                .param("ejercicio", registro.ejercicio().valor())
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
