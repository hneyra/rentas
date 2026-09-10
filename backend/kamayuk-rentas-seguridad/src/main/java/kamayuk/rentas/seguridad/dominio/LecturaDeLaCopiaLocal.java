package kamayuk.rentas.seguridad.dominio;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Lo que este sistema LEE de la copia local de la autorizacion, y nada mas (etapa 4 de ADR-0039).
 *
 * <p>Hasta la etapa 4 aqui habia dos repositorios que ademas escribian —altas, bajas, afiliaciones
 * y matrices de permisos—. Desde ADR-0039 quien escribe la copia es el consumidor del buzon de
 * {@code identidad}; lo que queda de este lado es lo que la sesion y el arbol de {@code rentas-web}
 * necesitan leer: los modulos, los accesos, la fila del usuario del token y su matriz efectiva.
 *
 * <p>No tiene ningun metodo de escritura a proposito: es la forma en que este contexto dice que ya
 * no administra a nadie.
 */
public interface LecturaDeLaCopiaLocal {

    Pagina<Modulo> modulos(Paginacion paginacion);

    Pagina<Acceso> accesos(Paginacion paginacion);

    Optional<Usuario> usuario(long id);

    Optional<Usuario> usuarioPorCuenta(String cuenta);

    /**
     * Cuantas cuentas tiene la copia local de esta municipalidad.
     *
     * <p>Existe para UNA pregunta, y la hace la implantacion (ADR-0039, etapa 5): despues de la
     * pasada del buzon, <b>cero</b> significa que nadie puede entrar. Se cuenta y no se busca una
     * cuenta concreta a proposito: desde la etapa 5 quien existe lo decide {@code identidad}, y
     * este sistema no tiene ninguna opinion sobre como se llama la primera cuenta.
     */
    long usuariosEnLaCopia();

    /**
     * La matriz efectiva de una cuenta a una fecha: por acceso, la union de sus grupos, y la
     * excepcion del usuario sustituyendo al grupo donde la haya (ADR-0013).
     */
    Map<String, Set<Privilegio>> permisosEfectivosDe(String cuenta, LocalDate fecha);
}
