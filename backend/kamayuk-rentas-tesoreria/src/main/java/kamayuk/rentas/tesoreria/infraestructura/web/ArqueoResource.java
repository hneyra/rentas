package kamayuk.rentas.tesoreria.infraestructura.web;

import java.util.List;
import kamayuk.rentas.tesoreria.dominio.ArqueoDelTurno;
import kamayuk.rentas.tesoreria.dominio.LineaDeArqueo;
import kamayuk.rentas.web.ImporteActualizado;

/**
 * El arqueo de un turno, tal como sale por HTTP (#36, RF-087).
 *
 * <p>Todo importe viaja como {@link ImporteActualizado}: la cifra y la fecha a la que corresponde,
 * juntas y sin poder separarse (regla 9, RNF-075). Un arqueo sin su fecha no se puede conciliar con
 * el deposito del dia siguiente.
 *
 * <p><b>Las cifras no se recomponen aqui.</b> {@code neto} y {@code diferencia} salen de {@link
 * ArqueoDelTurno}, que las calcula de sus lineas; la interfaz no resta nada. Es la misma regla que
 * el resto de la cola: una cifra recompuesta en el cliente es una cifra que puede discrepar de la
 * que se archivo.
 *
 * <h2>Lo que el prototipo dibuja y aqui no esta</h2>
 *
 * <p><b>{@code turno} (MAÑANA / TARDE / CONTINUO)</b>: no existe como dato. {@code cierre_uq} (V3)
 * hace unico el turno por (caja, cajero, fecha) y no hay columna que lo parta en dos, asi que un
 * cajero tiene un turno al dia por ventanilla. Publicar «CONTINUO» fijo seria inventar un campo que
 * despues alguien filtraria.
 *
 * <p><b>{@code horaDeApertura} y {@code horaDeCierre}</b>: la apertura si consta —{@code
 * cierre_caja.fecha_apertura} (V29)— y viaja en {@code aperturaEn}; la hora de cierre es {@code
 * registradoEn} de esta acta. La pantalla las dibuja como texto libre y aqui salen en ISO.
 *
 * @param turnoId el turno arqueado
 * @param fecha el dia del turno, en ISO
 * @param recibosEmitidos cuantos recibos emitio
 * @param recibosAnulados cuantos de ellos se anularon
 * @param cobrado lo que entro, con su fecha
 * @param anulado lo que las anulaciones sacaron, con su fecha
 * @param neto lo cobrado menos lo anulado
 * @param declarado lo que el cajero conto en el cajon
 * @param diferencia lo declarado menos el neto; negativo si falta dinero
 * @param cuadra si la diferencia es cero
 * @param lineas el arqueo medio de pago por medio de pago
 */
public record ArqueoResource(
        long turnoId,
        String fecha,
        int recibosEmitidos,
        int recibosAnulados,
        ImporteActualizado cobrado,
        ImporteActualizado anulado,
        ImporteActualizado neto,
        ImporteActualizado declarado,
        ImporteActualizado diferencia,
        boolean cuadra,
        List<LineaResource> lineas) {

    public static ArqueoResource de(ArqueoDelTurno arqueo) {
        return new ArqueoResource(
                arqueo.turnoId(),
                arqueo.aLaFecha().toString(),
                arqueo.recibosEmitidos(),
                arqueo.recibosAnulados(),
                new ImporteActualizado(arqueo.totalCobrado(), arqueo.aLaFecha()),
                new ImporteActualizado(arqueo.totalAnulado(), arqueo.aLaFecha()),
                new ImporteActualizado(arqueo.neto(), arqueo.aLaFecha()),
                new ImporteActualizado(arqueo.totalDeclarado(), arqueo.aLaFecha()),
                new ImporteActualizado(arqueo.diferencia(), arqueo.aLaFecha()),
                arqueo.cuadra(),
                arqueo.lineas().stream().map(linea -> LineaResource.de(linea, arqueo)).toList());
    }

    /**
     * Una fila del arqueo.
     *
     * <p>Las cinco cifras van cada una con su fecha. Es repetitivo a proposito: la alternativa —«la
     * fecha esta arriba»— es exactamente como una cifra acaba impresa sin ella el dia que alguien
     * reutiliza esta fila en otra pantalla.
     *
     * @param formaDePago con que se pago
     * @param cobrado lo que ese medio movio
     * @param anulado lo que sus anulaciones devolvieron
     * @param neto la resta de los dos
     * @param declarado lo que el cajero conto de ese medio
     * @param diferencia lo declarado menos el neto
     */
    public record LineaResource(
            String formaDePago,
            ImporteActualizado cobrado,
            ImporteActualizado anulado,
            ImporteActualizado neto,
            ImporteActualizado declarado,
            ImporteActualizado diferencia) {

        static LineaResource de(LineaDeArqueo linea, ArqueoDelTurno arqueo) {
            return new LineaResource(
                    linea.formaDePago().name(),
                    new ImporteActualizado(linea.cobrado(), arqueo.aLaFecha()),
                    new ImporteActualizado(linea.anulado(), arqueo.aLaFecha()),
                    new ImporteActualizado(linea.neto(), arqueo.aLaFecha()),
                    new ImporteActualizado(linea.declarado(), arqueo.aLaFecha()),
                    new ImporteActualizado(linea.diferencia(), arqueo.aLaFecha()));
        }
    }
}
