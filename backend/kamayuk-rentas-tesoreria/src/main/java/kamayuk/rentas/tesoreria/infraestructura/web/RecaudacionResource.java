package kamayuk.rentas.tesoreria.infraestructura.web;

import java.util.List;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeRecaudacion;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDePartida;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDeTributo;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * El avance de recaudacion y su distribucion, tal como salen por HTTP (#36, RF-088, RF-089).
 *
 * <p>Todo importe viaja como {@link ImporteActualizado} (regla 9, RNF-075), y la interfaz no
 * recompone ninguno: los netos y los totales los calcula el dominio. Es la misma regla que el resto
 * de la cola, y la que impide que la pantalla reste dos cifras y muestre una tercera que nadie
 * archivo.
 */
public final class RecaudacionResource {

    private RecaudacionResource() {}

    /**
     * El avance por tributo de un periodo.
     *
     * <p><b>Sin columnas de emitido, saldo, avance ni meta</b>, que es lo que la pantalla dibuja y
     * no existe como dato: la meta no tiene tabla, y lo emitido son cargos del libro, que este
     * contexto no lee. El motivo entero esta en {@link ConsultaDeRecaudacion}. Publicar «meta: 0»
     * invitaria a la interfaz a mostrar un porcentaje de cumplimiento que nadie firmo.
     *
     * @param desde primer dia del rango, en ISO
     * @param hasta ultimo dia del rango, en ISO
     * @param aLaFecha la fecha a la que se leyo (regla 9)
     * @param filas una por tributo, de mayor a menor
     * @param cobrado lo que entro en el periodo
     * @param anulado lo que las anulaciones sacaron
     * @param neto la resta de los dos; es exactamente la suma de los netos de las filas
     * @param turno el arqueo en vivo del turno, cuando se pidio por caja y cajero; nulo si no
     */
    public record Avance(
            String desde,
            String hasta,
            String aLaFecha,
            List<FilaDeTributo> filas,
            ImporteActualizado cobrado,
            ImporteActualizado anulado,
            ImporteActualizado neto,
            @Nullable AvanceDelTurno turno) {

        public static Avance de(
                ConsultaDeRecaudacion.Avance avance, @Nullable AvanceDelTurno turno) {
            return new Avance(
                    avance.criterio().desde().toString(),
                    avance.criterio().hasta().toString(),
                    avance.aLaFecha().toString(),
                    avance.filas().stream()
                            .map(fila -> FilaDeTributo.de(fila, avance.aLaFecha()))
                            .toList(),
                    new ImporteActualizado(avance.totalCobrado(), avance.aLaFecha()),
                    new ImporteActualizado(avance.totalAnulado(), avance.aLaFecha()),
                    new ImporteActualizado(avance.neto(), avance.aLaFecha()),
                    turno);
        }
    }

    /**
     * Una fila del avance.
     *
     * @param tributo el tributo, o el codigo de la tasa
     * @param cobrado lo que sus lineas sumaron, anuladas incluidas
     * @param anulado lo que de eso pertenecia a recibos anulados
     * @param neto la resta
     */
    public record FilaDeTributo(
            String tributo,
            ImporteActualizado cobrado,
            ImporteActualizado anulado,
            ImporteActualizado neto) {

        static FilaDeTributo de(RecaudacionDeTributo fila, java.time.LocalDate aLaFecha) {
            return new FilaDeTributo(
                    fila.tributo(),
                    new ImporteActualizado(fila.cobrado(), aLaFecha),
                    new ImporteActualizado(fila.anulado(), aLaFecha),
                    new ImporteActualizado(fila.neto(), aLaFecha));
        }
    }

    /**
     * La distribucion por area, partida y tributo.
     *
     * <p>{@code netoSinPartida} <b>se publica</b>, y esa es la decision que importa de RF-089: es
     * todo lo tributario, que no tiene area ni partida presupuestal en ningun sitio del esquema. Se
     * expone en vez de esconderse para que quien lea el reporte a la gerencia de administracion vea
     * que la suma de las partidas no es la recaudacion del periodo, y por que. Ver {@link
     * RecaudacionDePartida}.
     *
     * @param desde primer dia del rango, en ISO
     * @param hasta ultimo dia del rango, en ISO
     * @param aLaFecha la fecha a la que se leyo (regla 9)
     * @param filas una por (area, partida, tributo)
     * @param neto el total del periodo: la suma exacta de los netos de las filas
     * @param netoSinPartida cuanto de ese total no se puede imputar a ninguna partida
     */
    public record Distribucion(
            String desde,
            String hasta,
            String aLaFecha,
            List<FilaDePartida> filas,
            ImporteActualizado neto,
            ImporteActualizado netoSinPartida) {

        public static Distribucion de(ConsultaDeRecaudacion.Distribucion distribucion) {
            return new Distribucion(
                    distribucion.criterio().desde().toString(),
                    distribucion.criterio().hasta().toString(),
                    distribucion.aLaFecha().toString(),
                    distribucion.filas().stream()
                            .map(fila -> FilaDePartida.de(fila, distribucion.aLaFecha()))
                            .toList(),
                    new ImporteActualizado(distribucion.neto(), distribucion.aLaFecha()),
                    new ImporteActualizado(distribucion.netoSinPartida(), distribucion.aLaFecha()));
        }
    }

    /**
     * Una fila de la distribucion.
     *
     * <p>{@code area}, {@code areaNombre} y {@code partida} salen <b>nulos</b> en lo tributario, y
     * es deliberado: el dato no existe y no se sustituye por la partida de la caja ni por un
     * «VARIOS». Un {@code null} obliga a la interfaz a decidir que dibuja; un valor inventado se
     * copia a un reporte presupuestal sin que nadie lo note.
     *
     * @param area el codigo del area generadora; nulo en lo tributario
     * @param areaNombre su nombre; nulo en lo tributario
     * @param partida la partida presupuestal; nula en lo tributario
     * @param tributo el tributo, o el codigo de la tasa
     * @param cobrado lo que el grupo sumo, anuladas incluidas
     * @param anulado lo que de eso pertenecia a recibos anulados
     * @param neto la resta
     */
    public record FilaDePartida(
            @Nullable String area,
            @Nullable String areaNombre,
            @Nullable String partida,
            String tributo,
            ImporteActualizado cobrado,
            ImporteActualizado anulado,
            ImporteActualizado neto) {

        static FilaDePartida de(RecaudacionDePartida fila, java.time.LocalDate aLaFecha) {
            return new FilaDePartida(
                    fila.areaCodigo(),
                    fila.areaNombre(),
                    fila.partidaPresupuestal(),
                    fila.tributo(),
                    new ImporteActualizado(fila.cobrado(), aLaFecha),
                    new ImporteActualizado(fila.anulado(), aLaFecha),
                    new ImporteActualizado(fila.neto(), aLaFecha));
        }
    }

    /**
     * El avance en vivo de un turno: lo que el cajero lleva cobrado hoy.
     *
     * @param caja el codigo de la ventanilla
     * @param cajero de quien es el turno
     * @param fecha el dia del turno, en ISO
     * @param estadoDelTurno ABIERTO o CERRADO, derivado de sus movimientos
     * @param arqueo lo cobrado y lo anulado hasta ahora, sin nada declarado todavia
     */
    public record AvanceDelTurno(
            String caja,
            String cajero,
            String fecha,
            String estadoDelTurno,
            ArqueoResource arqueo) {

        public static AvanceDelTurno de(String caja, ConsultaDeRecaudacion.AvanceDelTurno avance) {
            return new AvanceDelTurno(
                    caja,
                    avance.turno().cajero(),
                    avance.turno().fecha().toString(),
                    avance.turno().estado().name(),
                    ArqueoResource.de(avance.arqueo()));
        }
    }
}
