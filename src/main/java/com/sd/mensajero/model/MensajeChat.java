package com.sd.mensajero.model;

import java.time.Instant;

/**
 * Mensaje de chat que el servidor entrega a los clientes.
 * El remitente lo pone el servidor a partir de la sesión, no el cliente.
 *
 * @param destinatario {@code null} cuando el mensaje es público o de sistema
 * @param fecha        instante ISO-8601 en que el servidor procesó el mensaje
 */
public record MensajeChat(Tipo tipo, String remitente, String destinatario, String contenido, String fecha) {

    public enum Tipo { PUBLICO, PRIVADO, SISTEMA }

    public static MensajeChat publico(String remitente, String contenido) {
        return new MensajeChat(Tipo.PUBLICO, remitente, null, contenido, Instant.now().toString());
    }

    public static MensajeChat privado(String remitente, String destinatario, String contenido) {
        return new MensajeChat(Tipo.PRIVADO, remitente, destinatario, contenido, Instant.now().toString());
    }

    public static MensajeChat sistema(String contenido) {
        return new MensajeChat(Tipo.SISTEMA, null, null, contenido, Instant.now().toString());
    }
}
