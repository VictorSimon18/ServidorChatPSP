package servidor;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Proceso hijo independiente que recibe líneas por stdin (pipe)
 * y las escribe en data/historial.txt.
 * Se lanza desde ServidorChat mediante ProcessBuilder.
 */
public class ProcesoHistorial {

    public static void main(String[] args) {
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        File archivoHistorial = new File("data/historial.txt");
        System.err.println("[Historial] Proceso iniciado. Escribiendo en: " + archivoHistorial.getAbsolutePath());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new FileWriter(archivoHistorial, true))) {

            String linea;
            while ((linea = reader.readLine()) != null) {
                writer.write(linea);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            System.err.println("[Historial] Error: " + e.getMessage());
        }

        System.err.println("[Historial] Proceso finalizado.");
    }
}
