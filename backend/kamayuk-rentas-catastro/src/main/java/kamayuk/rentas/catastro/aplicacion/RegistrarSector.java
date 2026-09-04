package kamayuk.rentas.catastro.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Da de alta un sector del catastro, lo edita o lo da de baja.
 *
 * <p>Copia tal cual el patron de {@link RegistrarVia}: la {@link Observacion} en la firma, la
 * auditoria en la misma transaccion, y el reloj inyectado. Nacio para la carga inicial de catalogos
 * territoriales (#121), un alta por fila del archivo importado; con #290 gana el lado que la
 * pantalla de mantenimiento necesita.
 *
 * <h2>Dos metodos y no uno, porque son tres operaciones de auditoria</h2>
 *
 * <p>{@link #registrar} es el alta y solo el alta: asienta {@link Operacion#ALTA}. {@link #editar}
 * recibe <b>el estado anterior</b> ademas del nuevo, y de la comparacion salen las otras dos: si el
 * sector estaba activo y deja de estarlo, es una {@link Operacion#BAJA}; en cualquier otro caso
 * —reactivarlo incluido—, una {@link Operacion#MODIFICACION}.
 *
 * <p>Antes habia un solo metodo que elegia la operacion mirando si el sector traia identificador, y
 * asentaba {@code MODIFICACION} <b>sin datos anteriores</b>. Eso es justo lo que el contrato de
 * {@code MODIFICACION} prohibe —«los datos anteriores quedan en la propia auditoria»—: una fila que
 * dice que algo cambio pero no desde que no permite reconstruir el valor previo, ni para deshacer
 * un error ni para sostener una reclamacion. Y ninguna baja se asentaba nunca como {@code BAJA}.
 *
 * <p>El estado anterior entra en la firma en lugar de releerse aqui: el que llama ya lo tiene, y
 * volver a leerlo abriria la ventana entre la lectura y la escritura.
 *
 * <p>Ningun argumento es el identificador de municipalidad (regla 2): sale del token y lo aplica la
 * politica RLS.
 */
@Service
public class RegistrarSector {

    private final CatastroRepository repositorio;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarSector(CatastroRepository repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Alta de un sector que todavia no esta en la base.
     *
     * <p>Rechaza un sector que ya tiene identificador en lugar de tratarlo como edicion: un sector
     * con id llega de la base, y guardarlo por aqui asentaria un {@code ALTA} sobre algo que ya
     * existia y perderia el estado anterior. Para eso esta {@link #editar}.
     */
    @Transactional
    public Sector registrar(Sector sector, Observacion observacion) {
        if (!sector.esNuevo()) {
            throw new IllegalArgumentException(
                    "registrar da de alta un sector nuevo; el que llego ya tiene identificador."
                            + " Para cambiar un sector existente esta editar(anterior, cambiado, …)");
        }
        Sector guardado = repositorio.guardar(sector);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "sector",
                                String.valueOf(guardado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardado)));

        return guardado;
    }

    /**
     * Edicion de un sector existente, o su baja logica.
     *
     * <p>{@code anterior} es el sector tal como esta en la base y {@code cambiado} el que se quiere
     * dejar; los dos han de ser la misma fila. La operacion que se asienta la decide el paso de
     * {@code activo} de {@code true} a {@code false}: eso es una {@link Operacion#BAJA}, y no un
     * borrado (RNF-051) —la fila sigue ahi con {@code activo = false}, y tiene que seguir: su
     * codigo esta dentro del codigo de referencia catastral de los predios ya emitidos—. Reactivar
     * un sector dado de baja es una {@link Operacion#MODIFICACION} como cualquier otra.
     */
    @Transactional
    public Sector editar(Sector anterior, Sector cambiado, Observacion observacion) {
        Long idAnterior = anterior.id();
        Long idCambiado = cambiado.id();
        if (idAnterior == null || idCambiado == null) {
            throw new IllegalArgumentException(
                    "editar cambia un sector ya guardado; alguno de los dos no tiene"
                            + " identificador");
        }
        if (!idAnterior.equals(idCambiado)) {
            throw new IllegalArgumentException(
                    "El antes y el despues han de ser el mismo sector; llegaron el "
                            + idAnterior
                            + " y el "
                            + idCambiado);
        }

        Sector guardado = repositorio.guardar(cambiado);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "sector",
                                String.valueOf(guardado.id()),
                                esBaja(anterior, guardado)
                                        ? Operacion.BAJA
                                        : Operacion.MODIFICACION,
                                observacion)
                        .con(descripcion(anterior), descripcion(guardado)));

        return guardado;
    }

    /** Retirar del catalogo: estaba activo y deja de estarlo. */
    private static boolean esBaja(Sector anterior, Sector despues) {
        return anterior.activo() && !despues.activo();
    }

    /**
     * Un JSON escrito a mano y no un serializador, por lo mismo que en {@link RegistrarVia}: son
     * cuatro campos, y traer Jackson hasta aqui ataria la capa de aplicacion a la de presentacion.
     *
     * <p>La {@code zona} entra aunque sea opcional: es editable, asi que sin ella una {@code
     * MODIFICACION} que solo la cambie dejaria el antes y el despues identicos.
     */
    private static String descripcion(Sector sector) {
        return "{\"codigo\":\""
                + sector.codigo()
                + "\",\"nombre\":\""
                + escapar(sector.nombre())
                + "\",\"zona\":"
                + textoOpcional(sector.zona())
                + ",\"activo\":"
                + sector.activo()
                + "}";
    }

    /** Una zona ausente se asienta como {@code null} JSON, no como la cadena «null». */
    private static String textoOpcional(@Nullable String valor) {
        return valor == null ? "null" : "\"" + escapar(valor) + "\"";
    }

    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
