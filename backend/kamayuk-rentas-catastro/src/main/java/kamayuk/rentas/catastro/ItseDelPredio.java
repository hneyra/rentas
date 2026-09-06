package kamayuk.rentas.catastro;

import java.time.LocalDate;
import java.util.List;

/**
 * El ITSE de un predio a una fecha: los certificados que ese dia estaban vigentes.
 *
 * <p><b>{@code aLaFecha} es la mitad de la respuesta</b> (regla 9): lo que se contesta no es «tiene
 * ITSE» sino «tenia ITSE el 12 de marzo de 2026». Un certificado vence, y sin la fecha dentro nadie
 * puede decir despues a que dia correspondia la lista.
 *
 * <p>La lista vacia dice que ese dia no habia ninguno. Es una respuesta y no una ausencia.
 */
public record ItseDelPredio(long predioId, LocalDate aLaFecha, List<CertificadoItse> vigentes) {}
