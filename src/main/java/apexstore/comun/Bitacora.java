package apexstore.comun;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Bitácora mínima por consola (suficiente para evidenciar el flujo en la demo). */
public final class Bitacora {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Bitacora() { }

    public static void info(String componente, String mensaje) {
        System.out.println(LocalTime.now().format(HORA) + " [" + componente + "] " + mensaje);
    }
}
