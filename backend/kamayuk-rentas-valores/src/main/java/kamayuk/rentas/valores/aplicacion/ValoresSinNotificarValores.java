package kamayuk.rentas.valores.aplicacion;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.valores.ValoresSinNotificar;
import kamayuk.rentas.valores.dominio.CriterioDeConsultaDeValores;
import kamayuk.rentas.valores.dominio.SituacionDelValor;
import kamayuk.rentas.valores.dominio.ValorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que {@code valores} le contesta al panel de trabajo parado (#549).
 *
 * <p>Una sola linea de negocio, y esta en el criterio: {@link SituacionDelValor#EMITIDO}, que es
 * «existe, no esta anulado, no esta pagado y todavia no se ha notificado» <b>a esa fecha</b>. La
 * situacion de un valor no es una columna sino una expresion sobre tres tablas, y por eso la fecha
 * viaja: preguntar «cuantos hay sin notificar» sin decir a que dia es preguntar algo que tiene dos
 * respuestas ciertas (regla 9).
 *
 * <p>{@code @Transactional(readOnly = true)} por lo mismo que en el resto del sistema: sin
 * transaccion no hay {@code SET LOCAL} y la politica RLS no puede evaluar {@code
 * app.municipalidad_id} —la consulta <b>falla</b> con 500, no devuelve vacio (#486)—.
 */
@Service
public class ValoresSinNotificarValores implements ValoresSinNotificar {

    private final ValorRepository valores;

    public ValoresSinNotificarValores(ValorRepository valores) {
        this.valores = valores;
    }

    @Override
    @Transactional(readOnly = true)
    public long cuantosA(LocalDate aLaFecha) {
        Objects.requireNonNull(aLaFecha, "La situacion de un valor se mira a una fecha (regla 9)");
        return valores.contar(
                new CriterioDeConsultaDeValores(
                        null, null, null, null, SituacionDelValor.EMITIDO, aLaFecha));
    }
}
