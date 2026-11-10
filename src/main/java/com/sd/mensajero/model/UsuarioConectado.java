package com.sd.mensajero.model;

import java.security.Principal;

/** Identidad asociada a una sesión WebSocket; Spring la usa para entregar mensajes privados. */
public record UsuarioConectado(String nombre) implements Principal {

    @Override
    public String getName() {
        return nombre;
    }
}
