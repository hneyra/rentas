package kamayuk.rentas.parametros.aplicacion;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Vigencia;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.dominio.ParametroTributario;
import kamayuk.rentas.parametros.dominio.ParametrosRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lee conjuntos <b>sellados</b>, y solo sellados. Un conjunto ABIERTO no se lee aunque tenga todos
 * sus parametros: recalcular 2027 en 2037 debe dar el mismo centimo, y para eso lo leido tiene que
 * ser inmutable.
 */
@Service
public class LectorDeParametrosSellados implements LectorDeParametros {

    private final ParametrosRepository repositorio;

    public LectorDeParametrosSellados(ParametrosRepository repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    @Transactional(readOnly = true)
    public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
        ConjuntoDeParametros conjunto =
                repositorio
                        .selladoVigenteDe(ejercicio)
                        .orElseThrow(() -> new EjercicioSinSellar(ejercicio));
        return armar(conjunto);
    }

    @Override
    @Transactional(readOnly = true)
    public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
        ConjuntoDeParametros conjunto =
                repositorio
                        .selladoVigenteDe(ejercicio)
                        .orElseThrow(() -> new EjercicioSinSellar(ejercicio));
        return IdentificadorDeConjunto.de(
                Objects.requireNonNull(conjunto.id(), "Un conjunto leido de la base tiene id"));
    }

    @Override
    @Transactional(readOnly = true)
    public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
        ConjuntoDeParametros conjunto =
                repositorio
                        .selladoPorId(identificador.valor())
                        .orElseThrow(() -> new ConjuntoNoSellado(identificador));
        return armar(conjunto);
    }

    /**
     * Arma el juego a partir de las filas del conjunto, <b>resolviendo la vigencia</b> (#659).
     *
     * <h2>Por que hace falta resolver, y que pasaba sin ello</h2>
     *
     * <p>Un conjunto sellado contiene, a proposito, <b>el historico</b> de una llave: {@code
     * parametros-2026.csv} publica cinco filas de {@code UIT} —2022 a 2026— porque ese archivo es a
     * la vez el derivado publicable y el manifiesto de composicion, y el historico de la cifra es
     * parte de lo que se publica. Hasta #659 esta lectura las metia las cinco en un mapa llaveado
     * por {@code tipo:clave} y el {@code put} se quedaba <b>en silencio</b> con la ultima; cual era
     * la ultima lo decidia el {@code ORDER BY p.tipo, p.clave} del repositorio, que para las cinco
     * UIT <b>no es un orden total</b>. El ejercicio 2026 se determinaba con la UIT de 2022: 234,00
     * donde deben ser 180,00, un 30 % de mas sobre todo el padron y sin ningun error de por medio.
     *
     * <h2>Con que fecha se resuelve</h2>
     *
     * <p>Con <b>el ejercicio del conjunto</b>, nunca con el reloj: sobrevive la fila cuya vigencia
     * se solapa con el año del ejercicio. Es lo que hace que recalcular una determinacion de 2024
     * en 2036 siga dando la UIT de 2024 (regla 6), y es la unica fecha disponible aqui — un
     * conjunto sellado no sabe que dia se le pregunta.
     *
     * <p>Se compara contra el <b>año entero</b> y no contra el 1 de enero. Anclarlo al primer dia
     * seria correcto para la UIT y para todo lo que rige el ejercicio completo, y dejaria fuera lo
     * que rige <b>parte</b> de el: una campaña de beneficio de marzo a junio (D-02b, #72) es del
     * ejercicio 2026 y desapareceria del conjunto sin que nada lo dijera.
     *
     * <h2>Y si despues de resolver siguen sobrando</h2>
     *
     * <p>Se falla nombrando la llave y las dos vigencias, en vez de elegir. Dos filas de la misma
     * llave vigentes en el mismo ejercicio son una contradiccion dentro de un conjunto que ya esta
     * sellado —o sea inmutable, y que solo se arregla sellando otra version—, y elegir una de las
     * dos es exactamente el defecto que este metodo acaba de cerrar. Hoy no ocurre: de las 33 filas
     * publicadas, solo {@code UIT} tiene mas de una vigencia y las cinco son de ejercicios
     * distintos.
     */
    private ParametrosSellados armar(ConjuntoDeParametros conjunto) {
        Ejercicio ejercicio = conjunto.ejercicio();
        ParametrosSellados.Constructor constructor =
                ParametrosSellados.de(ejercicio, conjunto.version());

        Map<String, ParametroTributario> queRige = new LinkedHashMap<>();
        for (ParametroTributario parametro :
                repositorio.parametrosDe(Objects.requireNonNull(conjunto.id()))) {
            if (!rigeEn(parametro, ejercicio)) {
                continue;
            }
            ParametroTributario yaHabia = queRige.putIfAbsent(llave(parametro), parametro);
            if (yaHabia != null) {
                throw new VigenciasQueSeSolapan(
                        llave(parametro), ejercicio, yaHabia, parametro, conjunto.version());
            }
        }

        for (ParametroTributario parametro : queRige.values()) {
            parametro
                    .numero()
                    .ifPresent(v -> constructor.numero(parametro.tipo(), parametro.clave(), v));
            parametro
                    .texto()
                    .ifPresent(v -> constructor.texto(parametro.tipo(), parametro.clave(), v));
        }

        return constructor.construir();
    }

    /** La vigencia de la fila se solapa con el año del ejercicio. */
    private static boolean rigeEn(ParametroTributario parametro, Ejercicio ejercicio) {
        Vigencia vigencia = parametro.vigencia();
        LocalDate primero = ejercicio.primerDia();
        LocalDate ultimo = ejercicio.ultimoDia();
        return (vigencia.desde() == null || !vigencia.desde().isAfter(ultimo))
                && (vigencia.hasta() == null || !vigencia.hasta().isBefore(primero));
    }

    /** La misma llave que {@link ParametrosSellados} usa por dentro. */
    private static String llave(ParametroTributario parametro) {
        String clave = parametro.clave();
        return clave == null || clave.isBlank() ? parametro.tipo() : parametro.tipo() + ":" + clave;
    }

    /**
     * El conjunto sellado trae dos filas de la misma llave vigentes en su ejercicio.
     *
     * <p>No se elige ninguna: elegir en silencio es el defecto de #659, y aqui las dos son
     * defendibles. Lo que hay que hacer es sellar otra version del conjunto sin la fila que sobra.
     */
    public static final class VigenciasQueSeSolapan extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        VigenciasQueSeSolapan(
                String llave,
                Ejercicio ejercicio,
                ParametroTributario una,
                ParametroTributario otra,
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

        private static String rango(ParametroTributario parametro) {
            Vigencia vigencia = parametro.vigencia();
            return (vigencia.desde() == null ? "siempre" : vigencia.desde().toString())
                    + " a "
                    + (vigencia.hasta() == null ? "indefinido" : vigencia.hasta().toString());
        }
    }
}
