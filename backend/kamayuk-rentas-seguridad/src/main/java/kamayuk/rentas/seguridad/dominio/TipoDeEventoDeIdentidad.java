package kamayuk.rentas.seguridad.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Los siete tipos de hecho que el buzon de {@code identidad} publica (etapa 4 de ADR-0039, ADR-0028
 * §3), tal como los declara su {@code TipoDeEventoDeIdentidad}.
 *
 * <p>Son los que esta copia local sabe aplicar. Un octavo que llegue <b>no se aplica a ciegas</b>:
 * el consumidor lo aparta con su nombre, lo acusa y avisa, porque un tipo que no esta aqui es un
 * cambio del contrato del buzon que alguien tiene que mirar —y no una capacidad que falte, como los
 * hechos del territorio de {@code catastro} (#54)—. La diferencia es que aqui el buzon es el de la
 * AUTORIZACION: un hecho que se ignora es alguien que puede hacer algo en {@code identidad} y no
 * puede aqui, y eso no espera.
 */
public enum TipoDeEventoDeIdentidad {
    USUARIO_DADO_DE_ALTA,
    USUARIO_MODIFICADO,
    GRUPO_DADO_DE_ALTA,
    GRUPO_MODIFICADO,
    MIEMBRO_AFILIADO,
    MIEMBRO_DESAFILIADO,
    PERMISO_FIJADO;

    /** El tipo que corresponde al nombre publicado, o {@code null} si esta copia no lo conoce. */
    public static @Nullable TipoDeEventoDeIdentidad declarado(@Nullable String nombre) {
        if (nombre == null) {
            return null;
        }
        for (TipoDeEventoDeIdentidad tipo : values()) {
            if (tipo.name().equals(nombre)) {
                return tipo;
            }
        }
        return null;
    }
}
