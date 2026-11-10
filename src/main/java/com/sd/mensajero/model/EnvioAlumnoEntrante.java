package com.sd.mensajero.model;

/**
 * Lo que el cliente manda a {@code /app/alumno}.
 * Si {@code destinatario} viene vacío o nulo, el alumno se envía a todos.
 */
public record EnvioAlumnoEntrante(String destinatario, Alumno alumno) {
}
