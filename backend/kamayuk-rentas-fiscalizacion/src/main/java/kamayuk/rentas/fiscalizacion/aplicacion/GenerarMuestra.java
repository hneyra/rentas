package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelPrograma;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelProgramaRepository;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ResultadoDelSorteo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sortea la muestra de un programa: los predios que se van a inspeccionar (#481, RF-050).
 *
 * <p><b>No hay ninguna detección nueva aquí.</b> Las filas salen de {@link DeteccionDeOmisos}, que
 * es la única fuente de la condición en el sistema y la misma que dibuja {@code fisc_omisos}.
 * Resolverla otra vez con un criterio propio dejaría dos verdades sobre el mismo predio, y la que
 * se lee en pantalla sería la que nadie recalculó — el defecto que #397 se negó a introducir.
 *
 * <p><b>Se guarda, y no se recalcula.</b> El motivo es la exclusión: una muestra no vuelve a
 * sortear un predio que otro programa abierto ya se llevó, ni uno que ya se fiscalizó este
 * ejercicio, y para saberlo hay que tenerlos escritos. De paso contesta «¿por qué me tocó a mí?»
 * con la fila del día del sorteo, sin necesidad de una semilla reproducible.
 *
 * <p><b>Y depende del orden, que es lo pedido y hay que decirlo:</b> el primer programa que se
 * genere se lleva los predios, y el segundo sale más corto.
 *
 * <p><b>Lo que excluye, lo cuenta y lo dice</b> (#586). Hasta este issue devolvía un {@code int}
 * —cuántos entraron— y la exclusión era literalmente muda: una muestra de 100 sobre un padrón donde
 * 4 977 predios no podían entrar no es una muestra de ese padrón, y nada en la respuesta ni en la
 * auditoría permitía sospecharlo. Ahora devuelve {@link ResultadoDelSorteo}, que reparte cada
 * predio detectado en exactamente una casilla y no se deja construir si la suma no cuadra.
 */
@Service
public class GenerarMuestra {

    private static final String TABLA_AUDITADA = "programa_muestra";

    /** Cuántas filas del padrón se piden a la detección por vuelta. */
    private static final int TAMANO_DE_PAGINA = 200;

    /**
     * Desde qué clave empieza el recorrido: antes de la primera. Las claves del padrón son
     * positivas (identidad de {@code predio}).
     *
     * <p><b>El padrón se recorre por su clave, no por páginas numeradas</b> (#346, anotado en
     * #629). Hasta aquí se pedía la página {@code n} con {@code OFFSET}, ordenada por {@code
     * codRefCatastral}, y el candado de #346 serializa los sorteos pero no el padrón: un alta que
     * catastro proyectara entre dos vueltas y que ordenara antes de la ventana repetía el último
     * predio de la vuelta anterior —que chocaba contra {@code programa_muestra_uq}, 500— y no se
     * examinaba nunca; una baja se saltaba en silencio el primer predio de la vuelta siguiente.
     * Cada vuelta empieza ahora después del último predio leído. Y como el recorrido ya no pasa por
     * la lista blanca de orden de la grilla, el 422 {@code ORDEN_NO_ADMITIDO} que #586 encontró
     * aquí no puede volver.
     */
    private static final long ANTES_DEL_PRIMERO = 0L;

    private final ProgramaFiscalizacionRepository programas;
    private final MuestraDelProgramaRepository muestras;
    private final ActaFiscalizacionRepository actas;
    private final DeteccionDeOmisos deteccion;
    private final Auditoria auditoria;
    private final Clock reloj;

    public GenerarMuestra(
            ProgramaFiscalizacionRepository programas,
            MuestraDelProgramaRepository muestras,
            ActaFiscalizacionRepository actas,
            DeteccionDeOmisos deteccion,
            Auditoria auditoria,
            Clock reloj) {
        this.programas = programas;
        this.muestras = muestras;
        this.actas = actas;
        this.deteccion = deteccion;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /** El programa no existe, o es de otra municipalidad —lo segundo lo decide RLS—. */
    public static final class ProgramaInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProgramaInexistente(long id) {
            super("No existe el programa de fiscalizacion " + id);
        }
    }

    /**
     * El programa no lleva uno de los parámetros con los que se sortea. Nombra cuál: es lo único
     * honesto que se puede hacer con un programa registrado antes de {@code V60}.
     */
    public static final class ProgramaSinParametros extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProgramaSinParametros(String parametro) {
            super(
                    "El programa no declara '"
                            + parametro
                            + "', y sin el no se puede sortear su muestra");
        }
    }

    /**
     * El programa no sortea predios (#343): es {@code VEHICULAR}, y la detección de la que sale la
     * muestra es el cruce del padrón de <b>predios</b>. Nombra el tipo porque es lo que hay que
     * cambiar; no nombra un parámetro, porque no falta ninguno.
     */
    public static final class ProgramaSinPadronQueSortear extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProgramaSinPadronQueSortear(ProgramaFiscalizacion programa) {
            super(
                    "El programa "
                            + programa.codigo()
                            + " es "
                            + programa.tipo()
                            + ": la deteccion es de predios, y un programa "
                            + programa.tipo()
                            + " no tiene padron que sortear");
        }
    }

    /** Ya se sorteó. Una muestra es un acto y no se regenera: para otra muestra, otro programa. */
    public static final class MuestraYaSorteada extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        MuestraYaSorteada(long programaId) {
            super(
                    "El programa "
                            + programaId
                            + " ya sorteo su muestra, y una muestra no se vuelve a sortear");
        }
    }

    /**
     * @return el reparto del padrón examinado: cuántos se detectaron, cuántos entraron, cuántos de
     *     ellos sin titular vigente, y cuántos quedaron fuera por cada motivo
     */
    @Transactional
    public ResultadoDelSorteo generar(long programaId, Observacion observacion) {
        ProgramaFiscalizacion programa =
                programas
                        .findById(programaId)
                        .orElseThrow(() -> new ProgramaInexistente(programaId));

        // Antes que los parametros (#343): a un programa VEHICULAR no le falta nada, es que la
        // deteccion no es de su padron. Decirle «falta el criterio» seria mentirle.
        if (!programa.sorteaPredios()) {
            throw new ProgramaSinPadronQueSortear(programa);
        }

        programa.parametrosDeLaMuestra()
                .ifPresent(
                        falta -> {
                            throw new ProgramaSinParametros(falta);
                        });

        // El candado, antes de la primera lectura en que el sorteo se apoya (#346): el segundo
        // sorteo de la municipalidad espera a que el primero confirme, y entonces ve su fila de
        // sorteo —el mismo programa sale 409— y sus predios —la exclusion entre programas los
        // aparta—. Sin el, dos sorteos simultaneos se llevaban los mismos predios.
        muestras.bloquearLosSorteos();
        if (muestras.yaSorteo(programaId)) {
            throw new MuestraYaSorteada(programaId);
        }

        Ejercicio ejercicio = java.util.Objects.requireNonNull(programa.ejercicio());
        LocalDate fechaSorteo = LocalDate.now(reloj);
        List<MuestraDelPrograma> sorteadas = new ArrayList<>();

        // El padron se recorre por vueltas y no de una vez: la deteccion no se pide entera, y un
        // distrito son decenas de miles de predios. Por su clave, no por numero de pagina (#346,
        // anotado en #629): ver ANTES_DEL_PRIMERO.
        //
        // Los tres recuentos se ACUMULAN aqui, no se leen de la ultima vuelta: leerlos de la
        // ultima daria un numero plausible y equivocado, que es el modo de fallo de #586 repetido
        // un escalon mas arriba. `ResultadoDelSorteo` no se deja construir si la suma no cuadra.
        int detectados = 0;
        int porOtroPrograma = 0;
        int porActaDelEjercicio = 0;

        long ultimo = ANTES_DEL_PRIMERO;
        List<FilaDeOmisos> encontradas;
        do {
            encontradas =
                    deteccion.siguientes(
                            ejercicio,
                            programa.sectorCodigo(),
                            programa.criterio(),
                            fechaSorteo,
                            ultimo,
                            TAMANO_DE_PAGINA);

            Reparto reparto = repartir(encontradas, programaId, ejercicio, fechaSorteo);
            sorteadas.addAll(reparto.admitidas());
            detectados += encontradas.size();
            porOtroPrograma += reparto.porOtroPrograma();
            porActaDelEjercicio += reparto.porActaDelEjercicio();

            if (!encontradas.isEmpty()) {
                ultimo = encontradas.getLast().predioId();
            }
        } while (encontradas.size() == TAMANO_DE_PAGINA);

        ResultadoDelSorteo resultado =
                new ResultadoDelSorteo(
                        fechaSorteo,
                        detectados,
                        sorteadas.size(),
                        (int) sorteadas.stream().filter(MuestraDelPrograma::sinTitular).count(),
                        porOtroPrograma,
                        porActaDelEjercicio);

        // El sorteo se registra SIEMPRE, tambien cuando no entro nadie (#346): «ya se sorteo» lo
        // dice esta fila, no las de la muestra. Y va antes que las filas: si dos peticiones
        // llegaran hasta aqui, la segunda choca en la clave del sorteo y sale 409, no contra
        // `programa_muestra_uq` con un 500.
        if (!muestras.registrarSorteo(programaId, resultado, observacion, reloj.instant())) {
            throw new MuestraYaSorteada(programaId);
        }
        if (!sorteadas.isEmpty()) {
            muestras.insertar(sorteadas, observacion, reloj.instant());
        }

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                TABLA_AUDITADA,
                                String.valueOf(programaId),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(programa, resultado)));

        return resultado;
    }

    // ------------------------------------------------------------------

    /**
     * Las <b>dos</b> exclusiones de #481, resueltas por página y no fila a fila: un predio no entra
     * si otro programa que admite visitas ya se lo llevó, ni si ya tiene acta dentro del ejercicio.
     *
     * <p><b>Y ya no son tres</b> (#586). Hasta este issue había una más —el predio sin titular
     * vigente—, y era la que hacía daño: desde #545 la detección los enseña porque un predio que
     * nadie reclama es exactamente el que hay que fiscalizar, y eran el 34,5 % del padrón de
     * Catacaos. Se apartaban porque {@code programa_muestra.contribuyente_id} era {@code NOT NULL},
     * y {@code V73} lo relajó: estar en la muestra no le cobra nada a nadie, y la visita es lo que
     * resuelve quién ocupa. Lo que la fila lleva es la columna nula, no un titular inventado.
     *
     * <p><b>Cada predio cae en exactamente una casilla.</b> Un predio puede cumplir los dos motivos
     * a la vez, así que se le atribuye el primero que le aplique: sumar los dos por separado daría
     * más excluidos que detectados, y contar mal es el defecto que este issue denuncia.
     */
    private Reparto repartir(
            List<FilaDeOmisos> filas, long programaId, Ejercicio ejercicio, LocalDate fechaSorteo) {

        Set<Long> predios = new HashSet<>();
        for (FilaDeOmisos fila : filas) {
            predios.add(fila.predioId());
        }

        Set<Long> yaProgramados = muestras.prediosEnProgramasAbiertos(programaId, predios);
        Set<Long> yaFiscalizados = actas.prediosConActaEnElEjercicio(ejercicio, predios);

        List<MuestraDelPrograma> admitidas = new ArrayList<>();
        int porOtroPrograma = 0;
        int porActaDelEjercicio = 0;
        for (FilaDeOmisos fila : filas) {
            if (yaProgramados.contains(fila.predioId())) {
                porOtroPrograma++;
            } else if (yaFiscalizados.contains(fila.predioId())) {
                porActaDelEjercicio++;
            } else {
                admitidas.add(MuestraDelPrograma.sorteada(programaId, fila, fechaSorteo));
            }
        }
        return new Reparto(admitidas, porOtroPrograma, porActaDelEjercicio);
    }

    /**
     * Lo que una página del padrón dejó: lo que entra, y por qué motivo se quedó fuera el resto.
     */
    private record Reparto(
            List<MuestraDelPrograma> admitidas, int porOtroPrograma, int porActaDelEjercicio) {}

    /**
     * La descripción que queda en la bitácora, y que lleva <b>el reparto entero</b> (#586). Con
     * sólo {@code "predios": N} la exclusión era muda también para quien audita meses después: no
     * había forma de saber sobre qué padrón se sorteó esa muestra.
     */
    private static Map<String, Object> descripcion(
            ProgramaFiscalizacion programa, ResultadoDelSorteo resultado) {
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("programa", programa.codigo());
        campos.put("criterio", programa.criterio());
        campos.put("detectados", resultado.detectados());
        campos.put("predios", resultado.sorteados());
        campos.put("sinTitular", resultado.sorteadosSinTitular());
        campos.put("excluidosPorOtroPrograma", resultado.excluidosPorOtroPrograma());
        campos.put("excluidosPorActaDelEjercicio", resultado.excluidosPorActaDelEjercicio());
        campos.put("fechaSorteo", resultado.fechaSorteo());
        return campos;
    }
}
