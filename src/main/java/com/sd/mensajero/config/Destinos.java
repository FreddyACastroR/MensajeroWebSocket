package com.sd.mensajero.config;

/** Destinos STOMP a los que el servidor publica. */
public final class Destinos {

    /** Lista de nombres conectados; se publica cada vez que alguien entra o sale. */
    public static final String TEMA_USUARIOS = "/tema/usuarios";
    /** Mensajes de chat para todos y avisos de sistema (entradas y salidas). */
    public static final String TEMA_CHAT = "/tema/chat";
    /** Alumnos enviados a todos. */
    public static final String TEMA_ALUMNOS = "/tema/alumnos";

    // Colas privadas: el servidor las usa con convertAndSendToUser(nombre, cola, ...)
    // y el cliente se suscribe a "/usuario" + cola (por ejemplo, /usuario/cola/chat).
    public static final String COLA_CHAT = "/cola/chat";
    public static final String COLA_ALUMNOS = "/cola/alumnos";
    public static final String COLA_ERRORES = "/cola/errores";

    private Destinos() {
    }
}
