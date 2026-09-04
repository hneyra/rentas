package kamayuk.rentas.tesoreria.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dominio.Area;
import kamayuk.rentas.tesoreria.dominio.AreaRepository;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.CajaRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Da de alta una ventanilla de cobro y, si hace falta, el área a la que imputa (#430).
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>Hasta aquí <b>nada creaba una {@code caja} ni un {@code area} fuera de las fixtures de
 * prueba</b>. Las dos tablas existen desde {@code V3}, {@code sgtm_app} puede escribirlas desde
 * {@code V7}, {@link AbrirCaja} sabe abrir el turno de un cajero en una de ellas… y en una
 * instalación recién implantada no había ninguna: la primera cobranza del día fallaba con {@code
 * CajaInexistente} y no había forma de arreglarlo desde dentro del sistema. Lo destapó la siembra
 * de la municipalidad de demostración, y no una revisión: {@code ImplantarMunicipalidad} deja la
 * municipalidad, sus accesos, sus dos grupos y su administrador, pero ninguna ventanilla.
 *
 * <h2>Por qué un caso de uso de carga y no una pantalla</h2>
 *
 * <p>Las diez opciones de Tesorería del manual (NEG-03) cobran, cierran, anulan y consultan;
 * <b>ninguna da de alta una caja</b>. Publicar un endpoint que ninguna pantalla llama sería
 * inventar contrato, que es justo lo que {@code AbrirCaja} evita por lo mismo. Las ventanillas y
 * sus áreas son <b>configuración de la municipalidad</b> —como el catálogo vial, los sectores y las
 * manzanas—, y entran por donde entra esa clase de dato: un archivo, una vez, en la implantación
 * ({@code infra/carga-de-datos/cargar-cajas.sh}).
 *
 * <h2>El área y la caja, en la misma transacción</h2>
 *
 * <p>Una fila del archivo declara una caja y el área a la que imputa. Las dos entran juntas o no
 * entra ninguna: media fila —un área sin su ventanilla— no es un alta incompleta, es basura que
 * nadie va a mirar. El área se reutiliza si ya existe, porque varias ventanillas del mismo archivo
 * suelen imputar a la misma.
 *
 * <p>La transacción es de <b>una fila</b> y no del archivo: {@link ImportarCajas} llama a este
 * servicio una vez por línea, y una que reviente la unicidad no se lleva por delante a la siguiente
 * (#121, la lección de {@code ImportarVias}).
 *
 * <p>{@code serie} es obligatoria y es lo que numera los recibos de esa ventanilla ({@code V29}):
 * dos cajas no pueden compartirla, y el índice de la base lo garantiza.
 */
@Service
public class RegistrarCaja {

    private static final String TABLA_AREA = "area";
    private static final String TABLA_CAJA = "caja";

    private final AreaRepository areas;
    private final CajaRepository cajas;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarCaja(
            AreaRepository areas, CajaRepository cajas, Auditoria auditoria, Clock reloj) {
        this.areas = areas;
        this.cajas = cajas;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Da de alta la ventanilla, con su área si la nombra.
     *
     * @param codigo el código de la caja, único en la municipalidad
     * @param nombre el rótulo de la ventanilla
     * @param serie la serie con que numera sus recibos, única en la municipalidad (V29)
     * @param codigoDeArea el área a la que imputa lo que recauda; nulo en la caja tributaria
     *     general, que no imputa a ninguna (la columna {@code area_id} es nullable desde V3)
     * @param nombreDelArea cómo se llama esa área <b>si hay que crearla</b>. Con el área ya
     *     registrada se ignora: el archivo no reescribe el nombre de un área que ya opera
     * @param observacion por qué se da de alta (regla 10, RNF-052)
     */
    @Transactional
    public Caja registrar(
            String codigo,
            String nombre,
            String serie,
            @Nullable String codigoDeArea,
            @Nullable String nombreDelArea,
            Observacion observacion) {

        Long areaId =
                codigoDeArea == null ? null : areaDe(codigoDeArea, nombreDelArea, observacion);
        Caja guardada = cajas.insertar(new Caja(null, codigo, nombre, serie, areaId, true));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                TABLA_CAJA,
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcionDe(guardada)));

        return guardada;
    }

    // ------------------------------------------------------------------

    /** El identificador del área: la que ya está, o una nueva con su propia fila de auditoría. */
    private long areaDe(
            String codigoDeArea, @Nullable String nombreDelArea, Observacion observacion) {

        Area existente = areas.porCodigo(codigoDeArea).orElse(null);
        if (existente != null) {
            return java.util.Objects.requireNonNull(
                    existente.id(), "Un area leida del repositorio siempre trae su identificador");
        }
        if (nombreDelArea == null || nombreDelArea.isBlank()) {
            throw new IllegalArgumentException(
                    "El area '"
                            + codigoDeArea
                            + "' no esta registrada y la fila no dice como se llama: sin nombre no"
                            + " se puede dar de alta");
        }

        Area nueva = areas.insertar(new Area(null, codigoDeArea, nombreDelArea, true));
        long id =
                java.util.Objects.requireNonNull(
                        nueva.id(), "Un area recien insertada siempre trae su identificador");

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                TABLA_AREA,
                                String.valueOf(id),
                                Operacion.ALTA,
                                observacion)
                        .con(null, "{\"codigo\":\"" + nueva.codigo() + "\"}"));

        return id;
    }

    private static String descripcionDe(Caja caja) {
        return "{\"codigo\":\""
                + caja.codigo()
                + "\",\"serie\":\""
                + caja.serie()
                + "\",\"activa\":"
                + caja.activa()
                + "}";
    }
}
