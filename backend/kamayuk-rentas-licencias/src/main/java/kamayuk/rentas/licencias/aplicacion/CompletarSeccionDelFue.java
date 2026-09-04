package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Medida;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.EstructuraDelProyecto;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.FueRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacionRepository;
import kamayuk.rentas.licencias.dominio.PartidaDeEdificacion;
import kamayuk.rentas.licencias.dominio.ProfesionalDelFue;
import kamayuk.rentas.licencias.dominio.ProyectoDelFue;
import kamayuk.rentas.licencias.dominio.RequisitoDelFue;
import kamayuk.rentas.licencias.dominio.SeccionDelFue;
import kamayuk.rentas.licencias.dominio.TerrenoDelFue;
import kamayuk.rentas.licencias.dominio.TipoDeProfesional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Completa una seccion del FUE (#48 AC 1, RF-113).
 *
 * <h2>Por partes, y en cualquier orden</h2>
 *
 * <p>Es la mitad del AC 1 que se ve desde la ventanilla: el administrado trae el certificado de
 * parametros un dia y los planos otro, y cada visita completa la seccion que traiga. Ninguna
 * seccion exige que otra este; lo que exige que esten <b>todas</b> es emitir, y eso lo comprueba
 * {@link EmitirLicenciaDeEdificacion}.
 *
 * <h2>Se versiona; no se edita</h2>
 *
 * <p>Completar una seccion que ya estaba <b>no la sobrescribe</b>: guarda la version siguiente (V43
 * §8, el patron de {@code ficha_catastral}). Mientras el expediente se tramita, lo que el
 * administrado declaro primero y lo que corrigio despues son los dos datos, y el que se pierde con
 * un {@code UPDATE} es justo el que explica la observacion del evaluador.
 *
 * <h2>Una vez emitida, no se toca</h2>
 *
 * <p>Completar una seccion de un expediente que ya tiene licencia cambiaria lo que el papel dice
 * sin cambiar el papel. La licencia se corrige con otro acto —una modificacion de proyecto es un
 * tramite nuevo—, y por eso aqui se rechaza.
 */
@Service
public class CompletarSeccionDelFue {

    private final FueRepository expedientes;
    private final MovimientoDeEdificacionRepository movimientos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public CompletarSeccionDelFue(
            FueRepository expedientes,
            MovimientoDeEdificacionRepository movimientos,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /** Los datos urbanos. */
    @Transactional
    public TerrenoDelFue completarTerreno(
            String expediente, Terreno datos, Observacion observacion) {

        FueDeEdificacion fue = enTramite(expediente, SeccionDelFue.TERRENO);
        Instant ahora = reloj.instant();
        TerrenoDelFue guardado =
                expedientes.guardarTerreno(
                        new TerrenoDelFue(
                                null,
                                fue.identificador(),
                                1,
                                datos.codigoCatastral(),
                                datos.direccion(),
                                datos.manzana(),
                                datos.lote(),
                                datos.areaTerreno(),
                                datos.zonificacion(),
                                datos.partidaRegistral(),
                                datos.frente(),
                                datos.fondo(),
                                ahora,
                                null,
                                observacion));

        auditar(fue, SeccionDelFue.TERRENO, guardado.version(), observacion);
        return guardado;
    }

    /** Las caracteristicas del proyecto. Sin ninguna cifra de dinero (AC 2). */
    @Transactional
    public ProyectoDelFue completarProyecto(
            String expediente, Proyecto datos, Observacion observacion) {

        FueDeEdificacion fue = enTramite(expediente, SeccionDelFue.PROYECTO);
        ProyectoDelFue guardado =
                expedientes.guardarProyecto(
                        new ProyectoDelFue(
                                null,
                                fue.identificador(),
                                1,
                                datos.uso(),
                                datos.numeroPisos(),
                                datos.areaTechada(),
                                datos.areaLibre(),
                                datos.estacionamientos(),
                                datos.plazoEnMeses(),
                                reloj.instant(),
                                null,
                                observacion));

        auditar(fue, SeccionDelFue.PROYECTO, guardado.version(), observacion);
        return guardado;
    }

    /**
     * La valorizacion por pisos y estructuras.
     *
     * <p>Entra entera y se guarda entera, como una version: media valorizacion no es una
     * valorizacion, y dejar agregar lineas sueltas permitiria que una version tuviera los muros del
     * proyecto viejo y los techos del nuevo.
     */
    @Transactional
    public List<EstructuraDelProyecto> completarValorizacion(
            String expediente, List<Estructura> lineas, Observacion observacion) {

        Objects.requireNonNull(lineas, "La lista de estructuras es vacia, no nula");
        FueDeEdificacion fue = enTramite(expediente, SeccionDelFue.VALORIZACION);
        if (lineas.isEmpty()) {
            throw new SeccionVacia(SeccionDelFue.VALORIZACION);
        }

        List<EstructuraDelProyecto> aGuardar = new ArrayList<>(lineas.size());
        for (Estructura linea : lineas) {
            aGuardar.add(
                    new EstructuraDelProyecto(
                            null,
                            fue.identificador(),
                            1,
                            linea.piso(),
                            linea.partida(),
                            linea.categoria(),
                            linea.area()));
        }
        List<EstructuraDelProyecto> guardadas =
                expedientes.guardarValorizacion(fue.identificador(), aGuardar);

        auditar(
                fue,
                SeccionDelFue.VALORIZACION,
                guardadas.isEmpty() ? 1 : guardadas.get(0).version(),
                observacion);
        return guardadas;
    }

    /** Proyectistas y responsable de obra. */
    @Transactional
    public List<ProfesionalDelFue> completarProfesionales(
            String expediente, List<Profesional> firmantes, Observacion observacion) {

        Objects.requireNonNull(firmantes, "La lista de profesionales es vacia, no nula");
        FueDeEdificacion fue = enTramite(expediente, SeccionDelFue.PROFESIONALES);
        if (firmantes.isEmpty()) {
            throw new SeccionVacia(SeccionDelFue.PROFESIONALES);
        }

        Set<TipoDeProfesional> vistos = EnumSet.noneOf(TipoDeProfesional.class);
        List<ProfesionalDelFue> aGuardar = new ArrayList<>(firmantes.size());
        for (Profesional firmante : firmantes) {
            if (!vistos.add(firmante.tipo())) {
                throw new ProfesionalRepetido(firmante.tipo());
            }
            aGuardar.add(
                    new ProfesionalDelFue(
                            null,
                            fue.identificador(),
                            1,
                            firmante.tipo(),
                            firmante.nombre(),
                            firmante.colegio(),
                            firmante.colegiatura()));
        }
        List<ProfesionalDelFue> guardados =
                expedientes.guardarProfesionales(fue.identificador(), aGuardar);

        auditar(
                fue,
                SeccionDelFue.PROFESIONALES,
                guardados.isEmpty() ? 1 : guardados.get(0).version(),
                observacion);
        return guardados;
    }

    /** Documentos adjuntos. */
    @Transactional
    public List<RequisitoDelFue> completarDocumentos(
            String expediente, List<Requisito> documentos, Observacion observacion) {

        Objects.requireNonNull(documentos, "La lista de documentos es vacia, no nula");
        FueDeEdificacion fue = enTramite(expediente, SeccionDelFue.DOCUMENTOS);
        if (documentos.isEmpty()) {
            throw new SeccionVacia(SeccionDelFue.DOCUMENTOS);
        }

        List<RequisitoDelFue> aGuardar = new ArrayList<>(documentos.size());
        for (Requisito documento : documentos) {
            aGuardar.add(
                    new RequisitoDelFue(
                            null,
                            fue.identificador(),
                            1,
                            documento.requisito(),
                            documento.presentado(),
                            documento.folios()));
        }
        List<RequisitoDelFue> guardados =
                expedientes.guardarRequisitos(fue.identificador(), aGuardar);

        auditar(
                fue,
                SeccionDelFue.DOCUMENTOS,
                guardados.isEmpty() ? 1 : guardados.get(0).version(),
                observacion);
        return guardados;
    }

    // ------------------------------------------------------------------

    private FueDeEdificacion enTramite(String expediente, SeccionDelFue seccion) {
        FueDeEdificacion fue =
                expedientes
                        .porExpediente(expediente == null ? "" : expediente.strip())
                        .orElseThrow(() -> new ExpedienteInexistente(expediente));
        if (movimientos.emisionDe(fue.identificador()).isPresent()) {
            throw new ExpedienteYaEmitido(fue.expediente(), seccion);
        }
        return fue;
    }

    private void auditar(
            FueDeEdificacion fue, SeccionDelFue seccion, int version, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fue.fechaDeclaracion(),
                                "licencia_edificacion",
                                String.valueOf(fue.identificador()),
                                // Es un ALTA y no una MODIFICACION: no se sobrescribe nada, se
                                // agrega la version siguiente de la seccion. Asentarlo como
                                // MODIFICACION obligaria a llevar los datos anteriores, y los
                                // datos anteriores estan enteros en su propia fila.
                                Operacion.ALTA,
                                observacion)
                        .con(
                                null,
                                "{\"expediente\":\""
                                        + fue.expediente()
                                        + "\",\"seccion\":\""
                                        + seccion.name()
                                        + "\",\"version\":"
                                        + version
                                        + "}"));
    }

    // ------------------------------------------------------------------

    /** Los datos urbanos que llegan de la pantalla. */
    public record Terreno(
            @org.jspecify.annotations.Nullable String codigoCatastral,
            String direccion,
            @org.jspecify.annotations.Nullable String manzana,
            @org.jspecify.annotations.Nullable String lote,
            AreaM2 areaTerreno,
            @org.jspecify.annotations.Nullable String zonificacion,
            @org.jspecify.annotations.Nullable String partidaRegistral,
            @org.jspecify.annotations.Nullable Medida frente,
            @org.jspecify.annotations.Nullable Medida fondo) {}

    /** Las caracteristicas del proyecto. Ningun importe: ver {@link ProyectoDelFue}. */
    public record Proyecto(
            String uso,
            int numeroPisos,
            AreaM2 areaTechada,
            @org.jspecify.annotations.Nullable AreaM2 areaLibre,
            @org.jspecify.annotations.Nullable Integer estacionamientos,
            @org.jspecify.annotations.Nullable Integer plazoEnMeses) {}

    /** Una linea de la valorizacion. Sin importe: la cifra la pone la tabla de #17. */
    public record Estructura(int piso, PartidaDeEdificacion partida, char categoria, AreaM2 area) {}

    /** Un profesional firmante. */
    public record Profesional(
            TipoDeProfesional tipo,
            String nombre,
            @org.jspecify.annotations.Nullable String colegio,
            @org.jspecify.annotations.Nullable String colegiatura) {}

    /** Un documento adjunto. */
    public record Requisito(
            String requisito,
            boolean presentado,
            @org.jspecify.annotations.Nullable Integer folios) {}

    /** No hay ningun expediente con ese numero en esta municipalidad. */
    public static final class ExpedienteInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ExpedienteInexistente(@org.jspecify.annotations.Nullable String expediente) {
            super(
                    "No hay ningun expediente de edificacion "
                            + (expediente == null || expediente.isBlank()
                                    ? "(sin numero)"
                                    : expediente)
                            + " en esta municipalidad");
        }
    }

    /** El expediente ya tiene licencia: completar una seccion cambiaria lo que el papel dice. */
    public static final class ExpedienteYaEmitido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ExpedienteYaEmitido(String expediente, SeccionDelFue seccion) {
            super(
                    "El expediente "
                            + expediente
                            + " ya tiene su licencia otorgada, asi que su seccion de "
                            + seccion.etiqueta().toLowerCase(java.util.Locale.ROOT)
                            + " no se completa mas: cambiarla ahora dejaria al papel que el"
                            + " administrado tiene y al sistema diciendo cosas distintas. Modificar"
                            + " el proyecto autorizado es otro tramite");
        }
    }

    /** Se mando la seccion sin ninguna linea. */
    public static final class SeccionVacia extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SeccionVacia(SeccionDelFue seccion) {
            super(
                    "La seccion «"
                            + seccion.etiqueta()
                            + "» llego sin ninguna linea. Guardarla vacia la daria por completada"
                            + " sin que lo este, y el FUE se podria emitir sin ella (AC 1 de #48)");
        }
    }

    /** El mismo papel firmado dos veces en el mismo formulario. */
    public static final class ProfesionalRepetido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ProfesionalRepetido(TipoDeProfesional tipo) {
            super(
                    "El FUE trae dos veces el "
                            + tipo.etiqueta().toLowerCase(java.util.Locale.ROOT)
                            + ": con dos, ninguna consulta puede decir cual responde por la obra");
        }
    }
}
