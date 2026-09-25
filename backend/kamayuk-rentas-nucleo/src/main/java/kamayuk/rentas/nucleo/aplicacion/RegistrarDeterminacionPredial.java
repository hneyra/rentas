package kamayuk.rentas.nucleo.aplicacion;

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
import kamayuk.rentas.nucleo.dominio.predial.ModalidadDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.RT011BaseImponibleDelContribuyente;
import kamayuk.rentas.nucleo.dominio.predial.Tramo;
import kamayuk.rentas.nucleo.dominio.predial.TramosProgresivosAcumulativos;
import kamayuk.rentas.parametros.InsumosDeLaAgregacion;
import kamayuk.rentas.parametros.ParametrosSellados;
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
 *   <li>{@code MinimoImponible.aplicarSobreBase} (RT-014) sustituye el resultado si no llega al
 *       minimo. Con la base afecta exactamente cero no sustituye nada: lanza {@link
 *       MinimoImponible.BaseAfectaCero}, porque NEG-05 no decide si ahi se cobra el minimo o nada
 *       (RT-014-c02/c03) y hasta #332 este servicio cobraba el minimo sin decirlo.
 * </ol>
 *
 * <p><b>El redondeo se lee del conjunto sellado</b>, con {@link
 * kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas}: es el tercer entregable de E-7 (#203). La
 * respuesta de D-03c —en que puntos se redondea, con que escala y que modo— entra como dato con su
 * documento fuente, no como codigo, y este servicio no la construye: la lee del conjunto que llega
 * resuelto en el {@link CuadroPredialParametrizado.Vigente} (#361), el mismo de los tramos.
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
    private final Auditoria auditoria;

    /**
     * Sin {@code LectorDeParametros} desde #361, y es a proposito: el conjunto llega ya resuelto en
     * el {@link CuadroPredialParametrizado.Vigente} que recibe {@link #calcular}. Con el lector a
     * mano, volver a preguntar «que conjunto rige» era una linea, y esa linea es el defecto.
     */
    public RegistrarDeterminacionPredial(DeterminacionRepository repositorio, Auditoria auditoria) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
    }

    /**
     * Calcula el predial <b>sin escribir nada</b>: agrega la base del contribuyente, aplica los
     * tramos y el minimo, y devuelve la cabecera <b>sin identificador</b> —{@link
     * Determinacion#esNueva()}—.
     *
     * <h2>Por que calcular y asentar son dos metodos (#359)</h2>
     *
     * <p>Hasta #359 esto era {@code registrar}, que calculaba <b>y</b> asentaba en la misma
     * transaccion. {@link DeterminarPredial} necesita el monto para repartir las cuotas, y la unica
     * forma que tenia de obtenerlo era escribir: la fila y su {@code ALTA} quedaban confirmados
     * <b>antes</b> de resolver el derecho de emision, los vencimientos de la modalidad y el punto
     * {@code CUOTA}, y si faltaba cualquiera de los tres el usuario veia un 422 con la
     * determinacion ya escrita. Cada reintento dejaba otra, y la del intento fallido pasaba a ser
     * «la ultima» del ejercicio.
     *
     * <p>Eran dos motivos de cambio en una clase: el <b>calculo</b> —RT-011, RT-013, RT-014— y el
     * <b>asiento</b> —insertar, auditar, transaccion—. Partidos, quien compone la determinacion
     * entera la calcula aqui, resuelve lo demas y llama a {@link #asentar} <b>al final</b>, que es
     * el orden de ARQ-09 §4 («¿parametros completos? → no → DETENER» antes de la determinacion); y
     * simular se reduce a no llamar a {@link #asentar}.
     *
     * <p>No abre transaccion: no escribe, y no lee nada —el conjunto sellado llega resuelto—.
     *
     * <h2>El conjunto llega resuelto, y no se vuelve a preguntar (#361)</h2>
     *
     * <p>Hasta #361 este metodo recibia el ejercicio y preguntaba al lector dos veces: {@code
     * vigenteEn} para el redondeo y {@code conjuntoVigenteEn} para el {@code conjunto_id} que se
     * guarda. Quien lo llama ya habia resuelto el cuadro —de ahi salian los tramos y el minimo—,
     * asi que una determinacion resolvia el conjunto <b>cuatro</b> veces, y en produccion cada una
     * es una pregunta por red a {@code normativa} con su propio repliegue. Si {@code normativa}
     * sellaba «2026 v2» entre la primera y la cuarta, o contestaba en una y no en otra, la fila
     * guardaba v2 con el impuesto de los tramos de v1: recalcular con el {@code conjunto_id}
     * guardado daba otra cifra (ARQ-09 §3, regla 6). Ahora el redondeo, los parametros de RT-011 y
     * el identificador salen del {@code vigente} que llega, el mismo del que salieron los tramos.
     *
     * @param vigente el cuadro ya resuelto, de UNA resolucion: de el salen el ejercicio, el
     *     redondeo y el {@code conjunto_id} que se guarda
     * @param predios el aporte de cada predio del contribuyente, ya declarado (autovaluo, %
     *     propiedad y base ya ponderada); nunca vacio (NEG-05 §1: sin predios no hay base)
     * @param tramos el cuadro progresivo vigente, resuelto por quien conoce la ordenanza (D-02b)
     * @param minimoImponible el minimo del ejercicio (D-02b)
     * @param modalidad bajo que cronograma del articulo 15 se emite; viaja en la cabecera y {@link
     *     #asentar} la GUARDA (#234)
     */
    public Determinacion calcular(
            CuadroPredialParametrizado.Vigente vigente,
            long contribuyenteId,
            List<DetalleDeterminacionPredio> predios,
            List<Tramo> tramos,
            Dinero minimoImponible,
            ModalidadDelPredial modalidad) {
        Objects.requireNonNull(vigente, "La determinacion necesita el cuadro ya resuelto");
        Objects.requireNonNull(predios, "La lista de predios es vacia, no nula");
        if (predios.isEmpty()) {
            throw new SinPrediosDeclarados();
        }

        Ejercicio ejercicio = vigente.ejercicio();
        ParametrosSellados sellados = vigente.sellados();
        long conjuntoId = vigente.conjuntoId();
        PoliticasDeRedondeo redondeo = vigente.redondeo();
        InsumosDeLaAgregacion insumos = new InsumosDeLaAgregacion(ejercicio, sellados, redondeo);

        List<Dinero> aportes = new ArrayList<>();
        for (DetalleDeterminacionPredio predio : predios) {
            aportes.add(predio.baseImponiblePredio());
        }

        RT011BaseImponibleDelContribuyente rt011 = new RT011BaseImponibleDelContribuyente();
        Dinero baseContribuyente = rt011.agregar(List.copyOf(aportes), insumos);

        Dinero impuestoPorTramos =
                TramosProgresivosAcumulativos.calcular(baseContribuyente, tramos, redondeo);
        // Sobre la BASE, y no solo sobre el impuesto (#332): con la base afecta exactamente cero,
        // RT-014 no esta decidida (NEG-05, RT-014-c02/c03) y no se asienta ninguna fila.
        Dinero montoDeterminado =
                MinimoImponible.aplicarSobreBase(
                        impuestoPorTramos, baseContribuyente, minimoImponible);

        return Determinacion.nuevaPredial(
                ejercicio,
                contribuyenteId,
                conjuntoId,
                baseContribuyente,
                montoDeterminado,
                List.of(rt011.identificador().valor(), "RT-013", "RT-014"),
                modalidad);
    }

    /**
     * Asienta una determinacion ya calculada con {@link #calcular}: inserta la cabecera con su
     * detalle por predio y la audita con la observacion del usuario (regla 10). Es lo unico de esta
     * clase que escribe, y lo unico que abre transaccion.
     *
     * <p>Siempre inserta una fila nueva —{@link DeterminacionRepository} no tiene {@code
     * actualizar}—: recalcular con otro conjunto sellado es otra determinacion, nunca una edicion
     * de la anterior (AC2/AC3, ADR-0007). Por eso se niega a asentar una cabecera que ya tiene
     * identificador: seria la misma determinacion escrita dos veces.
     *
     * <p><b>Quien la llama, la llama al final</b> (#359): con todo lo que la determinacion necesita
     * para emitirse ya resuelto. Lo que falle despues de esta linea ya no deshace la fila.
     *
     * @param calculada la cabecera que devolvio {@link #calcular}, sin identificador
     * @param predios el mismo detalle con que se calculo
     * @param observacion por que se registra (regla 10)
     */
    @Transactional
    public Determinacion asentar(
            Determinacion calculada,
            List<DetalleDeterminacionPredio> predios,
            Observacion observacion) {
        Objects.requireNonNull(calculada, "Se asienta una determinacion ya calculada");
        Objects.requireNonNull(observacion, "Toda modificacion exige la observacion (regla 10)");
        Objects.requireNonNull(predios, "La lista de predios es vacia, no nula");
        if (!calculada.esNueva()) {
            throw new IllegalArgumentException(
                    "La determinacion "
                            + calculada.id()
                            + " ya esta asentada: recalcular es otra fila, nunca la misma escrita"
                            + " dos veces (ADR-0007)");
        }
        if (predios.isEmpty()) {
            throw new SinPrediosDeclarados();
        }
        Determinacion guardada = repositorio.insertar(calculada, predios);
        auditar(guardada, observacion);
        return guardada;
    }

    private void auditar(Determinacion guardada, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
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
                + ",\"modalidad\":\""
                + determinacion.modalidad()
                + "\"}";
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
