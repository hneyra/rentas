package kamayuk.rentas.autorizacion;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara que una operacion solo la llama <b>un sistema</b>, con su cuenta de servicio, y cual
 * (#429).
 *
 * <pre>
 *   &#64;RequiereIdentidadDeServicio(sistema = "caja")
 *   &#64;RequiereAcceso(acceso = "caja_tributaria", privilegio = Privilegio.REGISTRO)
 *   &#64;PostMapping
 *   public ResponseEntity&lt;PagoResource&gt; recibir(...) { … }
 * </pre>
 *
 * <h2>Es otra pregunta, y por eso no es un permiso mas del catalogo</h2>
 *
 * <p>{@link RequiereAcceso} pregunta <b>que</b> puede hacer quien llama, y lo contesta la matriz de
 * permisos que {@code identidad} administra. Esta pregunta <b>quien</b> es. Un acceso propio
 * —«buzon_de_pagos», por ejemplo— no la contestaria: seria una fila mas de la matriz, y {@code
 * identidad} la concederia a quien el administrador decidiera, persona incluida. Lo que no puede
 * conceder nadie es haber pedido el token con la clave del cliente confidencial de ese sistema.
 *
 * <p>El guardia la comprueba <b>antes</b> del permiso, y el permiso sigue siendo la segunda
 * condicion: una cuenta de servicio de la caja sin {@code caja_tributaria} sigue siendo un 403. Lo
 * que se lee es el {@code azp} del token ya validado —lo que Spring Security comprobo
 * criptograficamente—, con la forma de {@link ClienteDeServicio}; si no es el del sistema
 * declarado, la respuesta es {@code SIN_IDENTIDAD_DE_SERVICIO}, que se arregla pidiendo otro token,
 * y no {@code SIN_PRIVILEGIO}, que se arreglaria concediendo lo que no arregla nada.
 *
 * <h2>El ubigeo no se compara con el de la municipalidad del contexto, y se dice</h2>
 *
 * <p>La municipalidad de la peticion la fija {@code TenantContextFilter} desde el claim {@code
 * municipalidad_id} del <b>mismo</b> token firmado (ADR-0005), asi que comparar el ubigeo del
 * {@code azp} con el de esa fila seria comprobar el token consigo mismo por un camino mas largo, y
 * anadiria una segunda fuente de la verdad del inquilino. Es la decision de {@code Consumidor} en
 * {@code identidad}, por el mismo motivo.
 *
 * <p>Se admite en la clase y en el metodo, y el metodo gana, igual que {@link RequiereAcceso}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiereIdentidadDeServicio {

    /**
     * El sistema cuya cuenta de servicio puede llamar: el {@code <sistema>} de {@code
     * kamayuk-<sistema>-servicio-<ubigeo>}.
     */
    String sistema();
}
