package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Las caracteristicas del proyecto del FUE: que se construye (#48, RF-113).
 *
 * <h2>Ninguna cifra de dinero, y es el punto del issue</h2>
 *
 * <p>El «Valor de obra (S/)» que la pantalla muestra <b>no</b> es un campo de este objeto ni una
 * columna de su tabla. Es el resultado de valorizar {@link EstructuraDelProyecto} contra el cuadro
 * de valores unitarios de #17, y guardarlo aqui lo duplicaria: el AC 2 de #48 dice literalmente
 * «usa las tablas de #17 y <b>no</b> duplica cifras». Ver {@link ValorizacionDeObra}.
 *
 * <p>Se versiona igual que el terreno (V43 §8).
 *
 * @param id nulo mientras no se haya guardado
 * @param fueId el expediente al que pertenece
 * @param version 1 la primera vez que se completa la seccion
 * @param uso el uso de la edificacion declarado
 * @param numeroPisos cuantos pisos
 * @param areaTechada el area techada total
 * @param areaLibre el area libre; opcional
 * @param estacionamientos cuantos estacionamientos; opcional
 * @param plazoEnMeses el plazo de ejecucion declarado; opcional
 * @param registradoEn el instante de registro, del reloj inyectado
 * @param usuarioRegistro quien lo registro
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record ProyectoDelFue(
        @Nullable Long id,
        long fueId,
        int version,
        String uso,
        int numeroPisos,
        AreaM2 areaTechada,
        @Nullable AreaM2 areaLibre,
        @Nullable Integer estacionamientos,
        @Nullable Integer plazoEnMeses,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    public ProyectoDelFue {
        Objects.requireNonNull(uso, "El proyecto declara el uso de la edificacion");
        Objects.requireNonNull(areaTechada, "El proyecto declara su area techada");
        Objects.requireNonNull(registradoEn, "La seccion dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        uso = uso.strip().toUpperCase(java.util.Locale.ROOT);
        if (uso.isEmpty()) {
            throw new IllegalArgumentException("El uso de la edificacion no puede estar vacio");
        }
        if (version < 1) {
            throw new IllegalArgumentException(
                    "La primera version de una seccion es la 1; llego " + version);
        }
        if (numeroPisos < 1) {
            throw new IllegalArgumentException(
                    "Una edificacion de cero pisos no es una edificacion: llego " + numeroPisos);
        }
        if (estacionamientos != null && estacionamientos < 0) {
            throw new IllegalArgumentException(
                    "Un numero negativo de estacionamientos no significa nada");
        }
        if (plazoEnMeses != null && plazoEnMeses <= 0) {
            throw new IllegalArgumentException(
                    "Un plazo de ejecucion de cero meses no autoriza ninguna obra");
        }
    }
}
