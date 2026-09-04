package kamayuk.rentas.indicadores.aplicacion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.coactiva.ExpedientesSinRec;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.indicadores.dominio.FrenteDeTrabajo;
import kamayuk.rentas.indicadores.dominio.FrenteParado;
import kamayuk.rentas.indicadores.dominio.TrabajoParado;
import kamayuk.rentas.rentas.PrediosSinConciliar;
import kamayuk.rentas.sanciones.PapeletasSinNotificar;
import kamayuk.rentas.valores.ValoresSinNotificar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compone el trabajo parado por modulo de la pantalla de aterrizaje (#549, RF-130).
 *
 * <h2>No cuenta: pregunta</h2>
 *
 * <p>Las cuatro lecturas son de <b>APIs publicas</b> de otros modulos y ninguna es una tabla, igual
 * que en {@link PanelDeRecaudacion}. Los cuatro puertos devuelven <b>agregados</b> —un recuento, a
 * lo sumo con su suma— y no filas: un frente que devolviera el padron dejaria a la pantalla que
 * todo el mundo abre al entrar recorriendolo entero (AC 4 de #56), y {@code
 * PanelSinRecorrerElLibroTest} lo comprueba sobre este servicio tambien.
 *
 * <h2>Una sola transaccion, una sola foto</h2>
 *
 * <p>{@code @Transactional(readOnly = true)} en el metodo, y los cuatro puertos se unen a ella
 * —{@code REQUIRED} es la propagacion por omision—. Con cuatro transacciones separadas cada
 * recuento saldria de un instante distinto, y ademas <b>sin transaccion no hay {@code SET
 * LOCAL}</b> y la politica RLS no puede evaluar {@code app.municipalidad_id}: la consulta falla con
 * 500 en vez de devolver vacio (#486).
 *
 * <h2>El permiso lo decide el borde, y aqui solo se obedece</h2>
 *
 * <p>Este servicio recibe el conjunto de frentes <b>visibles</b> y no pregunta por los demas: no
 * los cuenta y no los publica. Quien sabe quien esta preguntando es el controlador —es donde vive
 * {@code OrigenContext}—, y es el mismo reparto que {@code ConsultaDeConciliacion} eligio para el
 * permiso de fiscalizacion de {@code conciliadaConRentas=No}.
 *
 * <p>Que el frente no salga —en vez de salir vacio o con un guion— es el AC 2.3, y es la leccion de
 * #297: una fila vacia ya dice que ahi hay algo que mirar.
 *
 * <h2>Lo que este servicio NO hace</h2>
 *
 * <p>No suma los cuatro recuentos ni compone un total. Son poblaciones distintas —papeletas,
 * valores, expedientes y predios— y sumarlas daria un numero sin unidad; y con un perfil que no ve
 * los cuatro, ese total ademas cambiaria de significado segun quien mire.
 */
@Service
public class ConsultaDeTrabajoParado {

    private final PapeletasSinNotificar papeletas;
    private final ValoresSinNotificar valores;
    private final ExpedientesSinRec expedientes;
    private final PrediosSinConciliar predios;

    public ConsultaDeTrabajoParado(
            PapeletasSinNotificar papeletas,
            ValoresSinNotificar valores,
            ExpedientesSinRec expedientes,
            PrediosSinConciliar predios) {
        this.papeletas = papeletas;
        this.valores = valores;
        this.expedientes = expedientes;
        this.predios = predios;
    }

    /**
     * Los frentes que ese perfil puede ver, leidos a esa fecha y a ese instante.
     *
     * @param ejercicio el ejercicio contra el que se concilia el padron
     * @param aLaFecha el dia de corte de los recuentos
     * @param leidoEn el instante exacto de la lectura
     * @param visibles los frentes cuyo permiso tiene quien pregunta; los demas no se consultan
     */
    @Transactional(readOnly = true)
    public TrabajoParado del(
            Ejercicio ejercicio,
            LocalDate aLaFecha,
            Instant leidoEn,
            Set<FrenteDeTrabajo> visibles) {

        Objects.requireNonNull(ejercicio, "El trabajo parado se cuenta contra un ejercicio");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        Objects.requireNonNull(leidoEn, "Dice tambien a que hora se leyo");
        Objects.requireNonNull(visibles, "El conjunto es vacio, no nulo");

        List<FrenteParado> frentes = new ArrayList<>();

        if (visibles.contains(FrenteDeTrabajo.TRANSITO)) {
            PapeletasSinNotificar.PapeletasImpuestas impuestas = papeletas.sinNotificar();
            // El unico de los cuatro que el modulo sabe cifrar: `papeleta.importe_a_pagar` lo
            // suma la misma consulta que cuenta. Cero papeletas suman S/ 0.00, y ese cero es
            // un hecho: no es lo mismo que «no se sabe cifrar» (AC 2.2).
            frentes.add(
                    FrenteParado.cifrado(
                            FrenteDeTrabajo.TRANSITO,
                            impuestas.cuantas(),
                            impuestas.importe(),
                            aLaFecha));
        }
        if (visibles.contains(FrenteDeTrabajo.VALORES)) {
            frentes.add(
                    FrenteParado.soloContado(
                            FrenteDeTrabajo.VALORES, valores.cuantosA(aLaFecha), aLaFecha));
        }
        if (visibles.contains(FrenteDeTrabajo.COACTIVA)) {
            frentes.add(
                    FrenteParado.soloContado(
                            FrenteDeTrabajo.COACTIVA, expedientes.cuantosSinRec1(), aLaFecha));
        }
        if (visibles.contains(FrenteDeTrabajo.CATASTRO)) {
            frentes.add(
                    FrenteParado.soloContado(
                            FrenteDeTrabajo.CATASTRO,
                            predios.cuantosA(ejercicio, aLaFecha),
                            aLaFecha));
        }

        return new TrabajoParado(ejercicio, aLaFecha, leidoEn, frentes);
    }
}
