package kamayuk.rentas.catastro.prueba;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.catastro.CertificadoItse;
import kamayuk.rentas.catastro.ItseDelPredio;
import kamayuk.rentas.catastro.RiesgoDelPredio;
import kamayuk.rentas.catastro.RiesgoYItseDelPredio;
import kamayuk.rentas.catastro.ZonaDelPredio;
import kamayuk.rentas.catastro.ZonificacionDelPredio;
import kamayuk.rentas.catastro.infraestructura.ClienteHttpDeCatastro;

/**
 * <b>FIXTURE DE PRUEBA</b>: los hechos del territorio de `catastro`#4 y #5, sembrados en memoria.
 *
 * <h2>Lo que NO se siembra, NO CONSTA — y eso es lo que hay hoy</h2>
 *
 * <p>Un predio del que nadie sembro nada contesta {@link ClienteHttpDeCatastro.NoConstaEnCatastro},
 * que es literalmente el estado de todas las instalaciones: <b>no hay ni un poligono cargado en
 * ninguna</b>. Devolver una lista vacia seria la mentira que {@code ZonificacionDelPredio} explica
 * en su javadoc —«una zona nula seria indistinguible de este predio esta en zona nula»— y dejaria a
 * las pruebas midiendo un mundo que no existe.
 *
 * <p>Y por eso el desenlace mas comun de las pruebas que usan esto es que haga falta la
 * autorizacion expresa de #43: no es un defecto del fixture, es el sistema diciendo la verdad.
 */
public final class TerritorioEnMemoria implements ZonificacionDelPredio, RiesgoYItseDelPredio {

    private final Map<Long, ZonaDelPredio> zonas = new LinkedHashMap<>();
    private final Map<Long, Boolean> riesgoNoMitigable = new LinkedHashMap<>();
    private final Map<Long, List<CertificadoItse>> certificados = new LinkedHashMap<>();
    private boolean catastroCaido;

    /** El predio cae en esa zona, aprobada por esa ordenanza. */
    public TerritorioEnMemoria conZona(long predioId, String codigo, String ordenanza) {
        zonas.put(
                predioId,
                new ZonaDelPredio(
                        LocalDate.EPOCH,
                        codigo,
                        "Zona " + codigo,
                        "PDU-DEMO",
                        ordenanza,
                        LocalDate.of(2020, 1, 1),
                        null,
                        List.of()));
        return this;
    }

    /** Si el lote cruza una zona de riesgo no mitigable. Sin sembrar, el riesgo NO CONSTA. */
    public TerritorioEnMemoria conRiesgo(long predioId, boolean hayNoMitigable) {
        riesgoNoMitigable.put(predioId, hayNoMitigable);
        return this;
    }

    /** Los certificados ITSE vigentes de ese predio. La lista vacia SI es un dato aqui. */
    public TerritorioEnMemoria conItse(long predioId, CertificadoItse... vigentes) {
        certificados.put(predioId, List.of(vigentes));
        return this;
    }

    /** Un predio con las tres cosas contestadas y ninguna que se oponga. */
    public TerritorioEnMemoria conTodoEnRegla(long predioId, String zona, String ordenanza) {
        return conZona(predioId, zona, ordenanza).conRiesgo(predioId, false).conItse(predioId);
    }

    /**
     * `catastro` deja de contestar.
     *
     * <p>Es OTRA cosa que «no consta», y las dos tienen que poder medirse por separado: la
     * distincion es la que decide si se abre un local (#43, AC-4).
     */
    public TerritorioEnMemoria caido() {
        this.catastroCaido = true;
        return this;
    }

    // ------------------------------------------------------------------

    @Override
    public ZonaDelPredio zonaDe(long predioId, LocalDate aLaFecha) {
        exigirQueConteste("leer la zona del predio " + predioId);
        ZonaDelPredio zona = zonas.get(predioId);
        if (zona == null) {
            throw new ClienteHttpDeCatastro.NoConstaEnCatastro(
                    "leer la zona del predio " + predioId,
                    "NO_ENCONTRADO",
                    "ningun plan vigente cubre ese predio, o no tiene poligono levantado");
        }
        // La fecha que vuelve es la que se pidio (regla 9): lo que se afirma no es «este predio es
        // RDM» sino «lo era ese dia».
        return new ZonaDelPredio(
                aLaFecha,
                zona.codigo(),
                zona.nombre(),
                zona.plan(),
                zona.ordenanza(),
                zona.vigenciaDesde(),
                zona.vigenciaHasta(),
                zona.parametros());
    }

    @Override
    public RiesgoDelPredio riesgoDe(long predioId, LocalDate aLaFecha) {
        exigirQueConteste("leer el riesgo del predio " + predioId);
        Boolean noMitigable = riesgoNoMitigable.get(predioId);
        if (noMitigable == null) {
            throw new ClienteHttpDeCatastro.NoConstaEnCatastro(
                    "leer el riesgo del predio " + predioId,
                    "VALIDACION",
                    "el predio esta y no tiene poligono: sin el, «cero zonas» se leeria como «no"
                            + " cae en ninguna»");
        }
        return new RiesgoDelPredio(predioId, aLaFecha, noMitigable, List.of(), List.of());
    }

    @Override
    public ItseDelPredio itseVigenteEn(long predioId, LocalDate aLaFecha) {
        exigirQueConteste("leer el ITSE del predio " + predioId);
        List<CertificadoItse> vigentes = certificados.get(predioId);
        if (vigentes == null) {
            throw new ClienteHttpDeCatastro.NoConstaEnCatastro(
                    "leer el ITSE del predio " + predioId,
                    "NO_ENCONTRADO",
                    "ese predio no esta en el padron de esta municipalidad");
        }
        return new ItseDelPredio(predioId, aLaFecha, new ArrayList<>(vigentes));
    }

    private void exigirQueConteste(String que) {
        if (catastroCaido) {
            throw new ClienteHttpDeCatastro.CatastroInalcanzable(
                    "No se puede " + que + ": `catastro` no contesta", null);
        }
    }
}
