package kamayuk.rentas.coactiva.infraestructura.web;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDelProcesoCoactivo;

/**
 * El seguimiento del expediente coactivo, tal como lo dibuja {@code proceso_coactivo} (#41,
 * RF-101).
 *
 * <p>Es el expediente de #40 —con su estado derivado, sus valores, su historial y su deuda <b>con
 * la fecha a la que esta</b> (regla 9, RNF-075)— mas las actuaciones del procedimiento. La deuda no
 * se repite en ninguna actuacion: viaja una sola vez, en {@code expediente}, con su {@code
 * deudaAlDia}.
 *
 * @param expediente la cabecera, el estado, la deuda y el historial
 * @param actuaciones los actos dictados, cada uno con sus diligencias
 */
public record ProcesoResource(ExpedienteResource expediente, List<ActoResource> actuaciones) {

    public static ProcesoResource de(
            ConsultaDelProcesoCoactivo.ProcesoCoactivo proceso, String codContribuyente) {
        List<ActoResource> actos = new ArrayList<>();
        for (ConsultaDelProcesoCoactivo.Actuacion actuacion : proceso.actuaciones()) {
            actos.add(ActoResource.de(actuacion));
        }
        return new ProcesoResource(
                ExpedienteResource.de(proceso.ficha(), codContribuyente), List.copyOf(actos));
    }
}
