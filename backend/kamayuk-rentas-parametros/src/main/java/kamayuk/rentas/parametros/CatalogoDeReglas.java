package kamayuk.rentas.parametros;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Las reglas disponibles, con sus versiones.
 *
 * <p>Guarda por separado las que operan sobre una partida y las que agregan el conjunto de predios
 * del contribuyente, porque el motor las aplica en dos fases distintas.
 *
 * <p><b>Lo que este catalogo no hace es ordenar.</b> El orden de aplicacion lo deduce el motor de
 * las dependencias que cada regla declara, no de la posicion en una lista. Es la diferencia con la
 * version anterior: alli el orden era el de registro, y bastaba registrar mal para calcular mal sin
 * ningun error visible.
 */
public final class CatalogoDeReglas {

    private final List<ReglaTributaria> reglas;
    private final List<ReglaDeAgregacion> agregaciones;

    private CatalogoDeReglas(List<ReglaTributaria> reglas, List<ReglaDeAgregacion> agregaciones) {
        this.reglas = reglas;
        this.agregaciones = agregaciones;
    }

    public static CatalogoDeReglas vacio() {
        return new CatalogoDeReglas(List.of(), List.of());
    }

    public CatalogoDeReglas con(ReglaTributaria regla) {
        Objects.requireNonNull(regla, "No se registra una regla nula");
        for (ReglaTributaria existente : reglas) {
            if (existente.identificador().equals(regla.identificador())
                    && existente.vigencia().seSolapaCon(regla.vigencia())) {
                throw new VigenciasQueSeSolapan(regla.identificador());
            }
        }
        List<ReglaTributaria> ampliado = new ArrayList<>(reglas);
        ampliado.add(regla);
        return new CatalogoDeReglas(List.copyOf(ampliado), agregaciones);
    }

    public CatalogoDeReglas con(ReglaDeAgregacion regla) {
        Objects.requireNonNull(regla, "No se registra una regla nula");
        for (ReglaDeAgregacion existente : agregaciones) {
            if (existente.identificador().equals(regla.identificador())
                    && existente.vigencia().seSolapaCon(regla.vigencia())) {
                throw new VigenciasQueSeSolapan(regla.identificador());
            }
        }
        List<ReglaDeAgregacion> ampliado = new ArrayList<>(agregaciones);
        ampliado.add(regla);
        return new CatalogoDeReglas(reglas, List.copyOf(ampliado));
    }

    /** Las reglas por partida que rigen ese ejercicio. Sin orden: lo decide el motor. */
    public List<ReglaTributaria> vigentesEn(Ejercicio ejercicio) {
        Objects.requireNonNull(ejercicio, "Resolver las reglas exige el ejercicio (ARQ-09 §1.3)");
        List<ReglaTributaria> vigentes = new ArrayList<>();
        for (ReglaTributaria regla : reglas) {
            if (regla.vigencia().rigeEn(ejercicio)) {
                vigentes.add(regla);
            }
        }
        return List.copyOf(vigentes);
    }

    /** Las reglas de agregacion que rigen ese ejercicio. */
    public List<ReglaDeAgregacion> agregacionesVigentesEn(Ejercicio ejercicio) {
        Objects.requireNonNull(ejercicio, "Resolver las reglas exige el ejercicio (ARQ-09 §1.3)");
        List<ReglaDeAgregacion> vigentes = new ArrayList<>();
        for (ReglaDeAgregacion regla : agregaciones) {
            if (regla.vigencia().rigeEn(ejercicio)) {
                vigentes.add(regla);
            }
        }
        return List.copyOf(vigentes);
    }

    public boolean estaVacio() {
        return reglas.isEmpty() && agregaciones.isEmpty();
    }

    /**
     * Dos implementaciones de la misma regla vigentes a la vez. Una implementacion que ya se uso en
     * una emision no se modifica: se crea otra con su rango, y los rangos no se pisan.
     */
    public static final class VigenciasQueSeSolapan extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        VigenciasQueSeSolapan(IdentificadorDeRegla identificador) {
            super(
                    "Ya hay una version de "
                            + identificador
                            + " vigente en ese rango de ejercicios. Con dos, el importe dependeria"
                            + " de cual se elija, y recalcular el pasado dejaria de ser reproducible"
                            + " (ARQ-09 §1.3)");
        }
    }
}
