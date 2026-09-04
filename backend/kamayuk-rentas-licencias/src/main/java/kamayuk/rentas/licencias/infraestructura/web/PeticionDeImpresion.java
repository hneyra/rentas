package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que se manda para volver a sacar un certificado ya emitido (#54, AC 2; RF-132).
 *
 * <p><b>Lleva observacion, y no es burocracia</b>: reimprimir <b>escribe</b> —incrementa el
 * contador de reimpresiones del documento y deja su fila de auditoria—, y toda modificacion de
 * datos exige la observacion del usuario (regla 10, RNF-052). Sin ella, un certificado se podria
 * volver a entregar sin que nadie tuviera que decir por que, y el papel duplicado circula igual que
 * el original.
 *
 * <p>El {@code formato} decide en cual de los tres sale, y <b>no tiene que ser el de la
 * emision</b>: quien recibio un PDF tiene derecho a pedir la misma emision en hoja de calculo
 * (RF-132). Lo que no cambia es el contenido, que se vuelve a dibujar de los datos guardados.
 */
public record PeticionDeImpresion(@Nullable String formato, @Nullable String observacion) {}
