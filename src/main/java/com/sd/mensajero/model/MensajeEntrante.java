package com.sd.mensajero.model;

/**
 * Lo que el cliente manda a {@code /app/chat}.
 * Si {@code destinatario} viene vacío o nulo, el mensaje es para todos.
 */
public record MensajeEntrante(String destinatario, String contenido) {
}
