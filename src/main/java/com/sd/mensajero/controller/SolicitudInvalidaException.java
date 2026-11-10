package com.sd.mensajero.controller;

/** Error de datos del cliente; su mensaje se le regresa tal cual como {@link com.sd.mensajero.model.Aviso}. */
public class SolicitudInvalidaException extends RuntimeException {

    public SolicitudInvalidaException(String mensaje) {
        super(mensaje);
    }
}
