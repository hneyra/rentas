package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.nucleo.dominio.predial.MinimoImponible;
import kamayuk.rentas.nucleo.dominio.predial.RT011BaseImponibleDelContribuyente;
import kamayuk.rentas.nucleo.dominio.predial.Tramo;
import kamayuk.rentas.nucleo.dominio.predial.TramosProgresivosAcumulativos;
import kamayuk.rentas.parametros.InsumosDeLaAgregacion;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Determina el predial de un contribuyente para un ejercicio (#30, RF de determinacion predial).
 *
 * <p><b>Alcance de #30, no del predial completo.</b> Encadena solo lo que NEG-05/ARQ-09 tienen
 * confirmado y sin bloqueo:
 *
 * <ol>
 *   <li>{@code RT011BaseImponibleDelContribuyente#agregar} suma la base ya ponderada de cada predio
 *       —{@link DetalleDeterminacionPredio#baseImponiblePredio}—, que llega declarada por quien
 *       invoca este caso de uso: el paso que la calcularia sobre el autovaluo del predio — {@code %
 *       actualizacion} y RT-001/002/005/010— sigue bloqueado por D-11 y D-02a, y este servicio no
 *       lo inventa.
 *   <li>{@code TramosProgresivosAcumulativos.calcular} (RT-013) aplica el cuadro sobre esa base ya
 *       agregada, nunca predio por predio (NEG-05 §1).
 *   <li>{@code MinimoImponible.aplicar} (RT-014) sustituye el resultado si no llega al minimo.
 * </ol>
 *
 * <p><b>El redondeo se lee del conjunto sellado</b>, con {@link PoliticasDeRedondeoSelladas}: es el
 * tercer entregable de E-7 (#203). La respuesta de D-03c —en que puntos se redondea, con que escala
 * y que modo— entra como dato con su documento fuente, no como codigo, y este servicio no la
 * construye ni la recibe.
 *
 * <p>{@code tramos} y {@code minimoImponible}, en cambio, siguen llegando como argumento, y la
 * diferencia no es un descuido: de D-03c ya esta decidido el <b>formato</b> —una fila {@code
 * REDONDEO:‹punto›} por punto— y lo que falta son los valores; del cuadro del articulo 13 no esta
 * decidido ni el formato —una clave por tramo, cuantos tramos—, y fijarlo aqui congelaria una forma
 * que D-02b podria contradecir. Ninguno de los dos sale de un literal (regla 5).
 *
 * <p>No usa {@code MotorDeReglas.aplicarAlContribuyente}: el motor exige al menos una {@code
 * ReglaTributaria} vigente por partida (fase 1, terreno/edificacion/obras) para no fallar con
 * {@code SinReglasVigentes}, y esa fase completa —RT-001 a RT-010— sigue sin implementar. Llama
 * {@link RT011BaseImponibleDelContribuyente#agregar} directamente, como ya hacen RT-013 y RT-014
 * fuera del motor.
 */
@Service
public class RegistrarDeterminacionPredial {

    private final DeterminacionRepository repositorio;
    private final LectorDeParametros parametros;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarDeterminacionPredial(
            DeterminacionRepository repositorio,
            LectorDeParametros parametros,
            Auditoria auditoria,
            Clock reloj) {
        this.repositorio = repositorio;
        this.parametros = parametros;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Determina el predial: agrega la base del contribuyente, aplica los tramos y el minimo, y
     * guarda la cabecera con su detalle por predio. Siempre inserta una fila nueva —{@link
     * DeterminacionRepository} no tiene {@code actualizar}—: recalcular con otro conjunto sellado
     * es otra determinacion, nunca una edicion de la anterior (AC2/AC3, ADR-0007).
     *
     * @param predios el aporte de cada predio del contribuyente, ya declarado (autovaluo, %
     *     propiedad y base ya ponderada); nunca vacio (NEG-05 §1: sin predios no hay base)
     * @param tramos el cuadro progresivo vigente, resuelto por quien conoce la ordenanza (D-02b)
     * @param minimoImponible el minimo del ejercicio (D-02b)
     * @param observacion por que se registra (regla 10)
     */
    @Transactional
    public Determinacion registrar(
            Ejercicio ejercicio,
            long contribuyenteId,
            List<DetalleDeterminacionPredio> predios,
            List<Tramo> tramos,
            Dinero minimoImponible,
            Observacion observacion) {
        return registrar(
                ejercicio, contribuyenteId, predios, tramos, minimoImponible, false, observacion);
    }

    /**
     * Lo mismo, pero pudiendo <b>no</b> guardar: con {@code simulacion = true} calcula y devuelve
     * la cabecera sin insertar ninguna fila y sin auditarla, igual que {@code
     * RegistrarDeterminacionVehicular#calcular} desde #32.
     *
     * <p>Es lo que permite que la pantalla tenga un boton «Simular» y otro «Calcular» contra la
     * <b>misma</b> operacion del contrato sin que pulsar el primero emita deuda (#395): la
     * diferencia esta en el cuerpo de la peticion, no en la ruta. La determinacion simulada se
     * reconoce por {@link Determinacion#esNueva()} —no tiene identificador, porque no hay fila que
     * lo lleve—.
     *
     * <p>La observacion se exige igual en los dos caminos: quien simula tambien deja rastro de por
     * que lo hizo cuando el calculo se asienta, y bifurcar el requisito abriria la puerta a que un
     * cliente mandara {@code simulacion = true} para saltarse la regla 10.
     */
    @Transactional
    public Determinacion registrar(
            Ejercicio ejercicio,
            long contribuyenteId,
            List<DetalleDeterminacionPredio> predios,
            List<Tramo> tramos,
            Dinero minimoImponible,
            boolean simulacion,
            Observacion observacion) {
        Objects.requireNonNull(ejercicio, "La determinacion necesita su ejercicio");
        Objects.requireNonNull(predios, "La lista de predios es vacia, no nula");
        if (predios.isEmpty()) {
            throw new SinPrediosDeclarados();
        }

        ParametrosSellados sellados = parametros.vigenteEn(ejercicio);
        long conjuntoId = parametros.conjuntoVigenteEn(ejercicio).valor();
        PoliticasDeRedondeo redondeo = PoliticasDeRedondeoSelladas.de(sellados);
        InsumosDeLaAgregacion insumos = new InsumosDeLaAgregacion(ejercicio, sellados, redondeo);

        List<Dinero> aportes = new ArrayList<>();
        for (DetalleDeterminacionPredio predio : predios) {
            aportes.add(predio.baseImponiblePredio());
        }

        RT011BaseImponibleDelContribuyente rt011 = new RT011BaseImponibleDelContribuyente();
        Dinero baseContribuyente = rt011.agregar(List.copyOf(aportes), insumos);

        Dinero impuestoPorTramos =
                TramosProgresivosAcumulativos.calcular(baseContribuyente, tramos, redondeo);
        Dinero montoDeterminado = MinimoImponible.aplicar(impuestoPorTramos, minimoImponible);

        Determinacion nueva =
                Determinacion.nuevaPredial(
                        ejercicio,
                        contribuyenteId,
                        conjuntoId,
                        baseContribuyente,
                        montoDeterminado,
                        List.of(rt011.identificador().valor(), "RT-013", "RT-014"));

        if (simulacion) {
            return nueva;
        }

        Determinacion guardada = repositorio.insertar(nueva, predios);
        auditar(guardada, observacion);
        return guardada;
    }

    private void auditar(Determinacion guardada, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "determinacion",
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardada)));
    }

    private static String descripcion(Determinacion determinacion) {
        String reglas =
                determinacion.reglasAplicadas().stream()
                        .map(regla -> "\"" + regla + "\"")
                        .collect(Collectors.joining(",", "[", "]"));
        return "{\"contribuyenteId\":"
                + determinacion.contribuyenteId()
                + ",\"ejercicio\":\""
                + determinacion.ejercicio()
                + "\",\"conjuntoId\":"
                + determinacion.conjuntoId()
                + ",\"baseImponible\":\""
                + determinacion.baseImponible()
                + "\",\"montoDeterminado\":\""
                + determinacion.montoDeterminado()
                + "\",\"reglasAplicadas\":"
                + reglas
                + "}";
    }

    /** Se pidio determinar un contribuyente sin ningun predio declarado. */
    public static final class SinPrediosDeclarados extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SinPrediosDeclarados() {
            super(
                    "Un contribuyente sin predios no tiene base imponible cero: no tiene"
                            + " determinacion (NEG-05 §1, igual que MotorDeReglas.SinPartidas)");
        }
    }
}
