package kamayuk.rentas.parametros.aplicacion;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.dominio.CacheDeSnapshots;
import kamayuk.rentas.parametros.dominio.PublicadorDeNormativa;
import kamayuk.rentas.parametros.dominio.SnapshotDeNormativa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * La misma puerta de siempre a los valores normativos, con {@code normativa} detras (ADR-0025 §1).
 *
 * <h2>La firma de {@link LectorDeParametros} no cambia, y eso es el punto</h2>
 *
 * <p>Los seis modulos que la usan —rentas, catastro, tesoreria, valores, coactiva, licencias,
 * sanciones y fiscalizacion— no se enteran de que los parametros dejaron de estar en esta base. Lo
 * que cambia es de donde salen: antes de una tabla local, ahora de la <b>copia local de un conjunto
 * sellado</b> que se descarga una vez y se guarda para siempre.
 *
 * <h2>El reparto que hace posible calcular con `normativa` apagada</h2>
 *
 * <p>Es asimetrico a proposito, y la asimetria <b>es</b> el criterio de aceptacion de ADR-0025:
 *
 * <ul>
 *   <li>{@link #porConjunto} —la lectura del <b>recalculo</b>— <b>nunca</b> llama por red. Parte
 *       del {@code conjuntoId} que la determinacion guardo (ADR-0025 §3) y ese conjunto ya esta en
 *       la cache. Recalcular un ejercicio emitido en 2027 funciona en 2037 con {@code normativa}
 *       apagada, que es lo que la regla 6 exige.
 *   <li>{@link #vigenteEn} y {@link #conjuntoVigenteEn} —«que rige HOY», o sea abrir una corrida
 *       <b>nueva</b>— preguntan primero a {@code normativa}, porque entre dos corridas puede
 *       haberse sellado una version nueva y esta es la unica llamada que se entera.
 * </ul>
 *
 * <h2>Y cuando `normativa` no contesta al resolver «lo vigente»</h2>
 *
 * <p>Se repliega al conjunto <b>cacheado</b> de mayor version de ese ejercicio, y lo <b>dice</b> en
 * el registro con el conjunto y el dia en que se descargo. No se calla, y no es una precaucion
 * decorativa: la diferencia entre «se uso el vigente» y «se uso el ultimo que teniamos» es la
 * diferencia entre una emision correcta y una emision con el arancel anterior, y esa diferencia no
 * se ve en ninguna cifra del recibo. Lo que la hace defendible es que la determinacion <b>guarda el
 * conjunto que uso</b>: sea el vigente o el cacheado, queda escrito cual fue.
 *
 * <p>Y si no hay ninguno cacheado, falla con {@link PublicadorDeNormativa.NormativaInalcanzable} en
 * vez de con {@code EjercicioSinSellar}. Las dos cosas se arreglan de manera distinta —una
 * levantando un despliegue, otra sellando un ejercicio— y decir la segunda cuando pasa la primera
 * manda a quien atiende a buscar donde no es.
 *
 * <h2>Ningun metodo de esta clase abre transaccion, y hace falta que no la abra (#450)</h2>
 *
 * <p>Hasta #450 las cuatro lecturas eran {@code @Transactional(readOnly = true)}, y dentro de
 * {@link #conjuntoVigenteEn} se preguntaba a {@code normativa} por red: la conexion de la base se
 * quedaba tomada mientras {@code normativa} contestaba, con la espera de 5 minutos que se penso
 * para el snapshot. Ahora la pregunta sale sin transaccion —con la espera corta de {@code
 * /conjuntos}—, y lo que si necesita una —leer la copia local, que esta bajo RLS— la abre {@link
 * CopiaLocalDeNormativa}, que es otra clase a proposito: una llamada dentro de esta no pasaria por
 * el proxy.
 *
 * <p><b>Anadir aqui un {@code @Transactional} lo rompe</b>, y no devolviendo otra cosa: dejando
 * otra vez la conexion tomada durante la pregunta. Lo vigilan {@code SinNormativaFronteraTest} (la
 * espera y la segunda conexion) y {@link kamayuk.rentas.plataforma.ViajeDeRed} (la transaccion
 * abierta).
 */
@Service
public class LectorDeParametrosCacheados implements LectorDeParametros {

    private static final Logger REGISTRO =
            LoggerFactory.getLogger(LectorDeParametrosCacheados.class);

    /**
     * El ambito con que este sistema pide los parametros.
     *
     * <p>Los parametros van en los dos (ADR-0024), asi que da igual cual se nombre para leerlos; se
     * nombra {@code OBLIGACION} porque es el ambito de {@code rentas} y porque su snapshot no
     * arrastra los dos cuadros de valuacion, que son decenas de miles de filas que esta lectura no
     * mira.
     */
    static final String AMBITO = "OBLIGACION";

    private final CopiaLocalDeNormativa copia;
    private final PublicadorDeNormativa normativa;
    private final DescargaDeNormativa descarga;

    public LectorDeParametrosCacheados(
            CopiaLocalDeNormativa copia,
            PublicadorDeNormativa normativa,
            DescargaDeNormativa descarga) {
        this.copia = copia;
        this.normativa = normativa;
        this.descarga = descarga;
    }

    @Override
    public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
        return porConjunto(conjuntoVigenteEn(ejercicio));
    }

    @Override
    public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
        long conjunto;
        try {
            conjunto = normativa.conjuntoVigenteEn(ejercicio);
        } catch (PublicadorDeNormativa.NormativaInalcanzable inalcanzable) {
            conjunto = elUltimoQueTeniamos(ejercicio, inalcanzable);
        }
        descarga.asegurarDescargado(conjunto, AMBITO);
        return IdentificadorDeConjunto.de(conjunto);
    }

    /**
     * El recalculo: de la cache y solo de la cache.
     *
     * <p>Si no esta, se intenta descargar —un recalculo de un conjunto que este proceso nunca vio
     * es legitimo, por ejemplo tras restaurar la base—; pero si {@code normativa} tampoco esta, el
     * error dice que no se pudo hablar con el, no que el conjunto no exista.
     */
    @Override
    public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
        long conjunto = identificador.valor();
        descarga.asegurarDescargado(conjunto, AMBITO);
        CopiaLocalDeNormativa.ConjuntoCopiado copiado =
                copia.leer(conjunto).orElseThrow(() -> new ConjuntoNoSellado(identificador));
        return armar(copiado.identidad(), copiado.parametros());
    }

    /**
     * Lo que ya esta descargado para el ejercicio, sin tocar la red (#25, AC-3).
     *
     * <p>Dos {@code SELECT} sobre {@code normativa_conjunto} y ninguna llamada: ver {@link
     * CopiaLocalDeNormativa#loQueYaEstaDescargado}. <b>No pasa por {@link
     * DescargaDeNormativa#asegurarDescargado}</b>, y esa es toda la diferencia: quien pregunta por
     * las senias no necesita el snapshot, asi que exigirle que este completo convertiria una
     * lectura que se puede contestar en una que va a la red y falla.
     */
    @Override
    public Optional<ConjuntoYaDescargado> loQueYaEstaDescargado(Ejercicio ejercicio) {
        return copia.loQueYaEstaDescargado(ejercicio);
    }

    private long elUltimoQueTeniamos(
            Ejercicio ejercicio, PublicadorDeNormativa.NormativaInalcanzable inalcanzable) {
        Optional<Long> cacheado = copia.conjuntoCacheadoDe(ejercicio);
        if (cacheado.isEmpty()) {
            throw inalcanzable;
        }
        long conjunto = cacheado.get();
        REGISTRO.warn(
                "`normativa` no contesta: el ejercicio {} se resuelve con el conjunto {} que ya"
                        + " estaba en la cache. Puede haberse sellado una version mas nueva que aqui no"
                        + " esta (ARQ-09 §3); la determinacion guardara ESTE conjunto, asi que queda"
                        + " escrito con cual se calculo",
                ejercicio,
                conjunto,
                inalcanzable);
        return conjunto;
    }

    /**
     * Arma el juego resolviendo la vigencia contra el ejercicio del conjunto (#659).
     *
     * <p>Es la <b>misma</b> resolucion que hacia {@code LectorDeParametrosSellados} cuando los
     * parametros estaban en esta base, y se queda aqui a proposito: pasarla a {@code normativa}
     * dejaria al servidor decidiendo cual de las cinco UIT rige, que es una decision del calculo y
     * no de la publicacion. El snapshot trae el historico entero y este lado elige.
     *
     * <p>Si despues de resolver siguen sobrando dos filas de la misma llave, se falla nombrandola.
     * Dentro de un conjunto ya sellado son una contradiccion, y elegir una es el defecto que #659
     * cerro.
     */
    private ParametrosSellados armar(
            CacheDeSnapshots.IdentidadDelConjunto identidad,
            java.util.List<SnapshotDeNormativa.Parametro> parametros) {

        Ejercicio ejercicio = identidad.ejercicio();
        ParametrosSellados.Constructor constructor =
                ParametrosSellados.de(ejercicio, identidad.version());

        Map<String, SnapshotDeNormativa.Parametro> queRige = new LinkedHashMap<>();
        for (SnapshotDeNormativa.Parametro parametro : parametros) {
            if (!rigeEn(parametro, ejercicio)) {
                continue;
            }
            SnapshotDeNormativa.Parametro yaHabia =
                    queRige.putIfAbsent(llave(parametro), parametro);
            if (yaHabia != null) {
                throw new VigenciasQueSeSolapan(
                        llave(parametro), ejercicio, yaHabia, parametro, identidad.version());
            }
        }

        for (SnapshotDeNormativa.Parametro parametro : queRige.values()) {
            String numerico = parametro.valorNumerico();
            if (numerico != null) {
                constructor.numero(
                        parametro.tipo(), parametro.clave(), ValorNormativo.de(numerico));
            }
            String texto = parametro.valorTexto();
            if (texto != null) {
                constructor.texto(parametro.tipo(), parametro.clave(), texto);
            }
        }
        return constructor.construir();
    }

    /** La vigencia de la fila se solapa con el año del ejercicio. */
    private static boolean rigeEn(SnapshotDeNormativa.Parametro parametro, Ejercicio ejercicio) {
        LocalDate desde =
                parametro.vigenciaDesde() == null
                        ? null
                        : LocalDate.parse(parametro.vigenciaDesde());
        LocalDate hasta =
                parametro.vigenciaHasta() == null
                        ? null
                        : LocalDate.parse(parametro.vigenciaHasta());
        return (desde == null || !desde.isAfter(ejercicio.ultimoDia()))
                && (hasta == null || !hasta.isBefore(ejercicio.primerDia()));
    }

    private static String llave(SnapshotDeNormativa.Parametro parametro) {
        String clave = parametro.clave();
        return clave == null || clave.isBlank() ? parametro.tipo() : parametro.tipo() + ":" + clave;
    }

    /** El conjunto sellado trae dos filas de la misma llave vigentes en su ejercicio. */
    public static final class VigenciasQueSeSolapan extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        VigenciasQueSeSolapan(
                String llave,
                Ejercicio ejercicio,
                SnapshotDeNormativa.Parametro una,
                SnapshotDeNormativa.Parametro otra,
                int version) {
            super(
                    "El conjunto sellado del ejercicio "
                            + ejercicio
                            + " (version "
                            + version
                            + ") tiene dos filas de "
                            + llave
                            + " vigentes en "
                            + ejercicio
                            + " —"
                            + rango(una)
                            + " y "
                            + rango(otra)
                            + "— y nadie eligio cual rige");
        }

        private static String rango(SnapshotDeNormativa.Parametro parametro) {
            return (parametro.vigenciaDesde() == null ? "siempre" : parametro.vigenciaDesde())
                    + " a "
                    + (parametro.vigenciaHasta() == null
                            ? "indefinido"
                            : parametro.vigenciaHasta());
        }
    }
}
