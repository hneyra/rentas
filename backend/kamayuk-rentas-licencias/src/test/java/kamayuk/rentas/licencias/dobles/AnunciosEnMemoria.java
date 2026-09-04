package kamayuk.rentas.licencias.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.licencias.dominio.Anuncio;
import kamayuk.rentas.licencias.dominio.AnuncioRepository;
import kamayuk.rentas.licencias.dominio.CriterioDeAnuncios;
import kamayuk.rentas.licencias.dominio.ResumenDelPadron;

/**
 * Un {@link AnuncioRepository} en memoria (#51).
 *
 * <p><b>Impone la unicidad del numero y la de la clave de idempotencia</b>, que es lo que hace que
 * la traduccion del 409 y la del 200 del reenvio tengan algo que traducir, y lleva su propio
 * correlativo para que dos registros seguidos no compartan numero.
 *
 * <p>Lo que <b>no</b> imita: el {@code REVOKE UPDATE} de V45, la carrera de diez peticiones
 * simultaneas y RLS. Eso solo lo puede demostrar PostgreSQL, y lo hace {@code
 * AnunciosYPropagandaJdbcTest}.
 */
public final class AnunciosEnMemoria implements AnuncioRepository {

    private final List<Anuncio> anuncios = new ArrayList<>();
    private final Map<Integer, Long> correlativos = new HashMap<>();
    private long siguienteId = 1;

    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        long siguiente = correlativos.getOrDefault(ejercicio.valor(), 0L) + 1;
        correlativos.put(ejercicio.valor(), siguiente);
        return siguiente;
    }

    @Override
    public Anuncio autorizar(Anuncio anuncio) {
        if (porNumero(anuncio.numero()).isPresent()) {
            throw new NumeroDuplicado(
                    "Ya existe la autorizacion de anuncio " + anuncio.numero(),
                    new IllegalStateException("anuncio_numero_uq"));
        }
        String clave = anuncio.claveIdempotencia();
        if (clave != null && porClaveDeIdempotencia(clave).isPresent()) {
            throw new ClaveRepetida(
                    "Ya se registro una autorizacion con esa clave de idempotencia",
                    new IllegalStateException("anuncio_idempotencia_uq"));
        }
        Anuncio conId =
                new Anuncio(
                        siguienteId++,
                        anuncio.numero(),
                        anuncio.contribuyenteId(),
                        anuncio.predioId(),
                        anuncio.licenciaId(),
                        anuncio.clase(),
                        anuncio.tipo(),
                        anuncio.emplazamiento(),
                        anuncio.forma(),
                        anuncio.denominacion(),
                        anuncio.ubicacion(),
                        anuncio.area(),
                        anuncio.lados(),
                        anuncio.cantidad(),
                        anuncio.fechaAutorizacion(),
                        anuncio.vigenciaHasta(),
                        anuncio.expediente(),
                        anuncio.fechaExpediente(),
                        anuncio.claveIdempotencia(),
                        anuncio.registradoEn(),
                        "prueba",
                        anuncio.observacion());
        anuncios.add(conId);
        return conId;
    }

    @Override
    public Optional<Anuncio> porNumero(String numero) {
        String buscado = numero == null ? "" : numero.strip();
        return anuncios.stream().filter(a -> a.numero().equals(buscado)).findFirst();
    }

    @Override
    public Optional<Anuncio> porClaveDeIdempotencia(String clave) {
        return anuncios.stream().filter(a -> clave.equals(a.claveIdempotencia())).findFirst();
    }

    @Override
    public Pagina<Anuncio> buscar(CriterioDeAnuncios criterio, Paginacion paginacion) {
        List<Anuncio> filtrados = filtrar(criterio);
        return Pagina.de(filtrados, paginacion, filtrados.size());
    }

    @Override
    public ResumenDelPadron resumen(CriterioDeAnuncios criterio, LocalDate aLaFecha) {
        // El doble suma sobre TODAS las filas del criterio, igual que el agregado del motor: si
        // sumara la pagina, la prueba de que el resumen no es la pagina no probaria nada.
        return new ResumenDelPadron(filtrar(criterio).size(), Dinero.CERO);
    }

    private List<Anuncio> filtrar(CriterioDeAnuncios criterio) {
        return anuncios.stream()
                .filter(a -> criterio.numero() == null || a.numero().equals(criterio.numero()))
                .filter(a -> criterio.clase() == null || a.clase() == criterio.clase())
                .filter(
                        a ->
                                criterio.expediente() == null
                                        || (a.expediente() != null
                                                && a.expediente()
                                                        .startsWith(criterio.expediente())))
                .filter(
                        a ->
                                criterio.direccion() == null
                                        || a.ubicacion().startsWith(criterio.direccion()))
                .filter(
                        a ->
                                criterio.desde() == null
                                        || !a.fechaAutorizacion().isBefore(criterio.desde()))
                .filter(
                        a ->
                                criterio.hasta() == null
                                        || !a.fechaAutorizacion().isAfter(criterio.hasta()))
                .filter(
                        a ->
                                criterio.contribuyentes() == null
                                        || criterio.contribuyentes().contains(a.contribuyenteId()))
                .toList();
    }
}
