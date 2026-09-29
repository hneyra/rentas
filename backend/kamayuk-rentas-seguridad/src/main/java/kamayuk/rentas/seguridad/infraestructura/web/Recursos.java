package kamayuk.rentas.seguridad.infraestructura.web;

import kamayuk.rentas.seguridad.dominio.Acceso;
import kamayuk.rentas.seguridad.dominio.Modulo;

/**
 * Los DTO de seguridad que quedan tras la etapa 4 de ADR-0039: el modulo y el acceso, que son lo
 * que la interfaz lee para componer su arbol. Los de grupos, usuarios, miembros y permisos se
 * fueron con su administracion a {@code identidad}.
 *
 * <p>Campos en español {@code camelCase} (ARQ-04 §3). Ninguno lleva {@code municipalidadId}.
 */
public final class Recursos {

    private Recursos() {}

    /**
     * Un modulo del catalogo, como lo publica {@code GET /seguridad/modulos}. {@code orden} es el
     * del menu; un modulo retirado sale con {@code activo} falso, no desaparece.
     */
    public record ModuloResource(long id, String codigo, String nombre, int orden, boolean activo) {
        public static ModuloResource de(Modulo modulo) {
            return new ModuloResource(
                    modulo.id() == null ? 0L : modulo.id(),
                    modulo.codigo(),
                    modulo.nombre(),
                    modulo.orden(),
                    modulo.activo());
        }
    }

    /**
     * Un acceso del catalogo, como lo publica {@code GET /seguridad/accesos}: {@code codigo} es el
     * que un controlador declara en {@code @RequiereAcceso}, y el que la interfaz cruza con la
     * matriz de la sesion para saber que ofrecer.
     */
    public record AccesoResource(
            long id, long moduloId, String tipo, String codigo, String nombre, boolean activo) {
        public static AccesoResource de(Acceso acceso) {
            return new AccesoResource(
                    acceso.id() == null ? 0L : acceso.id(),
                    acceso.moduloId(),
                    acceso.tipo().name(),
                    acceso.codigo(),
                    acceso.nombre(),
                    acceso.activo());
        }
    }
}
