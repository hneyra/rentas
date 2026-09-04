package kamayuk.rentas.parametros.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.dominio.LlaveDeParametro;
import kamayuk.rentas.parametros.dominio.ParametroTributario;
import kamayuk.rentas.parametros.dominio.ParametrosRepository;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Armar el conjunto de parametros de un ejercicio y sellarlo (ADR-0007).
 *
 * <p><b>Aqui no se carga ninguna cifra</b>, y no por prudencia sino porque no se puede: la
 * aplicacion solo tiene {@code SELECT} sobre {@code parametro_tributario} (V7). Publicar un valor
 * normativo es trabajo de {@code rol_carga_parametros}, con su propia conexion. Es la separacion de
 * funciones de REQ-03: quien opera el sistema no publica las cifras con las que se calcula.
 *
 * <p>Lo que si hace este servicio es lo que le toca a la municipalidad: decidir que parametros ya
 * publicados componen el ejercicio, y congelarlos.
 */
@Service
public class AdministrarParametros {

    private final ParametrosRepository repositorio;
    private final Auditoria auditoria;
    private final Clock reloj;

    public AdministrarParametros(
            ParametrosRepository repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public Pagina<ConjuntoDeParametros> conjuntos(Paginacion paginacion) {
        return repositorio.conjuntos(paginacion);
    }

    @Transactional(readOnly = true)
    public List<ParametroTributario> parametrosDe(long conjuntoId) {
        return repositorio.parametrosDe(conjuntoId);
    }

    @Transactional(readOnly = true)
    public Pagina<ParametroTributario> parametros(Paginacion paginacion) {
        return repositorio.parametros(paginacion);
    }

    /**
     * Si un ejercicio tiene conjunto <b>sellado</b>, y cual (#605).
     *
     * <p><b>Que problema resuelve.</b> Hasta aqui la unica forma de saberlo era mandar la peticion
     * de calculo y recibir el 422 de {@code LectorDeParametros.EjercicioSinSellar}: quien fracciona
     * teclea el contribuyente, marca las deudas, rellena cuotas, garantia y vencimiento —y en el
     * preconvenio, la observacion que la regla 10 obliga a redactar antes de habilitar el boton—
     * para enterarse al final de que el ejercicio no esta parametrizado, que con D-02a abierta es
     * el estado de <b>todas</b> las municipalidades. Y no es solo convenios: el predial, la
     * valorizacion del FUE, la liquidacion de fiscalizacion y el resumen anual de licencias tienen
     * la misma forma de enterarse tarde.
     *
     * <p><b>Ninguna cifra sale de aqui.</b> Se publica si hay conjunto sellado y su identidad
     * —{@code conjuntoId} y {@code version}—, que es exactamente lo que {@code
     * ConvenioResource.conjuntoDeParametros} ya publica cuando el convenio existe y lo que {@code
     * Determinacion} guarda para poder repetirse. Los valores son otra cosa y siguen detras del
     * permiso de {@code parametros} (REQ-03: quien opera el sistema no publica las cifras con las
     * que se calcula).
     *
     * <p><b>«No hay conjunto sellado» es una respuesta, no un error.</b> La pregunta es «¿hay
     * conjunto sellado para este ejercicio?» —no «¿se puede calcular?», que depende ademas de que
     * cada llave que la regla pida este dentro— y su respuesta puede ser que no; devolver vacio
     * obligaria a quien pregunta a distinguir dos ausencias que aqui no son distintas —un ejercicio
     * sin ninguna version y uno con la version abierta— porque para calcular las dos valen igual.
     * Lo que si tiene que salir distinto es un ejercicio <b>fuera de rango</b>, y sale: {@link
     * Ejercicio} lo rechaza en su constructor y el borde lo traduce a 422.
     *
     * <p><b>No deja fila en la bitacora, y es deliberado.</b> Es el unico endpoint del sistema
     * fuera del catalogo de opciones —{@code SESION_PROPIA}, para que un cajero con {@code
     * fraccionamiento} pueda preguntarlo sin necesitar el permiso de {@code parametros}—, y los
     * cinco escritores de {@code Operacion.ACCESO} que ya existen estan todos detras de un acceso
     * del catalogo o de la cadena firmada del ciudadano. Auditar aqui pondria una escritura <b>sin
     * cota</b> al alcance de cualquier token valido, sobre una tabla que es append-only por diseno:
     * no hay {@code DELETE} (regla 4, RNF-051), no hay poda y no hay limite de peticiones.
     *
     * <p>Y no hace falta: lo que se publica no es dato del padron sino si la propia instalacion
     * tiene sellado un ejercicio suyo, igual que {@code permisos_de_la_sesion} (ADR-0013) y {@code
     * sesion/municipalidad} (#555), que tampoco auditan. Lo que sigue detras del permiso de {@code
     * parametros} son las cifras (REQ-03).
     */
    @Transactional(readOnly = true)
    public EstadoDelEjercicio estadoDelEjercicio(Ejercicio ejercicio) {
        Objects.requireNonNull(ejercicio, "La pregunta es por un ejercicio concreto");

        return new EstadoDelEjercicio(
                ejercicio, repositorio.selladoVigenteDe(ejercicio).orElse(null));
    }

    /**
     * Abre una version nueva del ejercicio.
     *
     * <p>La version se calcula, no se recibe: quien corrige un conjunto sellado no tiene por que
     * saber cuantas versiones hubo antes, y dejarselo elegir es la forma de acabar con dos
     * versiones 2.
     */
    @Transactional
    public ConjuntoDeParametros abrirVersion(Ejercicio ejercicio, Observacion observacion) {
        int siguiente = repositorio.ultimaVersionDe(ejercicio) + 1;
        ConjuntoDeParametros creado =
                repositorio.crear(ConjuntoDeParametros.nuevo(ejercicio, siguiente));

        auditar(creado, Operacion.ALTA, observacion);
        return creado;
    }

    /** Agrega al conjunto un parametro ya publicado. Falla si el conjunto esta sellado. */
    @Transactional
    public void agregarParametro(long conjuntoId, long parametroId, Observacion observacion) {
        ConjuntoDeParametros conjunto = conjunto(conjuntoId);
        repositorio.agregarParametro(conjuntoId, parametroId);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "conjunto_parametro_detalle",
                                conjuntoId + ":" + parametroId,
                                Operacion.ALTA,
                                observacion)
                        .con(
                                null,
                                "{\"conjuntoId\":"
                                        + conjuntoId
                                        + ",\"parametroId\":"
                                        + parametroId
                                        + ",\"ejercicio\":"
                                        + conjunto.ejercicio().valor()
                                        + "}"));
    }

    /**
     * Agrega al conjunto el parametro publicado que responde a esa llave, nombrandolo por lo que es
     * y no por su identificador.
     *
     * <p>Es lo que hace posible componer un conjunto desde un archivo de operacion que valga igual
     * en {@code stg} y en {@code prod} (ver {@link LlaveDeParametro}). La resolucion y el alta van
     * en la <b>misma</b> transaccion: entre leer el identificador y usarlo cabe otra escritura, y
     * lo que se estaria incorporando ya no seria lo que se leyo.
     *
     * @throws ProblemaDeNegocio si no hay ningun parametro publicado con esa llave, o si hay mas de
     *     uno: elegir en silencio uno de dos homonimos sellaria un valor que nadie escogio
     */
    @Transactional
    public ParametroTributario agregarParametroPublicado(
            long conjuntoId, LlaveDeParametro llave, Observacion observacion) {
        List<ParametroTributario> encontrados = repositorio.publicados(llave);
        if (encontrados.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO,
                    "No hay ningun parametro publicado con la llave "
                            + llave
                            + ". Publicarlo es trabajo de rol_carga_parametros, antes de componer"
                            + " el conjunto (REQ-03)");
        }
        if (encontrados.size() > 1) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "Hay "
                            + encontrados.size()
                            + " parametros publicados con la llave "
                            + llave
                            + ": quedarse con uno seria sellar un valor que nadie eligio");
        }

        ParametroTributario parametro = encontrados.get(0);
        // Llamada a un metodo propio: no pasa por el proxy, pero la transaccion de este metodo ya
        // esta abierta y es la que se quiere. Lo que importa aqui es que las dos operaciones sean
        // una sola, no que haya dos anotaciones.
        agregarParametro(
                conjuntoId,
                Objects.requireNonNull(parametro.id(), "Un parametro leido de la base tiene id"),
                observacion);
        return parametro;
    }

    /**
     * Sella el conjunto: a partir de aqui rige, y no se modifica.
     *
     * <p>Es el acto administrativo del que cuelga la reproducibilidad de todo el ejercicio. Por eso
     * queda con fecha y con nombre, y por eso el rechazo de un segundo sellado no depende de esta
     * comprobacion sino del disparador y del indice unico de la base (V9): entre leer el estado y
     * escribirlo cabe otra transaccion.
     */
    @Transactional
    public ConjuntoDeParametros sellar(long conjuntoId, Observacion observacion) {
        ConjuntoDeParametros conjunto = conjunto(conjuntoId);
        if (conjunto.estaSellado()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "El conjunto "
                            + conjuntoId
                            + " ya esta sellado; corregirlo exige una version"
                            + " nueva (ADR-0007)");
        }
        if (repositorio.parametrosDe(conjuntoId).isEmpty()) {
            // Un conjunto vacio sellado es peor que ninguno: la pantalla diria que el
            // ejercicio esta parametrizado y el calculo no encontraria ni la UIT.
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "El conjunto "
                            + conjuntoId
                            + " no tiene ningun parametro: sellarlo vacio diria"
                            + " que el ejercicio esta parametrizado cuando no lo esta");
        }

        Instant cuando = Instant.now(reloj);
        String quien = OrigenContext.actual().usuario();
        ConjuntoDeParametros sellado = repositorio.sellar(conjuntoId, cuando, quien);

        auditar(sellado, Operacion.MODIFICACION, observacion);
        return sellado;
    }

    private ConjuntoDeParametros conjunto(long id) {
        return repositorio
                .conjunto(id)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ningun conjunto de parametros con identificador "
                                                + id));
    }

    /**
     * Si el ejercicio esta parametrizado, y con que conjunto.
     *
     * @param ejercicio el que se pregunto, devuelto tal cual: quien lee la respuesta tiene que
     *     poder decir de que ano habla sin volver a mirar lo que envio
     * @param sellado el conjunto sellado vigente, o nulo si el ejercicio no tiene ninguno
     */
    public record EstadoDelEjercicio(Ejercicio ejercicio, @Nullable ConjuntoDeParametros sellado) {

        public EstadoDelEjercicio {
            Objects.requireNonNull(ejercicio, "La respuesta dice de que ejercicio habla");
        }

        public boolean estaSellado() {
            return sellado != null;
        }
    }

    private void auditar(
            ConjuntoDeParametros conjunto, Operacion operacion, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "conjunto_parametros",
                                String.valueOf(conjunto.id()),
                                operacion,
                                observacion)
                        .con(
                                null,
                                "{\"ejercicio\":"
                                        + conjunto.ejercicio().valor()
                                        + ",\"version\":"
                                        + conjunto.version()
                                        + ",\"estado\":\""
                                        + conjunto.estado()
                                        + "\"}"));
    }
}
