package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.EmbudoDeFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelProgramaRepository;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El embudo de un programa de fiscalización: las cifras que {@code fis-panel} dibuja (#196).
 *
 * <h2>Por qué esto es una operación y no cuatro</h2>
 *
 * <p>Porque un embudo se lee entero o no se lee. Las cuatro etapas se podían sacar del {@code
 * totalElementos} de cuatro operaciones distintas y componerlas en el navegador, y eso es
 * exactamente lo que {@code conectores.ts} prohíbe: cuatro peticiones para cuatro números que
 * ninguna operación afirma que signifiquen eso, tres de ellas acotadas a mano a un programa, y un
 * resultado indistinguible de uno publicado que <b>nadie puede cuadrar</b>. Aquí las cuatro se
 * resuelven en la misma transacción, o sea sobre la misma foto.
 *
 * <h2>La primera etapa se mueve y las otras tres no, y la respuesta lo dice</h2>
 *
 * <p>«Programados», «con acta» y «con diferencia» están congeladas por lo que se sorteó, se visitó
 * y se liquidó. «Detectados por cruce» se resuelve contra el padrón de <b>hoy</b> con los mismos
 * parámetros del programa, así que cambia sola conforme entran declaraciones y fichas. No hay dónde
 * leer el detectado del día del sorteo: {@code ResultadoDelSorteo} lo devolvió una vez y no se
 * guarda en ninguna columna. Por eso la respuesta lleva {@code aLaFecha} (regla 9): la cifra es
 * cierta, y de un día.
 *
 * <h2>{@code @Transactional(readOnly = true)}</h2>
 *
 * <p>Sin transacción no hay {@code SET LOCAL} y la política RLS <b>falla</b> en vez de devolver
 * filas — el defecto de clase de #486, repetido en seis issues. Y aquí hay cuatro lecturas: sin una
 * sola transacción, además, cada una vería una foto distinta.
 */
@Service
public class ConsultaDelEmbudo {

    /**
     * Por qué campo se recorre el padrón al contar los detectados, y <b>es el nombre que la fila
     * publica</b>.
     *
     * <p>El mismo que {@link GenerarMuestra}, y por el mismo motivo: {@code predio_id} salió de la
     * lista blanca en #546 y pedirlo devuelve <b>422 {@code ORDEN_NO_ADMITIDO}</b>. Que aquí sólo
     * se lea el sobre no exime: la paginación exige siempre un {@code ORDER BY} válido.
     */
    private static final String ORDEN_DEL_RECORRIDO = "codRefCatastral";

    /**
     * Se pide <b>una</b> fila y se lee el sobre.
     *
     * <p>La detección no tiene un método de conteo suelto, y darle uno sería una segunda
     * transcripción de la condición al motor —lo que {@link
     * kamayuk.rentas.fiscalizacion.dominio.DeteccionRepository} ya razona que no se hace—. El
     * {@code totalElementos} de esta página cuenta el conjunto entero, que es lo que el embudo
     * necesita, y una fila de más es un precio que no depende del tamaño del padrón.
     */
    private static final int UNA_FILA = 1;

    /**
     * Las condiciones que justifican determinar de oficio. Las decide el dominio, no esta clase.
     */
    private static final Set<CondicionFiscalizada> CON_DIFERENCIA =
            EnumSet.copyOf(
                    java.util.Arrays.stream(CondicionFiscalizada.values())
                            .filter(CondicionFiscalizada::hayDiferencia)
                            .toList());

    private final ProgramaFiscalizacionRepository programas;
    private final MuestraDelProgramaRepository muestras;
    private final ActaFiscalizacionRepository actas;
    private final LiquidacionRepository liquidaciones;
    private final DeteccionDeOmisos deteccion;
    private final Clock reloj;

    public ConsultaDelEmbudo(
            ProgramaFiscalizacionRepository programas,
            MuestraDelProgramaRepository muestras,
            ActaFiscalizacionRepository actas,
            LiquidacionRepository liquidaciones,
            DeteccionDeOmisos deteccion,
            Clock reloj) {
        this.programas = programas;
        this.muestras = muestras;
        this.actas = actas;
        this.liquidaciones = liquidaciones;
        this.deteccion = deteccion;
        this.reloj = reloj;
    }

    /**
     * El embudo del programa.
     *
     * @throws ProgramaInexistente si no existe, o es de otra municipalidad —lo segundo lo decide la
     *     política RLS y no un {@code WHERE}—
     */
    @Transactional(readOnly = true)
    public EmbudoDeFiscalizacion de(long programaId) {
        ProgramaFiscalizacion programa =
                programas
                        .findById(programaId)
                        .orElseThrow(() -> new ProgramaInexistente(programaId));

        LocalDate hoy = LocalDate.now(reloj);
        String falta = programa.parametrosDeLaMuestra().orElse(null);

        return new EmbudoDeFiscalizacion(
                programaId,
                programa.codigo(),
                programa.ejercicio(),
                hoy,
                falta == null ? detectados(programa, hoy) : null,
                falta,
                muestras.tamanoDeLaMuestra(programaId),
                actas.unidadesConActaViva(programaId),
                liquidaciones.unidadesConDiferencia(programaId, CON_DIFERENCIA));
    }

    /**
     * Cuántos predios señala el cruce con los parámetros <b>del programa</b>, no con otros.
     *
     * <p>Los mismos tres con los que {@link GenerarMuestra} sorteó su muestra —ejercicio, sector y
     * criterio—, leídos del programa. Resolverlos de otra manera dejaría dos verdades sobre el
     * mismo padrón, y la que se lee en la pantalla sería la que nadie recalculó.
     */
    private int detectados(ProgramaFiscalizacion programa, LocalDate hoy) {
        Ejercicio ejercicio = java.util.Objects.requireNonNull(programa.ejercicio());
        return (int)
                deteccion
                        .detectar(
                                ejercicio,
                                programa.sectorCodigo(),
                                programa.criterio(),
                                hoy,
                                new Paginacion(
                                        0,
                                        UNA_FILA,
                                        ORDEN_DEL_RECORRIDO,
                                        Paginacion.Direccion.ASCENDENTE))
                        .totalElementos();
    }

    /** El programa no existe, o es de otra municipalidad. */
    public static final class ProgramaInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProgramaInexistente(long id) {
            super("No existe el programa de fiscalizacion " + id);
        }
    }
}
