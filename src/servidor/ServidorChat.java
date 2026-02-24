package servidor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Semaphore;

public class ServidorChat {

    private static final int PUERTO_DEFAULT = 12345;
    private static final int MAX_CONEXIONES = 50;

    public static void main(String[] args) {
        int puerto = PUERTO_DEFAULT;
        if (args.length > 0) {
            try {
                puerto = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Puerto inválido. Usando " + PUERTO_DEFAULT);
            }
        }

        // Lanzar proceso hijo para historial
        Process procesoHistorial = lanzarProcesoHistorial();
        if (procesoHistorial != null) {
            OutputStream pipeAlHistorial = procesoHistorial.getOutputStream();
            GestorMensajes.getInstancia().setPipeHistorial(pipeAlHistorial);
            System.out.println("Proceso de historial iniciado correctamente.");
        } else {
            System.err.println("No se pudo iniciar el proceso de historial. Continuando sin historial.");
        }

        Semaphore semaforo = new Semaphore(MAX_CONEXIONES);

        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            System.out.println("=== Servidor de Chat iniciado en puerto " + puerto + " ===");
            System.out.println("Esperando conexiones... (máx " + MAX_CONEXIONES + ")");

            while (true) {
                Socket clienteSocket = serverSocket.accept();
                System.out.println("Nueva conexión desde: " + clienteSocket.getInetAddress());

                if (semaforo.tryAcquire()) {
                    ManejadorCliente manejador = new ManejadorCliente(clienteSocket) {
                        @Override
                        public void run() {
                            try {
                                super.run();
                            } finally {
                                semaforo.release();
                            }
                        }
                    };
                    manejador.setDaemon(true);
                    manejador.start();
                } else {
                    System.out.println("Conexión rechazada: límite alcanzado.");
                    clienteSocket.close();
                }
            }
        } catch (IOException e) {
            System.err.println("Error en el servidor: " + e.getMessage());
        } finally {
            if (procesoHistorial != null) {
                procesoHistorial.destroy();
            }
        }
    }

    private static Process lanzarProcesoHistorial() {
        try {
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + java.io.File.separator + "bin" + java.io.File.separator + "java";
            String classpath = System.getProperty("java.class.path");

            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", classpath, "servidor.ProcesoHistorial");
            pb.redirectErrorStream(false);
            pb.directory(new java.io.File(System.getProperty("user.dir")));

            return pb.start();
        } catch (IOException e) {
            System.err.println("Error lanzando proceso historial: " + e.getMessage());
            return null;
        }
    }
}
