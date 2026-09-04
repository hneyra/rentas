package kamayuk.rentas.catastro.infraestructura;

import java.time.LocalDate;
import kamayuk.rentas.catastro.TransferenciaDeFiscalizacion;
import kamayuk.rentas.catastro.VersionTransferida;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Lo que de {@code catastro} <b>todavia no se puede pedir</b>. Hoy queda un metodo (C-5).
 *
 * <h2>Que era esto y en que quedo</h2>
 *
 * <p>P5C dejo aqui los SIETE puertos que ninguna ruta contestaba, y su javadoc prometia que esta
 * clase <b>encoge</b>. C-5 publico las cinco lecturas —{@code GET
 * /catastro/predios/&#123;id&#125;}, {@code &#8230;/caracteristicas}, {@code
 * /catastro/fichas/&#123;id&#125;/area}, {@code /catastro/titularidad} y sus dos hermanas— y las
 * conecto en {@link CaracteristicasDelPredioHttp}, {@link TitularesDelPredioHttp}, {@link
 * PrediosDelContribuyenteHttp} y {@link TitularidadHttp}.
 *
 * <p>Queda <b>una escritura</b>, y no por falta de ruta: por falta de transaccion compartida. La
 * otra, {@code GestorDeTitularidad.transferir}, vive en {@link TitularidadHttp} porque su interfaz
 * tiene ademas un metodo que si se puede leer; el motivo es el mismo y esta escrito alli.
 *
 * <h2>Por que {@code inscribirLoHallado} no se conecta</h2>
 *
 * <p>Su unico llamador es {@code TransferirARentas.transferir}, y dentro de <b>una</b>
 * {@code @Transactional} hace cuatro cosas en este orden: <b>(1)</b> inscribe lo hallado en el
 * padron — esto—, <b>(2)</b> compone y emite la resolucion de determinacion con la version que
 * acaba de quedar inscrita, <b>(3)</b> asienta los cargos de la diferencia y <b>(4)</b> registra la
 * fila que ata las dos versiones con el documento y la liquidacion.
 *
 * <p>El comentario de ese paso 1 dice por que va primero: «para que el papel imprima lo que quedo
 * inscrito de verdad y no lo que se esperaba inscribir». Servido por HTTP, {@code catastro}
 * confirmaria su version por su cuenta y los pasos 2 a 4 ocurririan despues, en otra base: un fallo
 * en cualquiera de ellos deja <b>el padron cambiado sin resolucion que lo justifique y sin cargo
 * que cobrar</b>. Eso no es una hipotesis — es la mutacion que #52 midio, con «12 fichas donde debe
 * haber 11».
 *
 * <p>Y hay un segundo motivo, mas silencioso: la implementacion de {@code catastro} declara {@code
 * Propagation.MANDATORY} precisamente para que esto no se pueda escribir suelto. Llamada desde un
 * controlador de {@code catastro}, esa guarda <b>se cumpliria</b> —hay una transaccion, la del
 * borde— mientras el invariante que protege ya no existiria: una regla que no puede fallar donde
 * antes mordia.
 *
 * <p>Lo que lo desbloquea: que la escritura remota sea la ultima y reversible por compensacion —lo
 * que obliga a decidir que imprime el papel, porque hoy imprime lo que catastro devuelve—, o el
 * buzon de eventos de ADR-0027, que P5C dejo declarado como hueco 3.
 *
 * <h2>Y sigue lanzando, no devolviendo vacio</h2>
 *
 * <p>Eso no cambia y no puede cambiar: {@code VersionTransferida} no tiene forma vacia —su
 * constructor compacto rechaza una version que no cierre una y abra otra—, y aunque la tuviera,
 * inventarla dejaria la resolucion diciendo que no cambio nada.
 */
@Component
public class SinRutaTodavia implements TransferenciaDeFiscalizacion {

    @Override
    public VersionTransferida inscribirLoHallado(
            long predioId,
            LocalDate desde,
            String documentoOrigen,
            @Nullable AreaM2 areaHallada,
            @Nullable String usoHallado,
            Observacion observacion) {

        throw new ClienteHttpDeCatastro.EscrituraSinTransaccionCompartida(
                "inscribir lo hallado en el predio " + predioId,
                "la resolucion de determinacion, sus cargos y la fila que los ata");
    }
}
