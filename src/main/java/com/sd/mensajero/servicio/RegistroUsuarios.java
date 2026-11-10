package com.sd.mensajero.servicio;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lista de usuarios conectados, indexada por id de sesión WebSocket.
 * Los nombres son únicos sin distinguir mayúsculas ("Ana" y "ana" son el mismo).
 */
@Component
public class RegistroUsuarios {

    private final Map<String, String> nombrePorSesion = new HashMap<>();

    /** Registra el nombre para la sesión. Devuelve {@code false} si el nombre ya está en uso. */
    public synchronized boolean registrar(String sesionId, String nombre) {
        if (buscar(nombre).isPresent()) {
            return false;
        }
        nombrePorSesion.put(sesionId, nombre);
        return true;
    }

    /** Quita la sesión y devuelve el nombre que tenía (vacío si la sesión no estaba registrada). */
    public synchronized Optional<String> eliminar(String sesionId) {
        return Optional.ofNullable(nombrePorSesion.remove(sesionId));
    }

    /** Busca un usuario conectado y devuelve su nombre tal como se registró. */
    public synchronized Optional<String> buscar(String nombre) {
        if (nombre == null) {
            return Optional.empty();
        }
        String buscado = nombre.trim();
        return nombrePorSesion.values().stream()
                .filter(n -> n.equalsIgnoreCase(buscado))
                .findFirst();
    }

    /** Nombres de los usuarios conectados en orden alfabético. */
    public synchronized List<String> nombres() {
        return nombrePorSesion.values().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
