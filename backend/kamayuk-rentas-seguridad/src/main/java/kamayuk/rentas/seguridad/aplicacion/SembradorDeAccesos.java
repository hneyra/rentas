package kamayuk.rentas.seguridad.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.seguridad.dominio.CatalogoDeOpciones;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Siembra en {@code modulo_sistema} y {@code acceso} las opciones del catalogo (RF-122).
 *
 * <p>Es lo que hace cierta la promesa del manual: «al crearse una nueva opcion de menu el sistema
 * automaticamente la reconoce y brinda la posibilidad de configurar los diferentes niveles de
 * acceso». Sin esto, cada pantalla nueva exigiria que alguien recordara insertar su fila, y la
 * primera vez que se olvide habra una pantalla a la que no se le puede dar permiso.
 *
 * <p><b>Idempotente y solo agrega.</b> Se puede ejecutar en cada despliegue: lo que ya existe se
 * queda como esta —con sus permisos ya configurados— y lo que falta se crea. Lo que <b>no</b> hace
 * es borrar los accesos que ya no estan en el catalogo: los permisos que cuelgan de ellos son
 * constancia de quien pudo hacer que, y eso no se borra (RNF-051). Una opcion retirada se desactiva
 * a mano, que es una decision con consecuencias y merece a alguien detras.
 *
 * <p><b>No lleva {@code Observacion}</b>, y por eso no es un caso de uso de escritura de negocio:
 * no lo pide un usuario. Es una operacion de implantacion, como migrar el esquema. Que este marcado
 * {@code readOnly = false} y aun asi no reciba observacion es exactamente lo que la regla 10
 * prohibe, asi que <b>no lleva {@code @Transactional} de escritura anotado a mano</b>: lo envuelve
 * quien lo invoca. Ver {@link #sembrar()}.
 */
@Service
public class SembradorDeAccesos extends RepositorioJdbc {

    private final Auditoria auditoria;
    private final Clock reloj;

    public SembradorDeAccesos(JdbcClient jdbc, Auditoria auditoria, Clock reloj) {
        super(jdbc);
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Crea los modulos y accesos que falten para la municipalidad del contexto.
     *
     * @param observacion por que se ejecuta la siembra; queda en la auditoria
     * @return cuantos accesos se crearon; 0 en un despliegue donde no cambio el catalogo
     */
    @Transactional
    public int sembrar(Observacion observacion) {
        List<CatalogoDeOpciones.Opcion> opciones = CatalogoDeOpciones.leer();
        if (opciones.isEmpty()) {
            throw new IllegalStateException(
                    "El catalogo de opciones vino vacio. Sembrar cero accesos dejaria el sistema"
                            + " sin ninguna opcion configurable, y en silencio");
        }

        int creados = 0;
        for (CatalogoDeOpciones.Opcion opcion : opciones) {
            long moduloId = moduloId(opcion);
            creados += crearAccesoSiFalta(opcion, moduloId);
        }

        if (creados > 0) {
            auditoria.registrar(
                    RegistroDeAuditoria.enLaFechaDe(
                                    LocalDate.now(reloj),
                                    "acceso",
                                    "catalogo",
                                    Operacion.ALTA,
                                    observacion)
                            .con(
                                    null,
                                    "{\"accesosCreados\":"
                                            + creados
                                            + ",\"opcionesDelCatalogo\":"
                                            + opciones.size()
                                            + "}"));
        }
        return creados;
    }

    /** Crea el modulo si falta y devuelve su identificador. */
    private long moduloId(CatalogoDeOpciones.Opcion opcion) {
        jdbc().sql(
                        "INSERT INTO modulo_sistema (municipalidad_id, codigo, nombre)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :codigo, :nombre)"
                                + " ON CONFLICT (municipalidad_id, codigo) DO NOTHING")
                .param("codigo", opcion.moduloCodigo())
                .param("nombre", opcion.moduloNombre())
                .update();

        return jdbc().sql("SELECT id FROM modulo_sistema WHERE codigo = :codigo")
                .param("codigo", opcion.moduloCodigo())
                .query(Long.class)
                .single();
    }

    private int crearAccesoSiFalta(CatalogoDeOpciones.Opcion opcion, long moduloId) {
        return jdbc().sql(
                        "INSERT INTO acceso (municipalidad_id, modulo_id, tipo, codigo, nombre)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :modulo, 'OPCION_MENU', :codigo, :nombre)"
                                + " ON CONFLICT (municipalidad_id, codigo) DO NOTHING")
                .param("modulo", moduloId)
                .param("codigo", opcion.codigo())
                .param("nombre", opcion.nombre())
                .update();
    }
}
