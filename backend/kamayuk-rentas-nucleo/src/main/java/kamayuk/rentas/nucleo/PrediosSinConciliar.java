package kamayuk.rentas.nucleo;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Cuantos predios tienen ficha catastral y no declararon el ejercicio (#549, ADR-0015).
 *
 * <p>Es la <b>API publica</b> de {@code rentas} para el panel de trabajo parado, y devuelve un
 * <b>agregado</b>: un recuento, nunca la lista. La lista es otra cosa —«quien no genera deuda
 * predial», y en manos equivocadas el mapa de a quien no le va a llegar recibo (ADR-0015 §2.3)—, y
 * por eso ella deja rastro en la bitacora y esto no.
 *
 * <h2>Sale del recuento que ya existe, no de un {@code count} propio</h2>
 *
 * <p>Lo sirve {@code ConsultaDeConciliacion.resumen(ejercicio, aLaFecha)}, el mismo que publica
 * {@code GET /catastro/fichas/conciliacion/resumen} desde #564. No se escribe una segunda
 * definicion de «sin conciliar»: la grilla de la conciliacion ya la tiene y dos copias del mismo
 * predicado se contradicen — de hecho ya se contradijeron una vez, cuando la grilla contaba el
 * padron entero y el panel de Catastro dibujaba «Predios sin conciliar: 14 422» sobre «14 422
 * predios en el padron».
 *
 * <h2>Lleva ejercicio y fecha, y las dos deciden la cifra</h2>
 *
 * <p>No existe «sin conciliar»: existe «sin conciliar a un ejercicio». El padron afecto se rehace
 * cada anio y declarar 2024 no concilia 2026. Y la fecha de corte porque la poblacion son las
 * fichas vigentes ese dia (regla 9, RNF-075).
 *
 * <p><b>Sin importe.</b> Un predio sin declarar no tiene deuda predial —eso es justamente lo que le
 * falta—, asi que no hay cifra que dar; ponerle una obligaria a determinar el impuesto de quien no
 * declaro, que es otro acto. Por que cuesta dinero: tienen ficha catastral y no generan deuda.
 */
public interface PrediosSinConciliar {

    /**
     * Cuantos predios con ficha vigente ese dia no tienen declaracion jurada de ese ejercicio.
     *
     * @param ejercicio el ejercicio al que se concilia
     * @param aLaFecha el dia al que se resuelve la ficha vigente
     */
    long cuantosA(Ejercicio ejercicio, LocalDate aLaFecha);
}
