package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.licencias.dominio.FilaDelResumenAnual;
import kamayuk.rentas.licencias.dominio.LicenciaRepository;
import kamayuk.rentas.licencias.dominio.TipoDeLicencia;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.tesoreria.CobrosDeTasas;
import kamayuk.rentas.tesoreria.RecaudacionDeTasa;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * El resumen de licencias por año de la opcion {@code licencia_resumen_anual} (#54, RF-115).
 *
 * <p>Cuantas se emitieron, cuantas se cancelaron, cuantos duplicados se autorizaron, cuantas
 * seguian vigentes al cierre y cuanto recaudo la caja por el derecho de tramite.
 *
 * <h2>«Al cierre» es un dia concreto, y por eso viaja</h2>
 *
 * <p>Para un año ya cerrado, el cierre es el 31 de diciembre. Para el año en curso, la fecha de
 * corte del reporte —no tendria sentido preguntar «cuantas seguian vigentes el 31 de diciembre de
 * este año» a mitad de año, y responderlo con la vigencia de hoy seria decir una cosa por otra—.
 * Cada fila dice cual de los dos se uso (regla 9, RNF-075).
 *
 * <h2>La recaudacion se le pide a {@code tesoreria}, y puede no estar</h2>
 *
 * <p>Se pide por el <b>concepto del TUPA</b> que el conjunto sellado de ese año nombra, y con el
 * agregado que #36 ya escribio para el avance de recaudacion: un año, una consulta. Un año cuyo
 * conjunto sellado no tenga el concepto —o que no tenga conjunto— no se puede sumar, y entonces la
 * fila llega <b>sin cifra y con el motivo</b>, nombrando la llave que falta.
 *
 * <p><b>No se pone cero</b>, y es la leccion literal de #48: un cero es indistinguible de una cifra
 * correcta cuando llega al papel, y esta hoja se usa para conciliar lo que la caja recaudo.
 *
 * <h2>Lo que la cifra recaudada NO es</h2>
 *
 * <p>Es lo que la <b>ventanilla cobro</b> por ese concepto durante el año, no «lo que costaron las
 * licencias emitidas ese año». Los dos numeros pueden diferir legitimamente —un derecho pagado el
 * 28 de diciembre para una licencia emitida en enero cuenta en el año del cobro— y por eso el
 * reporte los publica como dos columnas y no como una cuenta.
 *
 * <p>Por la misma razon, el filtro por <b>tipo de licencia</b> alcanza a los cuatro conteos y
 * <b>no</b> a la recaudacion: el recibo del derecho de tramite no sabe de que tipo sera la licencia
 * que se emita con el. Repartir la recaudacion por tipo exigiria cruzar recibos con licencias, y
 * ese cruce produce un total que no cuadra con ningun arqueo.
 *
 * <h2>Este servicio NO abre transaccion, y hace falta que no la abra</h2>
 *
 * <p>Es la unica clase de aplicacion del modulo sin {@code @Transactional}, y no es un olvido. Sus
 * tres colaboradores traen la suya —{@link ConsultaDeLicencias#conteosDelAno}, {@code
 * LectorDeParametrosSellados} y {@code CobrosDeTasas}—, y un año sin conjunto sellado hace que el
 * lector de parametros <b>lance</b>. Si este metodo envolviera a los tres en una transaccion
 * propia, esa excepcion la dejaria marcada <i>rollback-only</i> y, aunque se capture, el reporte
 * entero fallaria al confirmarla: los cinco años que si se podian calcular se perderian por culpa
 * del sexto. Lo demuestra la prueba del resumen de 2024 a 2026.
 *
 * <p>Es el reparto de #25 leido al reves: alli el defecto era que los puertos ajenos disimulaban la
 * falta de transaccion del anfitrion; aqui es que el anfitrion no debe abrir ninguna.
 */
@Service
public class ResumenAnualDeLicencias {

    /**
     * Cuantos años se admiten de una vez.
     *
     * <p><b>No es una cifra normativa</b>: es el tope del rango que la pantalla ofrece en sus dos
     * desplegables, y esta aqui para que una peticion con «desde 1900» no dispare mil consultas a
     * la caja. Quien necesite mas años pide dos reportes.
     */
    private static final int ANOS_MAXIMOS = 25;

    private final ConsultaDeLicencias licencias;
    private final CobrosDeTasas cobros;
    private final DerechosDeTramiteParametrizados derechos;

    public ResumenAnualDeLicencias(
            ConsultaDeLicencias licencias,
            CobrosDeTasas cobros,
            DerechosDeTramiteParametrizados derechos) {
        this.licencias = licencias;
        this.cobros = cobros;
        this.derechos = derechos;
    }

    /**
     * El resumen de los años del intervalo, uno por fila.
     *
     * @param desde primer año, inclusive
     * @param hasta ultimo año, inclusive
     * @param tipo el filtro por tipo de licencia; {@code null} los cuenta todos
     * @param aLaFecha el dia de corte del reporte (regla 9, RNF-075)
     * @throws IntervaloInvalido si el intervalo esta al reves o pide demasiados años
     */
    public Resumen entre(
            Ejercicio desde, Ejercicio hasta, @Nullable TipoDeLicencia tipo, LocalDate aLaFecha) {

        Objects.requireNonNull(desde, "El resumen empieza en un año concreto");
        Objects.requireNonNull(hasta, "El resumen termina en un año concreto");
        Objects.requireNonNull(
                aLaFecha, "El resumen dice de cuando es: la fecha entra como argumento (regla 9)");

        if (hasta.valor() < desde.valor()) {
            throw new IntervaloInvalido(
                    "El resumen va de "
                            + desde
                            + " a "
                            + hasta
                            + ", que termina antes de empezar: no devolveria ninguna fila y nadie"
                            + " sabria por que");
        }
        int cuantos = hasta.valor() - desde.valor() + 1;
        if (cuantos > ANOS_MAXIMOS) {
            throw new IntervaloInvalido(
                    "El resumen pide "
                            + cuantos
                            + " años y el maximo son "
                            + ANOS_MAXIMOS
                            + ": cada año consulta la recaudacion de la caja, y un intervalo sin"
                            + " tope convierte una pantalla de reportes en una carga del motor");
        }

        List<FilaDelResumenAnual> filas = new ArrayList<>(cuantos);
        for (int ano = desde.valor(); ano <= hasta.valor(); ano++) {
            filas.add(filaDe(new Ejercicio(ano), tipo, aLaFecha));
        }
        return new Resumen(filas, aLaFecha);
    }

    // ------------------------------------------------------------------

    private FilaDelResumenAnual filaDe(
            Ejercicio ejercicio, @Nullable TipoDeLicencia tipo, LocalDate aLaFecha) {

        LocalDate finDelAno = LocalDate.of(ejercicio.valor(), 12, 31);
        LocalDate alCierre = finDelAno.isAfter(aLaFecha) ? aLaFecha : finDelAno;
        LocalDate inicioDelAno = LocalDate.of(ejercicio.valor(), 1, 1);

        if (alCierre.isBefore(inicioDelAno)) {
            // El año no ha empezado a la fecha de corte. Se devuelve la fila en cero CON su
            // cierre puesto en la fecha de corte, y no se consulta nada: preguntarle a la caja por
            // un rango invertido seria pedirle que responda una pregunta que no tiene sentido.
            LicenciaRepository.ConteosDelAno ninguno = LicenciaRepository.ConteosDelAno.vacio();
            return FilaDelResumenAnual.con(
                    ejercicio,
                    ninguno.emitidas(),
                    ninguno.canceladas(),
                    ninguno.duplicados(),
                    ninguno.vigentesAlCierre(),
                    Dinero.CERO,
                    aLaFecha);
        }

        LicenciaRepository.ConteosDelAno conteos =
                licencias.conteosDelAno(ejercicio, tipo, alCierre);

        try {
            String concepto = derechos.aLaFechaDe(inicioDelAno).paraLaLicencia();
            RecaudacionDeTasa recaudado = cobros.recaudado(concepto, inicioDelAno, alCierre);
            return FilaDelResumenAnual.con(
                    ejercicio,
                    conteos.emitidas(),
                    conteos.canceladas(),
                    conteos.duplicados(),
                    conteos.vigentesAlCierre(),
                    recaudado.neto(),
                    alCierre);
        } catch (DerechosDeTramiteParametrizados.DerechoSinParametrizar sinParametro) {
            // Ni cero ni excepcion: la fila sale con sus conteos —que si se pueden contar— y con el
            // motivo por el que la cifra falta, nombrando la llave. Un cero aqui es indistinguible
            // de «no se recaudo nada» en el papel que concilia la caja (#48).
            return FilaDelResumenAnual.sinDerecho(
                    ejercicio,
                    conteos.emitidas(),
                    conteos.canceladas(),
                    conteos.duplicados(),
                    conteos.vigentesAlCierre(),
                    "Falta el parametro "
                            + sinParametro.llave().orElseThrow()
                            + " del ejercicio "
                            + ejercicio,
                    alCierre);
        } catch (LectorDeParametros.EjercicioSinSellar sinConjunto) {
            // Un año sin conjunto sellado no tiene concepto del TUPA que pedirle a la caja. Se
            // cuenta lo que si se puede contar y se dice por que falta la cifra: negarse a emitir
            // el reporte entero por un año antiguo sin sellar dejaria sin padron a quien pregunta
            // por los cinco años que si estan.
            return FilaDelResumenAnual.sinDerecho(
                    ejercicio,
                    conteos.emitidas(),
                    conteos.canceladas(),
                    conteos.duplicados(),
                    conteos.vigentesAlCierre(),
                    "El ejercicio " + ejercicio + " no tiene un conjunto de parametros sellado",
                    alCierre);
        }
    }

    // ------------------------------------------------------------------

    /**
     * El resumen entero.
     *
     * @param filas un año por fila, del mas antiguo al mas reciente
     * @param aLaFecha el dia de corte del reporte
     */
    public record Resumen(List<FilaDelResumenAnual> filas, LocalDate aLaFecha) {

        public Resumen {
            filas = List.copyOf(filas);
        }
    }

    /** El intervalo de años pedido no se puede resolver. */
    public static final class IntervaloInvalido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        IntervaloInvalido(String mensaje) {
            super(mensaje);
        }
    }
}
