package kamayuk.rentas.catastro.aplicacion;

import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.EstadoPredio;
import kamayuk.rentas.catastro.dominio.Manzana;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.dominio.TipoPredio;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.dominio.ViaRepository;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El predio que entra del plano: <b>el lote y su geometria, sin ficha</b>.
 *
 * <h2>Por que un predio sin ficha, si {@link InscribirFicha} existe</h2>
 *
 * <p>Porque el plano no trae una ficha. Da el lote —su codigo, su ubicacion y su poligono— y no
 * dice ni el area construida, ni el uso, ni las categorias, ni quien es el titular: eso lo levanta
 * un tecnico en campo o lo declara el contribuyente en ventanilla, despues. Obligar a inventar una
 * ficha para poder cargar el plano seria meter en el padron datos que nadie midio.
 *
 * <p>El predio sin ficha no es un accidente: es la <b>cola de saneamiento</b>, y {@code GET
 * /catastro/predios?fichado=false} existe para verla. Antes de #400 no habia forma de encontrarlo,
 * porque la unica consulta transversal listaba fichas.
 *
 * <h2>Sobre un predio que ya existe, el plano SOLO pone geometria</h2>
 *
 * <p>Reimportar el plano sobre un padron ya trabajado es el caso corriente, no el raro. Y ahi el
 * plano <b>no reescribe</b> la direccion, ni la via, ni el lote: esos datos los corrigio alguien en
 * ventanilla con su observacion, y un archivo cartografico que los pisara borraria ese trabajo sin
 * dejar mas rastro que una fila de auditoria por predio. Lo unico que el plano sabe mejor que nadie
 * es el poligono, y es lo unico que escribe.
 *
 * <p>Sobre un predio <b>dado de baja</b> no escribe nada: reactivarlo es otro acto, con su propia
 * observacion, igual que en {@link InscribirFicha}.
 *
 * <p>Ningun metodo recibe el identificador de municipalidad (regla 2).
 */
@Service
public class InscribirPredioDelPlano {

    private final CatastroRepository catastro;
    private final ViaRepository vias;
    private final RegistrarPredio predios;

    public InscribirPredioDelPlano(
            CatastroRepository catastro, ViaRepository vias, RegistrarPredio predios) {
        this.catastro = catastro;
        this.vias = vias;
        this.predios = predios;
    }

    /**
     * Da de alta el predio si no existe, y le asigna su poligono.
     *
     * @return {@code true} si el predio nacio en este acto; {@code false} si ya estaba y solo se le
     *     puso la geometria
     */
    @Transactional
    public boolean inscribir(DatosDelLote lote, Observacion observacion) {
        Optional<Predio> existente = catastro.predioPorCodigo(lote.codigo());

        Predio predio;
        boolean nuevo;
        if (existente.isPresent()) {
            predio = existente.get();
            if (!predio.estaActivo()) {
                throw new InscribirFicha.PredioDadoDeBaja(lote.codigo());
            }
            nuevo = false;
        } else {
            predio = predios.registrar(nuevoPredio(lote), observacion);
            nuevo = true;
        }

        long predioId =
                Objects.requireNonNull(predio.id(), "El predio guardado tiene identificador");
        catastro.asignarGeometria(predioId, lote.geometria());
        return nuevo;
    }

    // ------------------------------------------------------------------

    private Predio nuevoPredio(DatosDelLote lote) {
        Long sectorId = null;
        Long manzanaId = null;
        String codigoDeSector = lote.codigoDeSector();
        String codigoDeManzana = lote.codigoDeManzana();
        if (codigoDeSector != null) {
            Sector sector =
                    catastro.sectorPorCodigo(codigoDeSector)
                            .orElseThrow(
                                    () ->
                                            new InscribirFicha.ReferenciaInexistente(
                                                    "sector", codigoDeSector));
            sectorId = Objects.requireNonNull(sector.id(), "El sector leido tiene identificador");
            if (codigoDeManzana != null) {
                manzanaId = manzanaDe(sectorId, codigoDeManzana);
            }
        } else if (codigoDeManzana != null) {
            throw new IllegalArgumentException(
                    "Se declaro la manzana '"
                            + codigoDeManzana
                            + "' sin su sector: la manzana pertenece a uno");
        }

        Long viaId = null;
        String codigoDeVia = lote.codigoDeVia();
        if (codigoDeVia != null) {
            Via via =
                    vias.findByCodigo(codigoDeVia)
                            .orElseThrow(
                                    () ->
                                            new InscribirFicha.ReferenciaInexistente(
                                                    "via", codigoDeVia));
            viaId = Objects.requireNonNull(via.id(), "La via leida tiene identificador");
        }

        return new Predio(
                null,
                lote.codigo(),
                lote.tipo(),
                viaId,
                lote.numeroMunicipal(),
                lote.direccion(),
                sectorId,
                manzanaId,
                lote.lote(),
                lote.ubigeo(),
                EstadoPredio.ACTIVO);
    }

    private long manzanaDe(long sectorId, String codigo) {
        for (Manzana manzana : catastro.manzanasDe(sectorId)) {
            if (manzana.codigo().equals(codigo)) {
                return Objects.requireNonNull(manzana.id(), "La manzana leida tiene identificador");
            }
        }
        throw new InscribirFicha.ReferenciaInexistente("manzana", codigo);
    }

    /**
     * Un lote del plano. Sector, manzana y via entran por <b>codigo</b>, igual que en {@link
     * InscribirFicha.DatosDelPredio}, y solo se usan si hay que dar de alta el predio.
     *
     * @param geometria el poligono en WKT y WGS84 (ADR-0021). Es obligatorio: un lote del plano sin
     *     poligono no es un lote del plano, y admitirlo sin el convertiria este camino en un alta
     *     masiva de predios vacios por la puerta de atras
     */
    public record DatosDelLote(
            CodigoReferenciaCatastral codigo,
            TipoPredio tipo,
            String direccion,
            @Nullable String codigoDeVia,
            @Nullable String numeroMunicipal,
            @Nullable String codigoDeSector,
            @Nullable String codigoDeManzana,
            @Nullable String lote,
            @Nullable String ubigeo,
            String geometria) {

        public DatosDelLote {
            Objects.requireNonNull(codigo, "El lote necesita su codigo de referencia catastral");
            Objects.requireNonNull(tipo, "El lote necesita su tipo de predio");
            Objects.requireNonNull(direccion, "El lote necesita su direccion");
            if (geometria == null || geometria.isBlank()) {
                throw new IllegalArgumentException(
                        "El lote del plano necesita su geometria: sin ella esto seria un alta"
                                + " masiva de predios vacios");
            }
            geometria = geometria.strip();
        }
    }
}
