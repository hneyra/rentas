package kamayuk.rentas.catastro.aplicacion;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.catastro.TransferenciaDeFiscalizacion;
import kamayuk.rentas.catastro.VersionTransferida;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.FichaCatastralRepository;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La implementacion del unico puerto de escritura que {@code catastro} publica para {@code
 * fiscalizacion} (#52, RF-054).
 *
 * <p><b>No versiona por su cuenta.</b> Delega en {@link ActualizarFichaCatastral}, que es el unico
 * sitio del sistema donde una ficha se copia, se cierra y se abre. Escribir aqui un segundo camino
 * —aunque fuera identico el primer dia— habria producido dos ordenes de cerrar-y-abrir que un dia
 * divergen, y el que divergiera seria el que menos se usa: precisamente este.
 *
 * <p>Lo que si hace este servicio es lo que el puerto promete y el caso de uso no: leer la version
 * vigente <b>antes</b> de tocarla, para poder devolver de que version a cual fue y con que
 * superficie y uso. Sin esa lectura previa, quien transfiere no podria imprimir en la resolucion la
 * diferencia entre lo que constaba y lo que queda inscrito.
 *
 * <p>Sin {@code @Transactional} propio a proposito: la transaccion es la de la transferencia
 * entera, que abre {@code fiscalizacion} (AC 4 de #52, RF-133). Si este metodo abriera la suya con
 * {@code REQUIRES_NEW}, la ficha nueva sobreviviria al fallo de un paso posterior, que es
 * exactamente lo que la atomicidad prohibe. {@code Propagation.MANDATORY} lo hace explicito: sin
 * una transaccion ya abierta, esto falla en vez de escribir suelto.
 */
@Service
public class TransferenciaDeFiscalizacionCatastro implements TransferenciaDeFiscalizacion {

    /** La ficha que la fiscalizacion predial contrasta es la unica (V1, #18). */
    private static final TipoFicha TIPO = TipoFicha.UNICA;

    private final FichaCatastralRepository repositorio;
    private final ActualizarFichaCatastral actualizacion;

    public TransferenciaDeFiscalizacionCatastro(
            FichaCatastralRepository repositorio, ActualizarFichaCatastral actualizacion) {
        this.repositorio = repositorio;
        this.actualizacion = actualizacion;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public VersionTransferida inscribirLoHallado(
            long predioId,
            LocalDate desde,
            String documentoOrigen,
            @Nullable AreaM2 areaHallada,
            @Nullable String usoHallado,
            Observacion observacion) {

        FichaCatastral anterior =
                repositorio
                        .vigenteA(predioId, TIPO, desde)
                        .orElseThrow(() -> new SinFichaQueVersionar(predioId, desde));

        FichaCatastral nueva =
                actualizacion.actualizarEstructura(
                        predioId,
                        TIPO,
                        desde,
                        OrigenDeLaFicha.FISCALIZACION,
                        documentoOrigen,
                        areaHallada,
                        usoHallado,
                        observacion);

        return new VersionTransferida(
                Objects.requireNonNull(anterior.id(), "Una version inscrita tiene identificador"),
                Objects.requireNonNull(nueva.id(), "Una version recien inscrita vuelve con su id"),
                nueva.version(),
                anterior.areaTerreno(),
                nueva.areaTerreno(),
                anterior.uso(),
                nueva.uso());
    }
}
