package kamayuk.rentas.fiscalizacion.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.catastro.TransferenciaDeFiscalizacion;
import kamayuk.rentas.catastro.VersionTransferida;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * El puerto de escritura hacia {@code catastro}, en memoria.
 *
 * <p>Reproduce lo que importa del versionado real y nada mas: la version anterior <b>no se
 * modifica</b>, la nueva sube uno y hereda lo que no se corrige. Lo que no reproduce es el cierre
 * de la vigencia ni {@code ficha_vigente_uq}: eso lo demuestra PostgreSQL en {@code
 * TransferenciaJdbcTest}, y reescribirlo aqui seria probar la copia.
 *
 * <p>Guarda ademas <b>cuantas veces se le llamo</b>, que es lo que hace comprobable el AC 6 sin
 * base de datos: transferir dos veces la misma liquidacion no puede llegar aqui dos veces.
 */
public final class PadronQueVersiona implements TransferenciaDeFiscalizacion {

    /** Lo que hay inscrito de cada predio: version, area y uso. */
    private final Map<Long, Inscrito> padron = new HashMap<>();

    private final List<String> documentosDeOrigen = new ArrayList<>();

    private long siguienteFicha = 100;

    /** Siembra la ficha vigente de un predio. */
    public PadronQueVersiona con(long predioId, String area, String uso) {
        padron.put(predioId, new Inscrito(siguienteFicha++, 1, AreaM2.de(area), uso));
        return this;
    }

    @Override
    public VersionTransferida inscribirLoHallado(
            long predioId,
            LocalDate desde,
            String documentoOrigen,
            @Nullable AreaM2 areaHallada,
            @Nullable String usoHallado,
            Observacion observacion) {

        Inscrito anterior = padron.get(predioId);
        if (anterior == null) {
            throw new SinFichaQueVersionar(predioId, desde);
        }
        Inscrito nuevo =
                new Inscrito(
                        siguienteFicha++,
                        anterior.version() + 1,
                        areaHallada == null ? anterior.area() : areaHallada,
                        usoHallado == null ? anterior.uso() : usoHallado);
        padron.put(predioId, nuevo);
        documentosDeOrigen.add(documentoOrigen);

        return new VersionTransferida(
                anterior.fichaId(),
                nuevo.fichaId(),
                nuevo.version(),
                anterior.area(),
                nuevo.area(),
                anterior.uso(),
                nuevo.uso());
    }

    /** Cuantas veces se escribio en el padron. */
    public int escrituras() {
        return documentosDeOrigen.size();
    }

    /** Con que documento se sustento cada escritura, en orden. */
    public List<String> documentosDeOrigen() {
        return List.copyOf(documentosDeOrigen);
    }

    /** Lo que hay inscrito hoy de un predio. */
    public Inscrito vigenteDe(long predioId) {
        return padron.get(predioId);
    }

    /** Una version inscrita. */
    public record Inscrito(long fichaId, int version, AreaM2 area, String uso) {}
}
