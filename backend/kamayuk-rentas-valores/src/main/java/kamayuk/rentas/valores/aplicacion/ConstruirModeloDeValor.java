package kamayuk.rentas.valores.aplicacion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import kamayuk.rentas.valores.dominio.ValorRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Arma el {@link ModeloDeDocumento} de un valor ya emitido, para su impresion -individual o masiva-
 * en cualquiera de los tres formatos (RF-092, RF-132, #38).
 *
 * <p>Es un {@code @Service} propio -no un metodo de {@link ImprimirCorridaMasiva}- por la misma
 * razon que separa a {@link ProcesarItemMasivo} de {@link GenerarCorridaMasiva}: {@link #de} tiene
 * que abrir su propia transaccion corta para leer el valor, su detalle y el nombre del
 * contribuyente, y eso exige que la llamada pase por el proxy de Spring -nunca una auto-invocacion
 * dentro de la clase que arma el lote entero-.
 *
 * <p><b>No se recalcula nada.</b> Cada cifra que aparece en el documento es la que {@link Valor}
 * congelo al emitirse: el mismo valor impreso hoy o dentro de dos anios trae el mismo desglose (AC
 * de #37), y por eso {@link ModeloDeDocumento#aLaFecha} es {@link Valor#proyectadoA}, nunca la
 * fecha en que se imprime.
 */
@Service
public class ConstruirModeloDeValor {

    private final ValorRepository repositorio;
    private final DirectorioDeContribuyentes contribuyentes;

    public ConstruirModeloDeValor(
            ValorRepository repositorio, DirectorioDeContribuyentes contribuyentes) {
        this.repositorio = repositorio;
        this.contribuyentes = contribuyentes;
    }

    @Transactional(readOnly = true)
    public ModeloDeDocumento de(long valorId) {
        Valor valor =
                repositorio
                        .porId(valorId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "El valor "
                                                        + valorId
                                                        + " no existe: la corrida lo"
                                                        + " marco GENERADO con un valorId que ya no"
                                                        + " se encuentra"));
        List<ValorDetalle> detalle = repositorio.detalleDe(valorId);
        ResumenDeContribuyente contribuyente =
                contribuyentes.porIds(Set.of(valor.contribuyenteId())).get(valor.contribuyenteId());

        return construir(valor, detalle, contribuyente);
    }

    private static ModeloDeDocumento construir(
            Valor valor,
            List<ValorDetalle> detalle,
            @Nullable ResumenDeContribuyente contribuyente) {
        List<Campo> cabecera =
                List.of(
                        Campo.de("Numero", valor.numero()),
                        Campo.de("Contribuyente", nombreDe(contribuyente)),
                        Campo.de("Codigo de contribuyente", codigoDe(contribuyente)),
                        Campo.de("Base legal", valor.baseLegal()),
                        Campo.de("Ejercicio", String.valueOf(valor.ejercicio().valor())));

        List<List<String>> filas = new ArrayList<>(detalle.size());
        for (ValorDetalle item : detalle) {
            filas.add(
                    List.of(
                            item.tributo(),
                            String.valueOf(item.ejercicio().valor()),
                            item.insoluto().valor().toPlainString(),
                            item.reajuste().valor().toPlainString(),
                            item.interes().valor().toPlainString(),
                            item.gasto().valor().toPlainString()));
        }
        Tabla tabla =
                Tabla.de(
                        "Deuda formalizada",
                        List.of("Tributo", "Ejercicio", "Insoluto", "Reajuste", "Interes", "Gasto"),
                        filas);

        return ModeloDeDocumento.de(tituloDe(valor), valor.proyectadoA(), cabecera, List.of(tabla))
                .con(List.of("Total: S/ " + valor.total().valor().toPlainString()));
    }

    private static String tituloDe(Valor valor) {
        return switch (valor.tipo()) {
            case ORDEN_DE_PAGO -> "ORDEN DE PAGO N.° " + valor.numero();
            case RESOLUCION_DE_DETERMINACION -> "RESOLUCION DE DETERMINACION N.° " + valor.numero();
            case RESOLUCION_DE_MULTA -> "RESOLUCION DE MULTA N.° " + valor.numero();
        };
    }

    private static String nombreDe(@Nullable ResumenDeContribuyente c) {
        return c == null ? "" : c.nombre();
    }

    private static String codigoDe(@Nullable ResumenDeContribuyente c) {
        return c == null ? "" : c.codigo();
    }
}
