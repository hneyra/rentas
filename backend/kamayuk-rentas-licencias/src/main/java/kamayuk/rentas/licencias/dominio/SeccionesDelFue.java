package kamayuk.rentas.licencias.dominio;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Las cinco secciones de un FUE, y cuales le faltan para poder emitir: la regla, una sola vez
 * (#452).
 *
 * <p>Hasta #452 estaba escrita dos veces. La ficha daba por buena una seccion con que no estuviera
 * vacia; la emision exigia los dos profesionales que firman y un documento presentado. Con un
 * proyectista de estructuras solo y un plano sin presentar, la ficha decia {@code completo: true} y
 * la emision contestaba que faltaban dos secciones: la ventanilla mandaba al administrado a un
 * rechazo que la propia pantalla le habia dicho que no iba a pasar. Ahora las dos leen {@link
 * #faltantes()}.
 *
 * <p>Se leen las cinco antes de comprobar ninguna, a proposito: comprobar sobre la marcha dejaria
 * el error diciendo solo la primera que falta.
 *
 * @param terrenoOpcional la version vigente del terreno, si la hay
 * @param proyectoOpcional la del proyecto
 * @param estructuras las estructuras declaradas: son la seccion de valorizacion
 * @param profesionales los profesionales declarados
 * @param requisitos los documentos adjuntos, presentados o no
 */
public record SeccionesDelFue(
        Optional<TerrenoDelFue> terrenoOpcional,
        Optional<ProyectoDelFue> proyectoOpcional,
        List<EstructuraDelProyecto> estructuras,
        List<ProfesionalDelFue> profesionales,
        List<RequisitoDelFue> requisitos) {

    public SeccionesDelFue {
        Objects.requireNonNull(terrenoOpcional, "El terreno es un Optional, no null");
        Objects.requireNonNull(proyectoOpcional, "El proyecto es un Optional, no null");
        estructuras = List.copyOf(estructuras);
        profesionales = List.copyOf(profesionales);
        requisitos = List.copyOf(requisitos);
    }

    /** Las secciones que hoy impiden emitir, en el orden en que el FUE las presenta (AC 1). */
    public List<SeccionDelFue> faltantes() {
        List<SeccionDelFue> faltan = new ArrayList<>();
        if (terrenoOpcional.isEmpty()) {
            faltan.add(SeccionDelFue.TERRENO);
        }
        if (proyectoOpcional.isEmpty()) {
            faltan.add(SeccionDelFue.PROYECTO);
        }
        if (estructuras.isEmpty()) {
            faltan.add(SeccionDelFue.VALORIZACION);
        }
        if (!tieneLosProfesionalesQueFirman()) {
            faltan.add(SeccionDelFue.PROFESIONALES);
        }
        if (requisitos.stream().noneMatch(RequisitoDelFue::presentado)) {
            faltan.add(SeccionDelFue.DOCUMENTOS);
        }
        return List.copyOf(faltan);
    }

    /** El terreno, cuando ya se sabe que no falta: lo pide quien dibuja la licencia. */
    public TerrenoDelFue terreno() {
        return terrenoOpcional.orElseThrow();
    }

    /** El proyecto, cuando ya se sabe que no falta. */
    public ProyectoDelFue proyecto() {
        return proyectoOpcional.orElseThrow();
    }

    /**
     * Que esten el proyectista de arquitectura y el responsable de obra.
     *
     * <p>Son los dos que responden por la obra: sin proyectista no hay quien responda por el
     * proyecto, y sin responsable de obra no hay a quien reclamar durante la ejecucion. Los otros
     * dos proyectistas —estructuras e instalaciones— no se exigen aqui: cuando hacen falta lo dice
     * el reglamento segun la modalidad, y eso son cifras y supuestos que este repositorio no tiene
     * verificados.
     */
    private boolean tieneLosProfesionalesQueFirman() {
        Set<TipoDeProfesional> presentes = EnumSet.noneOf(TipoDeProfesional.class);
        for (ProfesionalDelFue profesional : profesionales) {
            presentes.add(profesional.tipo());
        }
        return presentes.contains(TipoDeProfesional.PROYECTISTA_ARQUITECTURA)
                && presentes.contains(TipoDeProfesional.RESPONSABLE_OBRA);
    }
}
