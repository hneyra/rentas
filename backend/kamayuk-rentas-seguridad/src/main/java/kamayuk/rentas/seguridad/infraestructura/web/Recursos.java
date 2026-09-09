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
