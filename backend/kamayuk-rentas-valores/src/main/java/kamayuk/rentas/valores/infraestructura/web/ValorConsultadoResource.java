package kamayuk.rentas.valores.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.valores.aplicacion.ConsultaDeValores.FilaDeValor;
import kamayuk.rentas.valores.dominio.ValorEnConsulta;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de {@code consulta_valores}, tal como sale por HTTP. Campos en español {@code camelCase}
 * (ARQ-04 §3).
 *
 * <h2>El importe lleva su fecha, y esa fecha no es hoy</h2>
 *
 * <p>{@code monto} viaja como {@link ImporteActualizado} —cifra y fecha juntas, regla 9— y la fecha
 * es {@code proyectadoA}: el dia al que estaban proyectados los importes <b>cuando se emitio el
 * valor</b>. No es un descuido que no sea la de hoy: el desglose de un valor esta congelado (AC de
 * #37), y actualizarlo al mirarlo convertiria un documento notificado en una cifra que cambia sola.
 *
 * <p>{@code situacionA} es otra cosa y va aparte: el dia desde el que se miro si el plazo ya
 * vencio. Un valor emitido en marzo y notificado en abril se ve «NOTIFICADO» el 10 de abril y
 * «EXIGIBLE» el 10 de mayo, con el mismo importe y la misma {@code proyectadoA}.
 *
 * <h2>Lo que no viaja</h2>
 *
 * <p>{@code tributo} y {@code periodo} pueden ser nulos: un valor sin detalle no deberia existir, y
 * si aparece uno se ve como lo que es en vez de disfrazarse de cadena vacia. {@code notificadoEl} y
 * {@code exigibleDesde} son nulos mientras ninguna diligencia haya surtido efecto — la pantalla
 * pinta un guion, que no es una fecha.
 */
public record ValorConsultadoResource(
        long id,
        String numero,
        String tipo,
        String codContribuyente,
        String contribuyente,
        @Nullable String tributo,
        @Nullable String periodo,
        ImporteActualizado monto,
        @Nullable String notificadoEl,
        @Nullable String exigibleDesde,
        String situacion,
        String estado,
        String situacionA,
        String fechaEmision) {

    public static ValorConsultadoResource de(FilaDeValor fila) {
        ValorEnConsulta enConsulta = fila.valor();
        var valor = enConsulta.valor();
        ResumenDeContribuyente contribuyente = fila.contribuyente();

        return new ValorConsultadoResource(
                java.util.Objects.requireNonNull(
                        valor.id(), "Un valor que sale por HTTP ya esta guardado"),
                valor.numero(),
                valor.tipo().codigo(),
                contribuyente == null ? "" : contribuyente.codigo(),
                contribuyente == null ? "" : contribuyente.nombre(),
                enConsulta.tributos(),
                enConsulta.periodo(),
                new ImporteActualizado(valor.total(), valor.proyectadoA()),
                texto(enConsulta.notificadoEl()),
                texto(enConsulta.exigibleDesde()),
                enConsulta.situacion().name(),
                valor.estado().name(),
                enConsulta.situacionA().toString(),
                valor.fechaEmision().toString());
    }

    private static @Nullable String texto(@Nullable LocalDate fecha) {
        return fecha == null ? null : fecha.toString();
    }
}
