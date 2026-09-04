package kamayuk.rentas.licencias.dobles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.licencias.dominio.Certificado;
import kamayuk.rentas.licencias.dominio.CertificadoRepository;
import kamayuk.rentas.licencias.dominio.CriterioDeCertificados;
import kamayuk.rentas.licencias.dominio.TipoDeCertificado;

/**
 * Un {@link CertificadoRepository} en memoria.
 *
 * <p><b>Impone la unicidad del numero y la de la clave de idempotencia</b>, y lleva su propio
 * correlativo por tipo: es lo que permite probar la traduccion del 409 y del 200 del reintento sin
 * levantar PostgreSQL. Lo que no imita es la ausencia de {@code UPDATE} de V51 —eso solo lo puede
 * demostrar el motor— ni la serializacion real del contador bajo concurrencia, que verifica {@code
 * CertificadosYPadronesJdbcTest} con diez hilos.
 */
public final class CertificadosEnMemoria implements CertificadoRepository {

    private final List<Certificado> certificados = new ArrayList<>();
    private final Map<String, Long> correlativos = new HashMap<>();
    private long siguienteId = 1;

    @Override
    public long siguienteCorrelativo(TipoDeCertificado tipo, Ejercicio ejercicio) {
        String clave = tipo.name() + "-" + ejercicio.valor();
        long siguiente = correlativos.getOrDefault(clave, 0L) + 1;
        correlativos.put(clave, siguiente);
        return siguiente;
    }

    @Override
    public Certificado emitir(Certificado certificado) {
        if (!certificado.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un certificado ya emitido no se vuelve a insertar: se sustituye emitiendo"
                            + " otro");
        }
        if (porNumero(certificado.numero()).isPresent()) {
            throw new NumeroDuplicado(
                    "Ya existe el certificado " + certificado.numero(),
                    new IllegalStateException("certificado_numero_uq"));
        }
        String clave = certificado.claveIdempotencia();
        if (clave != null && porClaveDeIdempotencia(clave).isPresent()) {
            throw new ClaveRepetida(
                    "Otra peticion con la misma clave de idempotencia ya emitio este certificado",
                    new IllegalStateException("certificado_idempotencia_uq"));
        }
        Certificado conId = certificado.con(siguienteId++);
        certificados.add(conId);
        return conId;
    }

    @Override
    public Optional<Certificado> porNumero(String numero) {
        String buscado = numero == null ? "" : numero.strip();
        return certificados.stream().filter(c -> c.numero().equals(buscado)).findFirst();
    }

    @Override
    public Optional<Certificado> porClaveDeIdempotencia(String clave) {
        String buscada = clave == null ? "" : clave.strip();
        return certificados.stream().filter(c -> buscada.equals(c.claveIdempotencia())).findFirst();
    }

    @Override
    public Pagina<Certificado> buscar(CriterioDeCertificados criterio, Paginacion paginacion) {
        List<Certificado> filtrados =
                certificados.stream()
                        .filter(
                                c ->
                                        criterio.numero() == null
                                                || c.numero().equals(criterio.numero()))
                        .filter(c -> criterio.tipo() == null || c.tipo() == criterio.tipo())
                        .filter(
                                c ->
                                        criterio.codigoPredial() == null
                                                || c.codigoPredial()
                                                        .startsWith(criterio.codigoPredial()))
                        .filter(
                                c ->
                                        criterio.solicitantes() == null
                                                || criterio.solicitantes()
                                                        .contains(c.contribuyenteId()))
                        .toList();
        return Pagina.de(filtrados, paginacion, filtrados.size());
    }
}
