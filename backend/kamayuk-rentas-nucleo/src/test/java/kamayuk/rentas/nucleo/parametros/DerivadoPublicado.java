package kamayuk.rentas.nucleo.parametros;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.CorpusDeNormativa;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;

/**
 * El derivado publicable del corpus, leido como lo lee el proceso que publica (#188, #192, #395).
 *
 * <p>Existe para que las dos pruebas que lo consumen —la del cuadro del predial y la del corpus de
 * casos de NEG-05— lo lean <b>de la misma manera</b>. Con una copia en cada una, el dia que el
 * formato del archivo cambiara una de las dos seguiria verde leyendo mal, y el sintoma seria una
 * cifra distinta sin ningun error.
 *
 * <p>Se lee por <b>posicion</b>, igual que {@code FilaPublicable} y que {@code
 * ImportarParametrosDelConjunto}: {@code tipo, clave, vigencia_desde, vigencia_hasta,
 * valor_numerico}. Las demas columnas —la transcripcion verbatim, el documento fuente y las dos
 * firmas— las comprueba {@code docs/10-negocio/verificar-publicacion.mjs} contra el corpus en cada
 * PR, y aqui sobran.
 */
public final class DerivadoPublicado {

    /** El derivado que este repositorio versiona, tal como se despliega. */
    public static final Path ARCHIVO = CorpusDeNormativa.derivadoPublicable();

    /**
     * La escala que ADR-0018 fija para todo importe que se asienta: la de {@code dinero
     * numeric(15,2)}. Es un dato de la prueba —el que {@code normativa} publicara—, no del codigo
     * que se prueba.
     */
    private static final int ESCALA_DE_LA_ADR_0018 = 2;

    private DerivadoPublicado() {}

    /**
     * Las filas numericas que rigen ese ejercicio, indexadas por {@code tipo|clave}.
     *
     * <p>La clave vacia es la forma del tipo con un solo valor —la UIT—, y se conserva como cadena
     * vacia a proposito: es la misma distincion que {@code IS NOT DISTINCT FROM} sostiene en la
     * base, y confundirla con «no esta» es el defecto que #247 §2 destapo.
     *
     * <p>Desde #376 la lectura vive en {@link CorpusDeNormativa#numerosVigentesEn(int)}, porque
     * tambien la necesita {@code fiscalizacion}: aqui solo se delega.
     */
    public static Map<String, String> numerosVigentesEn(int ejercicio) {
        return CorpusDeNormativa.numerosVigentesEn(ejercicio);
    }

    /** Un lector de un conjunto compuesto con <b>todo</b> lo que el derivado publica. */
    public static LectorDeParametros conjuntoDelEjercicio(Ejercicio ejercicio) {
        return new DelDerivado(ejercicio, numerosVigentesEn(ejercicio.valor()), Map.of());
    }

    /**
     * Todo lo que el derivado publica <b>mas</b> las filas {@code REDONDEO:‹punto›} de esos puntos,
     * con la escala 2 de ADR-0018 y el modo que se pida (#378).
     *
     * <p>Existe porque el derivado <b>no trae ninguna</b> fila de redondeo: ADR-0018 de {@code
     * normativa} ya decidio el valor —escala 2, {@code HALF_UP}, al cierre de cada regla— y
     * publicarlo es trabajo de {@code normativa}, no de este repositorio. Mientras tanto, una
     * determinacion que cierra su regla en uno de esos puntos falla con el conjunto real, que es lo
     * que el ADR manda; las pruebas que necesitan <b>llegar</b> a la cifra anaden aqui la fila que
     * falta, y el modo entra como argumento para que una prueba pueda sellar otro y ver que la
     * cifra cambia (#378).
     */
    public static LectorDeParametros conjuntoDelEjercicioConRedondeo(
            Ejercicio ejercicio, RoundingMode modo, PuntoDeRedondeo... puntos) {
        Map<PuntoDeRedondeo, PoliticaDeRedondeo> redondeos = new EnumMap<>(PuntoDeRedondeo.class);
        for (PuntoDeRedondeo punto : puntos) {
            redondeos.put(punto, new PoliticaDeRedondeo(ESCALA_DE_LA_ADR_0018, modo));
        }
        return new DelDerivado(ejercicio, numerosVigentesEn(ejercicio.valor()), redondeos);
    }

    /**
     * Un lector de un conjunto compuesto con <b>solo esas llaves</b>, con los valores que el
     * derivado publica para ellas.
     *
     * <p>Es lo que permite que un caso del corpus declare que parametros necesita y que declararlos
     * de menos falle en vez de pasar en verde con lo que otro caso dejo cargado.
     *
     * @throws IllegalArgumentException si alguna llave no esta publicada: comparar contra un valor
     *     que el corpus no respalda no probaria nada
     */
    public static LectorDeParametros conjuntoCon(Ejercicio ejercicio, Set<String> llaves) {
        Map<String, String> publicados = numerosVigentesEn(ejercicio.valor());
        Map<String, String> elegidos = new LinkedHashMap<>();
        for (String llave : llaves) {
            // Solo el PRIMER dos puntos separa el tipo de la clave: la clave puede llevar los
            // suyos, como `PLAZO:PRESCRIPCION-DECLARACION_PRESENTADA` lleva un guion.
            int corte = llave.indexOf(':');
            String normalizada =
                    corte < 0
                            ? llave + "|"
                            : llave.substring(0, corte) + "|" + llave.substring(corte + 1);
            String valor = publicados.get(normalizada);
            if (valor == null) {
                throw new IllegalArgumentException(
                        "El derivado publicable no trae el parametro "
                                + llave
                                + ", asi que no hay con que comparar al centimo: lo que falta es"
                                + " transcribirlo y firmarlo (ADR-0007), no rellenarlo aqui");
            }
            elegidos.put(normalizada, valor);
        }
        return new DelDerivado(ejercicio, elegidos, Map.of());
    }

    /**
     * Un lector de un conjunto compuesto con todo lo que el derivado publica <b>mas</b> esas filas,
     * indexadas por {@code tipo|clave}; lo publicado gana a lo añadido.
     *
     * <p>Solo lo usa {@link ElVehicularQuePlaneaNormativa} (#499): es la forma de probar hoy el
     * conjunto que {@code normativa} sellara el dia que publique el vehicular, sin escribir en
     * {@code normativa} y sin sembrar a mano el resto del conjunto.
     *
     * <p>Los puntos de {@code conRedondeo} entran con la politica que ADR-0018 ya decidio —escala
     * 2, {@code HALF_UP}— y que {@code normativa} tampoco publica todavia (#378).
     */
    static LectorDeParametros conjuntoDelEjercicioMas(
            Ejercicio ejercicio, Map<String, String> filas, PuntoDeRedondeo... conRedondeo) {
        Map<String, String> compuesto = new LinkedHashMap<>(numerosVigentesEn(ejercicio.valor()));
        filas.forEach(compuesto::putIfAbsent);
        Map<PuntoDeRedondeo, PoliticaDeRedondeo> redondeos = new EnumMap<>(PuntoDeRedondeo.class);
        for (PuntoDeRedondeo punto : conRedondeo) {
            redondeos.put(
                    punto, new PoliticaDeRedondeo(ESCALA_DE_LA_ADR_0018, RoundingMode.HALF_UP));
        }
        return new DelDerivado(ejercicio, compuesto, redondeos);
    }

    /**
     * Un conjunto sellado compuesto con lo que el derivado publica, y las filas de redondeo que la
     * prueba anada (#378): {@code valor_numerico} la escala y {@code valor_texto} el modo, en la
     * misma fila, como las lee {@link PoliticasDeRedondeoSelladas}.
     */
    private record DelDerivado(
            Ejercicio ejercicio,
            Map<String, String> publicados,
            Map<PuntoDeRedondeo, PoliticaDeRedondeo> redondeos)
            implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio delEjercicio) {
            ParametrosSellados.Constructor constructor = ParametrosSellados.de(delEjercicio, 1);
            for (Map.Entry<String, String> fila : publicados.entrySet()) {
                String[] partes = fila.getKey().split("\\|", -1);
                constructor.numero(
                        partes[0],
                        partes[1].isEmpty() ? null : partes[1],
                        ValorNormativo.de(fila.getValue()));
            }
            for (Map.Entry<PuntoDeRedondeo, PoliticaDeRedondeo> punto : redondeos.entrySet()) {
                constructor.numero(
                        PoliticasDeRedondeoSelladas.TIPO,
                        punto.getKey().name(),
                        new ValorNormativo(BigDecimal.valueOf(punto.getValue().escala())));
                constructor.texto(
                        PoliticasDeRedondeoSelladas.TIPO,
                        punto.getKey().name(),
                        punto.getValue().modo().name());
            }
            return constructor.construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(ejercicio);
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio delEjercicio) {
            return IdentificadorDeConjunto.de(1L);
        }
    }
}
