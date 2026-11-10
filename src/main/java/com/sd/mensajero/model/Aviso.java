package com.sd.mensajero.model;

/** Aviso de error que el servidor manda sólo al cliente que lo provocó. */
public record Aviso(String texto) {
}
