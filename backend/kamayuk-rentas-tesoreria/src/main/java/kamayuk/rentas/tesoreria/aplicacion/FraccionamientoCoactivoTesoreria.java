package kamayuk.rentas.tesoreria.aplicacion;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.tesoreria.ConvenioCoactivo;
import kamayuk.rentas.tesoreria.CuotaDelConvenio;
import kamayuk.rentas.tesoreria.FraccionamientoCoactivo;
import kamayuk.rentas.tesoreria.SolicitudDeConvenioCoactivo;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.Cronograma;
import kamayuk.rentas.tesoreria.dominio.CuotaDeConvenio;
import kamayuk.rentas.tesoreria.dominio.EstadoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeConvenio;
import org.springframework.stereotype.Service;

/**
 * Implementa {@link FraccionamientoCoactivo} sobre {@link RegistrarPreconvenio} (#42, RF-105).
 *
 * <p><b>Esta clase no tiene logica de negocio, y es a proposito.</b> Traduce la solicitud que llega
 * de {@code coactiva} a la {@code Peticion} que la ventanilla usa desde #35, fija el tipo en {@code
 * COACTIVO} y proyecta el resultado a los tipos que cruzan el limite del modulo. Todo lo demas —la
 * deuda acogible releida del libro, el interes y el maximo de cuotas del conjunto sellado, el
 * cronograma, el numero, la auditoria y la garantia de que lo que sale es un preconvenio— lo hace
 * {@code RegistrarPreconvenio} sin enterarse de que quien llama es coactiva.
 *
 * <p><b>Lo unico que si hace es traducir</b>, y solo lo que no puede cruzar el limite: las
 * excepciones de negocio de {@code RegistrarPreconvenio} y las de «falta publicar una cifra» viven
 * en subpaquetes de {@code tesoreria}, asi que {@code coactiva} no las puede nombrar sin que Spring
 * Modulith lo rechace. Salen como {@link FraccionamientoCoactivo.SinDeudaCoactivaQueFraccionar} y
 * {@link FraccionamientoCoactivo.CondicionesSinPublicar}, con su mensaje intacto (#42, #562).
 *
 * <p>La sexta era {@code PoliticasDeRedondeo.PuntoSinPolitica}, y desde #633 no puede llegar aqui:
 * el unico sitio de tesoreria que resuelve un punto —{@code CondicionesParametrizadas}— lo pide por
 * {@link PoliticasDeRedondeoSelladas#en}, que la traduce a {@code PuntoSinObservar} para que la
 * respuesta pueda decir <b>de que ejercicio</b> es la fila que falta. Se nombra la que llega y no
 * la que ya no puede llegar: un {@code catch} que no se puede disparar no protege nada.
 *
 * <p>Si esta clase tuviera una regla propia seria la senal de que el fraccionamiento coactivo no es
 * el mismo mecanismo, y entonces habria dos sitios donde arreglar un defecto del cronograma. El
 * criterio de aceptacion de #42 es exactamente ese: reutilizar, y justificar en el diff cualquier
 * divergencia.
 *
 * <h2>La unica divergencia, y por que</h2>
 *
 * <p>El convenio coactivo no admite <b>garantia</b> ni <b>reformulacion</b> por este puerto. La
 * garantia porque el ofrecimiento —carta fianza, hipoteca, aval— es una figura de la ventanilla que
 * hoy es solo constancia (D-02b) y que la pantalla {@code fraccionamiento_coactivo} no dibuja; la
 * reformulacion porque encadenar un convenio a otro exige haber leido el de origen, y eso lo hace
 * {@code CerrarConvenio} —que tiene su ruta y su comprobacion del recibo de la inicial—. Dejarlas
 * entrar aqui seria abrir dos caminos a lo mismo con distintas guardas.
 */
@Service
public class FraccionamientoCoactivoTesoreria implements FraccionamientoCoactivo {

    private final RegistrarPreconvenio preconvenios;

    public FraccionamientoCoactivoTesoreria(RegistrarPreconvenio preconvenios) {
        this.preconvenios = preconvenios;
    }

    @Override
    public ConvenioCoactivo simular(SolicitudDeConvenioCoactivo solicitud) {
        RegistrarPreconvenio.Simulacion simulada;
        try {
            simulada = preconvenios.simular(peticionDe(solicitud));
        } catch (RegistrarPreconvenio.SinDeudaQueFraccionar sinDeuda) {
            throw new SinDeudaCoactivaQueFraccionar(mensajeDe(sinDeuda), sinDeuda);
        } catch (CondicionesParametrizadas.CondicionSinParametrizar
                | LectorDeParametros.EjercicioSinSellar
                | PoliticasDeRedondeoSelladas.SinPuntosObservados
                | PoliticasDeRedondeoSelladas.MediaPolitica
                | PoliticasDeRedondeoSelladas.EscalaNoEntera
                | PoliticasDeRedondeoSelladas.ModoDesconocido
                | PoliticasDeRedondeoSelladas.PuntoSinObservar falta) {
            throw new CondicionesSinPublicar(mensajeDeLoQueFalta(falta), falta);
        }

        List<CuotaDelConvenio> cronograma = cronogramaDe(simulada.cronograma());
        return new ConvenioCoactivo(
                null,
                TipoDeConvenio.COACTIVO.name(),
                EstadoDeConvenio.PRECONVENIO.name(),
                solicitud.fecha(),
                simulada.aLaFecha(),
                simulada.total(),
                Cronograma.inicialDe(simulada.cronograma()),
                (int) simulada.cronograma().stream().filter(c -> !c.esInicial()).count(),
                Cronograma.total(simulada.cronograma()),
                simulada.condiciones().interesMensual(),
                simulada.condiciones().conjuntoId(),
                cronograma,
                simulada.acogible());
    }

    @Override
    public ConvenioCoactivo registrar(
            SolicitudDeConvenioCoactivo solicitud,
            @org.jspecify.annotations.Nullable String claveDeIdempotencia,
            Observacion observacion) {
        Convenio guardado;
        try {
            // Con su clave (#606). El comentario que estaba aqui decia que este puerto no lo
            // llama un cliente HTTP, y era falso: al final de esta cadena esta
            // `ConvenioCoactivoController.fraccionar`, que es un `@PostMapping("/convenios")`.
            // Un reenvio del mismo intento abria un segundo convenio sobre la misma deuda,
            // que es el defecto entero de este issue en la otra ruta.
            guardado =
                    preconvenios.registrar(peticionDe(solicitud), claveDeIdempotencia, observacion);
        } catch (RegistrarPreconvenio.SinDeudaQueFraccionar sinDeuda) {
            throw new SinDeudaCoactivaQueFraccionar(mensajeDe(sinDeuda), sinDeuda);
        } catch (CondicionesParametrizadas.CondicionSinParametrizar
                | LectorDeParametros.EjercicioSinSellar
                | PoliticasDeRedondeoSelladas.SinPuntosObservados
                | PoliticasDeRedondeoSelladas.MediaPolitica
                | PoliticasDeRedondeoSelladas.EscalaNoEntera
                | PoliticasDeRedondeoSelladas.ModoDesconocido
                | PoliticasDeRedondeoSelladas.PuntoSinObservar falta) {
            throw new CondicionesSinPublicar(mensajeDeLoQueFalta(falta), falta);
        }

        return new ConvenioCoactivo(
                guardado.numero().impreso(),
                guardado.tipo().name(),
                // Siempre PRECONVENIO: `RegistrarPreconvenio` no escribe ningun movimiento, y sin
                // la cuota inicial cobrada en caja el convenio no existe (criterio de #35).
                EstadoDeConvenio.PRECONVENIO.name(),
                guardado.fecha(),
                guardado.fechaCorte(),
                guardado.montoTotal(),
                guardado.cuotaInicial(),
                guardado.numeroDeCuotas(),
                guardado.totalDelCronograma(),
                guardado.condiciones().interesMensual(),
                guardado.condiciones().conjuntoId(),
                cronogramaDe(guardado.cronograma()),
                guardado.acogida());
    }

    // ------------------------------------------------------------------

    /** La misma peticion que la ventanilla, con el tipo fijado y sin garantia ni origen. */
    private static RegistrarPreconvenio.Peticion peticionDe(SolicitudDeConvenioCoactivo solicitud) {
        return new RegistrarPreconvenio.Peticion(
                solicitud.contribuyenteId(),
                solicitud.obligaciones(),
                TipoDeConvenio.COACTIVO,
                solicitud.fecha(),
                solicitud.fechaDeCorte(),
                solicitud.cuotas(),
                solicitud.porcentajeInicial(),
                solicitud.primeraCuotaVence(),
                null,
                null,
                solicitud.resolucion(),
                null);
    }

    private static List<CuotaDelConvenio> cronogramaDe(List<CuotaDeConvenio> cuotas) {
        List<CuotaDelConvenio> proyectadas = new ArrayList<>(cuotas.size());
        for (CuotaDeConvenio cuota : cuotas) {
            proyectadas.add(
                    new CuotaDelConvenio(
                            cuota.numero(),
                            cuota.vencimiento(),
                            cuota.monto(),
                            cuota.capital(),
                            cuota.interes(),
                            cuota.gasto()));
        }
        return proyectadas;
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La seleccion no tiene deuda que fraccionar" : mensaje;
    }

    /**
     * El mensaje de la cifra que falta, tal cual lo escribio quien la pide.
     *
     * <p>Ya nombra la llave —{@code INTERES_FRACCIONAMIENTO:ORDINARIO}, {@code REDONDEO:CUOTA}— o
     * el ejercicio cuando lo que falta es el conjunto entero, y esa distincion es lo unico que
     * separa tres arreglos distintos (#547). Reescribirlo aqui la perderia.
     */
    private static String mensajeDeLoQueFalta(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null
                ? "Falta publicar alguna de las cifras con que se arma el cronograma"
                : mensaje;
    }
}
