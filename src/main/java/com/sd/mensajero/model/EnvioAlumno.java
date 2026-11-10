package com.sd.mensajero.model;

import java.time.Instant;

/**
 * Alumno que el servidor entrega a los clientes, junto con quién lo envió y a quién.
 *
 * @param destinatario {@code null} cuando se envió a todos
 */
public record EnvioAlumno(String remitente, String destinatario, Alumno alumno, String fecha) {

    public static EnvioAlumno de(String remitente, String destinatario, Alumno alumno) {
        return new EnvioAlumno(remitente, destinatario, alumno, Instant.now().toString());
    }
}
