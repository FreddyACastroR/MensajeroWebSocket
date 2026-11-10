package com.sd.mensajero.model;

/**
 * Entidad de negocio que viaja por el mensajero en formato JSON, por ejemplo:
 * <pre>
 * {"matricula":"00000123456","nombre":"Ana López","carrera":"Ingeniería en Software","semestre":5,"promedio":92.5}
 * </pre>
 */
public record Alumno(String matricula, String nombre, String carrera, Integer semestre, Double promedio) {
}
