package kamayuk.rentas.fiscalizacion.aplicacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaConLoDeclarado;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.TipoDePrograma;
import kamayuk.rentas.nucleo.PadronVehicular;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El acta de inspección: predial (RF-051) o vehicular (RF-052), sobre una <b>copia</b> —esta clase
 * nunca escribe en {@code catastro} ni en {@code rentas} (ARQ-01 §3.5, AC de #45).
 *
 * <p>La única resolución que hace antes de construir el dominio, y que {@link ActaFiscalizacion}
 * deliberadamente no hace por su cuenta: {@code fichaId} sale de {@link
 * LectorDeFichas#fichaVigenteEn}, a la fecha de la visita —no a hoy—, para que el contraste
 * hallado/declarado se pueda reproducir después (RNF-075), igual que {@code
 * RegistrarDeclaracionJurada} en {@code rentas} (#28).
 *
 * <p>El <b>uso hallado</b> lo anota el acta desde #599 ({@code acta_fiscalizacion.uso_hallado},
 * V76) y sólo la predial: hasta entonces lo tecleaba quien liquidaba, y quien visitó no tenía dónde
 * dejarlo escrito. Las reglas que lo atan —sólo predial, y obligatorio si el hallazgo es {@code
 * USO_DISTINTO}— viven en {@link ActaFiscalizacion} y otra vez en la base, no aquí.
 *
 * <p>La versión —la visita número N sobre este contribuyente dentro del mismo programa— sale de
 * {@link ActaFiscalizacionRepository#siguienteVersion}: refiscalizar no reemplaza el acta anterior,
 * agrega una versión (V4: {@code acta_fisc_version_uq}).
 *
 * <h2>A quién se fiscaliza se pregunta antes de escribir (#422)</h2>
 *
 * <p>El contribuyente y el vehículo se citan por identificador, y la tabla los ata con dos claves
 * foráneas: {@code acta_fisc_contribuyente_fk} y {@code acta_fisc_vehiculo_fk} —esta {@code NOT
 * VALID}, que no revisa las filas viejas pero sí obliga a las nuevas—. Hasta #422 nadie preguntaba
 * antes, así que un identificador que no está en el padrón llegaba al {@code INSERT} y salía como
 * un 500 con incidencia ERROR. Ahora se pregunta a los dos puertos públicos que ya existen —{@link
 * DirectorioDeContribuyentes} y {@link PadronVehicular}— y el borde lo contesta 404. Las claves
 * siguen siendo las que lo impiden; la pregunta es la que dice qué.
 */
@Service
public class RegistrarActaFiscalizacion {

    private static final String TABLA_AUDITADA = "acta_fiscalizacion";

    private final ActaFiscalizacionRepository actas;
    private final ProgramaFiscalizacionRepository programas;
    private final LectorDeFichas fichas;
    private final DirectorioDeContribuyentes contribuyentes;
    private final PadronVehicular vehiculos;
    private final Auditoria auditoria;

    public RegistrarActaFiscalizacion(
            ActaFiscalizacionRepository actas,
            ProgramaFiscalizacionRepository programas,
            LectorDeFichas fichas,
            DirectorioDeContribuyentes contribuyentes,
            PadronVehicular vehiculos,
            Auditoria auditoria) {
        this.actas = actas;
        this.programas = programas;
        this.fichas = fichas;
        this.contribuyentes = contribuyentes;
        this.vehiculos = vehiculos;
        this.auditoria = auditoria;
    }

    @Transactional
    public ActaConLoDeclarado registrarPredial(
            long programaId,
            long contribuyenteId,
            long predioId,
            LocalDate fechaVisita,
            String fiscalizador,
            @Nullable Hallazgo hallazgo,
            @Nullable BigDecimal areaHallada,
            @Nullable String usoHallado,
            @Nullable String detalle,
            Observacion observacion) {

        exigirPrograma(programaId, TipoDePrograma.PREDIAL);
        exigirHallazgo(hallazgo);
        exigirContribuyente(contribuyenteId);
        Long fichaId = fichas.fichaVigenteEn(predioId, fechaVisita).orElse(null);

        return guardar(
                ActaFiscalizacion.nuevaPredial(
                        programaId,
                        actas.siguienteVersion(programaId, contribuyenteId, predioId, null),
                        contribuyenteId,
                        predioId,
                        fichaId,
                        fechaVisita,
                        fiscalizador,
                        hallazgo,
                        areaHallada == null ? null : new AreaM2(areaHallada),
                        usoHallado,
                        detalle,
                        observacion));
    }

    @Transactional
    public ActaConLoDeclarado registrarVehicular(
            long programaId,
            long contribuyenteId,
            long vehiculoId,
            LocalDate fechaVisita,
            String fiscalizador,
            @Nullable Hallazgo hallazgo,
            @Nullable String detalle,
            Observacion observacion) {

        exigirPrograma(programaId, TipoDePrograma.VEHICULAR);
        exigirHallazgo(hallazgo);
        exigirContribuyente(contribuyenteId);
        exigirVehiculo(vehiculoId);

        return guardar(
                ActaFiscalizacion.nuevaVehicular(
                        programaId,
                        actas.siguienteVersion(programaId, contribuyenteId, null, vehiculoId),
                        contribuyenteId,
                        vehiculoId,
                        fechaVisita,
                        fiscalizador,
                        hallazgo,
                        detalle,
                        observacion));
    }

    // ------------------------------------------------------------------

    /**
     * Un acta sin hallazgo no se registra, y esto es lo que hacía daño hoy (D-16, #481).
     *
     * <p>La columna admite nulos ({@code V4}) y {@link LiquidarFiscalizacion} leía el nulo como
     * {@code CONFORME}: {@code POST /fiscalizacion/vehicular} sin hallazgo respondía <b>201</b> y
     * esa acta se liquidaba conforme — un vehículo que nadie inspeccionó, declarado en regla. En la
     * predial la condición sale de comparar superficies, así que lo que se perdía era {@code
     * NO_UBICADO}: un predio inexistente se comparaba por área como si se hubiera hallado.
     *
     * <p>La guarda va aquí y no en un {@code CHECK} porque la columna tiene que seguir admitiendo
     * nulos: no se puede afirmar que no haya actas históricas sin hallazgo, y este es el sitio
     * donde se puede decir <b>por qué</b> falla. Es el patrón de #51, #72 y #399.
     *
     * <p>Cerrarlo <b>no</b> decide con qué vocabulario se anota, que es la pregunta de D-16: esta
     * guarda sólo exige que se anote <b>alguno</b> de los valores que el dominio ya distingue. Es
     * la mitad de D-16 que su propio registro señala como desbloqueada, y por eso se cierra aquí
     * sin esperar a la otra.
     */
    private static void exigirHallazgo(@Nullable Hallazgo hallazgo) {
        if (hallazgo == null) {
            throw new IllegalArgumentException(
                    "Falta el campo 'hallazgo': un acta sin el se liquidaria como CONFORME, que es"
                            + " decir que la visita no encontro nada");
        }
    }

    private void exigirPrograma(long programaId, TipoDePrograma tipoEsperado) {
        ProgramaFiscalizacion programa =
                programas
                        .findById(programaId)
                        .orElseThrow(() -> new ProgramaInexistente(programaId));
        if (programa.tipo() != tipoEsperado) {
            throw new ProgramaDeOtroTipo(programa, tipoEsperado);
        }
    }

    /** El fiscalizado esta en el padron de esta municipalidad, o el acta no se escribe (#422). */
    private void exigirContribuyente(long contribuyenteId) {
        if (!contribuyentes.porIds(Set.of(contribuyenteId)).containsKey(contribuyenteId)) {
            throw new ContribuyenteInexistente(contribuyenteId);
        }
    }

    /** El vehiculo inspeccionado esta en el padron vehicular, o el acta no se escribe (#422). */
    private void exigirVehiculo(long vehiculoId) {
        if (!vehiculos.estaEnElPadron(vehiculoId)) {
            throw new VehiculoInexistente(vehiculoId);
        }
    }

    /**
     * Guarda el acta, la audita y devuelve <b>las dos mitades del contraste</b> (#191).
     *
     * <p>El lado declarado se resuelve aquí y no en la capa web: si el {@code POST} devolviera el
     * acta desnuda, su respuesta publicaría {@code areaDeclarada} y {@code usoDeclarado} en nulo
     * <b>siempre</b>, con el mismo contrato que la lectura de al lado y sin que nada lo dijera. Es
     * el modo de fallo que #194 midió —un campo declarado que nunca se llena, en verde—, y cuesta
     * una lectura local de la proyección para evitarlo.
     *
     * <p>Un acta vehicular no referencia ninguna versión de ficha, así que no hay nada que leer y
     * su contraste sale sin lado declarado. Es lo correcto: un vehículo no declara área ni uso.
     */
    private ActaConLoDeclarado guardar(ActaFiscalizacion nueva) {
        ActaFiscalizacion guardada = actas.insertar(nueva);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                TABLA_AUDITADA,
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                guardada.observacion())
                        .con(null, descripcion(guardada)));

        Long fichaId = guardada.fichaId();
        return fichaId == null
                ? ActaConLoDeclarado.sinLadoDeclarado(guardada)
                : ActaConLoDeclarado.de(
                        guardada,
                        actas.loDeclaradoPorFicha(java.util.Set.of(fichaId)).get(fichaId));
    }

    private static String descripcion(ActaFiscalizacion acta) {
        return "{\"programaId\":"
                + acta.programaId()
                + ",\"version\":"
                + acta.version()
                + ",\"contribuyenteId\":"
                + acta.contribuyenteId()
                + ",\"hallazgo\":"
                + (acta.hallazgo() == null ? "null" : "\"" + acta.hallazgo() + "\"")
                + "}";
    }

    /**
     * No hay ningun programa de fiscalizacion con ese identificador, o es de otra municipalidad.
     */
    public static final class ProgramaInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProgramaInexistente(long id) {
            super("No hay ningun programa de fiscalizacion con identificador " + id);
        }
    }

    /** No hay ningun contribuyente con ese identificador en el padron de esta municipalidad. */
    public static final class ContribuyenteInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ContribuyenteInexistente(long id) {
            super(
                    "No hay ningun contribuyente con identificador "
                            + id
                            + " en esta municipalidad: no se le puede levantar un acta");
        }
    }

    /**
     * No hay ningun vehiculo con ese identificador en el padron vehicular de esta municipalidad.
     */
    public static final class VehiculoInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        VehiculoInexistente(long id) {
            super(
                    "No hay ningun vehiculo con identificador "
                            + id
                            + " en el padron vehicular de esta municipalidad");
        }
    }

    /** El acta es predial y el programa es vehicular, o al reves. */
    public static final class ProgramaDeOtroTipo extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProgramaDeOtroTipo(ProgramaFiscalizacion programa, TipoDePrograma tipoEsperado) {
            super(
                    "El programa "
                            + programa.codigo()
                            + " es "
                            + programa.tipo()
                            + ", no "
                            + tipoEsperado
                            + ": no se le puede registrar un acta de ese tipo");
        }
    }
}
