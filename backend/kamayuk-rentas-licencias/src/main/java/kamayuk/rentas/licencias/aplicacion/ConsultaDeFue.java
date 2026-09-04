package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.licencias.dominio.CriterioDeFue;
import kamayuk.rentas.licencias.dominio.EstadoDelFue;
import kamayuk.rentas.licencias.dominio.EstructuraDelProyecto;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.ProfesionalDelFue;
import kamayuk.rentas.licencias.dominio.ProyectoDelFue;
import kamayuk.rentas.licencias.dominio.RequisitoDelFue;
import kamayuk.rentas.licencias.dominio.SeccionDelFue;
import kamayuk.rentas.licencias.dominio.TerrenoDelFue;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * La grilla, la ficha y el reporte general del FUE (#48, RF-113, RF-115).
 *
 * <h2>Este servicio NO abre transaccion, y hace falta que no la abra</h2>
 *
 * <p>Sus dos colaboradores traen la suya: {@link LecturaDelFue}, que lee las tablas del FUE, y
 * {@link ValorizacionDelFue}, que le pide a {@code catastro} el cuadro de valores unitarios del
 * conjunto sellado. Un ejercicio <b>sin conjunto sellado</b> —lo que ocurre hoy en todas las
 * municipalidades, D-02a— hace que el lector de parametros <b>lance</b>. Si este servicio
 * envolviera a los dos en una transaccion propia, esa excepcion la dejaria marcada
 * <i>rollback-only</i> y, aunque {@link ValorizacionDelFue} la capture para devolver el motivo, el
 * reporte entero fallaria al confirmarla con {@code UnexpectedRollbackException}: la hoja que
 * explicaba el problema no llegaria a devolverse.
 *
 * <p>Lo midio #569, y es la cuarta vez que este reparto aparece: #54 (el resumen anual de
 * licencias), #72 (el acogimiento a campania) y #247 §2 (la publicacion de parametros). Es el
 * reparto de #25 leido al reves: alli el defecto era que los puertos ajenos disimulaban la falta de
 * transaccion del anfitrion —la seccion que se lee del repositorio propio SI la necesita, y por eso
 * vive en {@link LecturaDelFue}—; aqui es que el anfitrion no debe abrir ninguna.
 *
 * <p><b>Añadir aqui una lectura suelta de un repositorio la rompe</b>, y no devolviendo vacio:
 * fallando con «invalid input syntax for type bigint: ""», porque sin transaccion no hay {@code SET
 * LOCAL} y la politica RLS no se puede evaluar (#486). Toda lectura va a {@link LecturaDelFue}.
 *
 * <h2>«A la fecha», tambien aqui</h2>
 *
 * <p>El estado depende del dia —una licencia vence—, asi que la fecha entra como argumento y viaja
 * en la respuesta. Y la valorizacion viaja con el ejercicio del conjunto sellado con que se
 * calculo: sin eso, dos consultas hechas a un anio de distancia podrian dar cifras distintas sin
 * que ninguna dijera de cuando es (regla 9, RNF-075).
 */
@Service
public class ConsultaDeFue {

    private final LecturaDelFue lectura;
    private final ValorizacionDelFue valorizaciones;

    public ConsultaDeFue(LecturaDelFue lectura, ValorizacionDelFue valorizaciones) {
        this.lectura = lectura;
        this.valorizaciones = valorizaciones;
    }

    /**
     * La grilla, paginada, con el estado de cada fila derivado a {@code aLaFecha}.
     *
     * <p><b>Sin valorizar</b>: la columna que la grilla pinta no lleva importes, y valorizar veinte
     * filas seria pedir el cuadro veinte veces para nada.
     */
    public Pagina<FueEnConsulta> buscar(
            CriterioDeFue criterio,
            @Nullable String nombreDelSolicitante,
            @Nullable EstadoDelFue estado,
            LocalDate aLaFecha,
            Paginacion paginacion) {
        return lectura.buscar(criterio, nombreDelSolicitante, estado, aLaFecha, paginacion);
    }

    /**
     * El reporte general de licencias de edificacion (RF-115, opcion {@code edificacion_reporte}).
     *
     * <p>Lo que la grilla no trae y este si: el area a construir y el valor de obra de cada fila.
     * Las filas se leen <b>en una transaccion</b> y el cuadro de valores unitarios se pide <b>fuera
     * de ella</b> y una sola vez para toda la pagina —{@link ValorizacionDelFue#valorizarVarias}—,
     * con la misma fecha de corte: si cada fila lo resolviera por su cuenta y entre dos lecturas se
     * sellara una version nueva, media hoja saldria con un cuadro y media con otro.
     *
     * <p>Una fila que no se pueda valorizar sale con su {@link ValorizacionDelFue.Resultado} <b>sin
     * cifra y con el motivo</b>, nombrando la llave que falta cuando la hay: ni cero ni error. Un
     * cero es indistinguible de una obra que no vale nada cuando llega al papel (#48).
     *
     * @param aLaFecha la fecha de corte del reporte; deriva el estado y resuelve el cuadro
     */
    public Pagina<FilaDelReporte> reporte(
            CriterioDeFue criterio,
            @Nullable String nombreDelSolicitante,
            @Nullable EstadoDelFue estado,
            LocalDate aLaFecha,
            Paginacion paginacion) {

        LecturaDelFue.DatosDelReporte datos =
                lectura.datosDelReporte(
                        criterio, nombreDelSolicitante, estado, aLaFecha, paginacion);
        if (datos.filas().estaVacia()) {
            // Ni una fila que valorizar: pedirle el cuadro a catastro seria una lectura de mas
            // para una hoja en blanco. La pagina vacia sale igual (AC 6 de #569).
            return Pagina.vacia(paginacion);
        }

        Map<Long, ValorizacionDelFue.Resultado> valorizadas =
                valorizaciones.valorizarVarias(datos.estructuras(), aLaFecha);

        return datos.filas()
                .mapear(
                        fila ->
                                new FilaDelReporte(
                                        fila,
                                        datos.proyectos().get(fila.fue().identificador()),
                                        valorizadas.get(fila.fue().identificador())));
    }

    /**
     * Los tramos de vigencia de una licencia de edificacion.
     *
     * <p>Los pide la respuesta de la revalidacion —las dos vigencias, la original y la nueva, que
     * es el AC 4 de #48 leible desde el JSON—.
     */
    public List<VigenciaDeLaLicencia> vigenciasDe(long licenciaId) {
        return lectura.vigenciasDe(licenciaId);
    }

    /** La ficha completa de un expediente: sus secciones, su historial y su valorizacion. */
    public Optional<FichaDelFue> porExpediente(String expediente, LocalDate aLaFecha) {
        return lectura.porExpediente(expediente, aLaFecha).map(this::valorizada);
    }

    /** La ficha completa por el numero de la licencia otorgada. */
    public Optional<FichaDelFue> porNumeroDeLicencia(String numero, LocalDate aLaFecha) {
        return lectura.porNumeroDeLicencia(numero, aLaFecha).map(this::valorizada);
    }

    // ------------------------------------------------------------------

    /**
     * Le pone su cifra a lo que se leyo de la base, ya fuera de la transaccion que lo leyo.
     *
     * <p>La valorizacion se resuelve con la fecha del ACTO —la de la emision si ya la hubo, y la de
     * la declaracion mientras no—, que {@link LecturaDelFue} deja resuelta en {@code fechaDelActo}.
     */
    private FichaDelFue valorizada(LecturaDelFue.DatosDeLaFicha datos) {
        return new FichaDelFue(
                datos.fila(),
                datos.terreno(),
                datos.proyecto(),
                datos.estructuras(),
                datos.profesionales(),
                datos.requisitos(),
                datos.historial(),
                datos.vigencias(),
                datos.seccionesFaltantes(),
                valorizaciones.valorizar(datos.estructuras(), datos.fechaDelActo()));
    }

    // ------------------------------------------------------------------

    /**
     * Un expediente tal como la grilla lo pinta.
     *
     * @param fue la cabecera
     * @param estado el derivado de sus movimientos y vigencias
     * @param aLaFecha el dia al que se derivo (regla 9)
     * @param numeroDeLicencia el numero otorgado; nulo mientras el expediente este en tramite
     * @param terreno el terreno vigente; nulo si la seccion no se completo
     * @param solicitante el resumen del padron; nulo si el contribuyente ya no esta
     */
    public record FueEnConsulta(
            FueDeEdificacion fue,
            EstadoDelFue estado,
            LocalDate aLaFecha,
            @Nullable String numeroDeLicencia,
            @Nullable TerrenoDelFue terreno,
            @Nullable ResumenDeContribuyente solicitante) {

        public String nombreDelSolicitante() {
            ResumenDeContribuyente resumen = solicitante;
            return resumen == null ? "" : resumen.nombre();
        }

        public String codigoDelSolicitante() {
            ResumenDeContribuyente resumen = solicitante;
            return resumen == null ? "" : resumen.codigo();
        }
    }

    /**
     * Una fila del reporte general.
     *
     * @param fila lo mismo que pinta la grilla, con su estado y su fecha
     * @param proyecto la version vigente de las caracteristicas; nula si la seccion falta
     * @param valorizacion la obra valorizada, o el motivo por el que no hay cifra; nula si la fila
     *     no llego a valorizarse
     */
    public record FilaDelReporte(
            FueEnConsulta fila,
            @Nullable ProyectoDelFue proyecto,
            ValorizacionDelFue.@Nullable Resultado valorizacion) {}

    /**
     * La ficha del FUE: la fila, sus cinco secciones, su historial y su valorizacion.
     *
     * @param fila lo mismo que pinta la grilla
     * @param terreno la version vigente de los datos urbanos; nulo si falta
     * @param proyecto la version vigente de las caracteristicas; nulo si falta
     * @param estructuras la valorizacion declarada, sin importes
     * @param profesionales los firmantes
     * @param requisitos los documentos adjuntos declarados
     * @param historial los movimientos
     * @param vigencias los tramos de vigencia, en orden (AC 4)
     * @param seccionesFaltantes las que impiden emitir hoy (AC 1)
     * @param valorizacion la obra valorizada, o el motivo por el que no hay cifra (AC 2)
     */
    public record FichaDelFue(
            FueEnConsulta fila,
            @Nullable TerrenoDelFue terreno,
            @Nullable ProyectoDelFue proyecto,
            List<EstructuraDelProyecto> estructuras,
            List<ProfesionalDelFue> profesionales,
            List<RequisitoDelFue> requisitos,
            List<MovimientoDeEdificacion> historial,
            List<VigenciaDeLaLicencia> vigencias,
            List<SeccionDelFue> seccionesFaltantes,
            ValorizacionDelFue.Resultado valorizacion) {

        public FichaDelFue {
            estructuras = List.copyOf(estructuras);
            profesionales = List.copyOf(profesionales);
            requisitos = List.copyOf(requisitos);
            historial = List.copyOf(historial);
            vigencias = List.copyOf(vigencias);
            seccionesFaltantes = List.copyOf(seccionesFaltantes);
        }

        /** Si hoy se podria emitir: no falta ninguna seccion. */
        public boolean estaCompleto() {
            return seccionesFaltantes.isEmpty();
        }
    }
}
