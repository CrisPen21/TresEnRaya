import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Cliente de consola del tres en raya.
 *
 * Uso: java Client [host] [puerto] [nombre]
 *      (por defecto localhost, 5050, y el nombre se pregunta si no se indica)
 *
 * Dos hilos: uno lee los mensajes del servidor y los pinta; el hilo principal
 * lee el teclado y envía las órdenes. Así el jugador ve las jugadas del rival
 * en cuanto llegan, aunque esté esperando su turno.
 */
public class Client {

    private static volatile char myMark = '?';
    private static volatile boolean running = true;

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = 5050;
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Puerto no válido: " + args[1]);
                return;
            }
        }

        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));
        String name;
        if (args.length > 2) {
            name = args[2];
        } else {
            System.out.print("Tu nombre: ");
            name = keyboard.readLine();
            if (name == null || name.isBlank()) {
                name = "jugador";
            }
        }

        Socket socket;
        try {
            socket = new Socket(host, port);
        } catch (IOException e) {
            System.err.println("No se pudo conectar con " + host + ":" + port + " (" + e.getMessage() + ")");
            return;
        }

        try (socket) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            out.println("PLAY " + name);

            Thread reader = new Thread(() -> readServer(in), "lector-servidor");
            reader.setDaemon(true);
            reader.start();

            // Bucle de teclado.
            String line;
            while (running && (line = keyboard.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equalsIgnoreCase("q")) {
                    running = false; // salida voluntaria: no avisar de "servidor cerrado"
                    out.println("BYE");
                    break;
                } else if (line.equalsIgnoreCase("r")) {
                    out.println("REMATCH");
                } else if (line.matches("\\d\\s*[, ]\\s*\\d")) {
                    out.println("MOVE " + line.replaceAll("\\s*[, ]\\s*", ","));
                } else {
                    System.out.println("Comandos: fila,col (ej. 2,2) | r = revancha | q = salir");
                }
            }
        }
        System.out.println("Desconectado.");
    }

    /** Hilo lector: interpreta cada mensaje del servidor. */
    private static void readServer(BufferedReader in) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                handleMessage(line);
            }
        } catch (IOException e) {
            // conexión cerrada
        }
        if (running) {
            System.out.println("El servidor ha cerrado la conexión.");
        }
        System.exit(0); // el hilo principal puede estar bloqueado leyendo el teclado
    }

    private static void handleMessage(String line) {
        String[] p = line.split(" ", 4);
        switch (p[0]) {
            case "FULL":
                System.out.println("Partida llena: no puedes entrar.");
                running = false;
                break;
            case "WAIT":
                System.out.println("Esperando al otro jugador...");
                break;
            case "START":
                myMark = p[1].charAt(0);
                System.out.println("\n=== Empieza la partida === Eres '" + myMark + "' y juegas contra " + (p.length > 2 ? p[2] : "?"));
                break;
            case "OK":
                printBoard(p[1]);
                break;
            case "TURN":
                if (p[1].charAt(0) == myMark) {
                    System.out.println("Tu turno. Escribe fila,col (1-3), p. ej. 2,2:");
                } else {
                    System.out.println("Turno del rival ('" + p[1] + "')...");
                }
                break;
            case "DENIED":
                System.out.println("Jugada rechazada: " + line.substring(Math.min(7, line.length())));
                break;
            case "END":
                if (p[1].equals("DRAW")) {
                    System.out.println("\n*** Empate ***");
                } else {
                    boolean won = p[2].charAt(0) == myMark;
                    boolean abandon = line.endsWith(" ABANDONO");
                    if (abandon) {
                        System.out.println("\n*** El rival ha abandonado: ganas por abandono ***");
                    } else {
                        System.out.println(won ? "\n*** ¡Has ganado! ***" : "\n*** Has perdido (gana " + p[3] + ") ***");
                    }
                }
                System.out.println("Escribe 'r' para pedir revancha o 'q' para salir.");
                break;
            case "REMATCH":
                System.out.println("El rival propone revancha. Escribe 'r' para aceptar o 'q' para salir.");
                break;
            case "LEFT":
                System.out.println("El rival se ha ido. Escribe 'q' para salir.");
                break;
            case "ERROR":
                System.out.println("Error del servidor: " + line.substring(Math.min(6, line.length())));
                break;
            default:
                System.out.println("Mensaje desconocido: " + line);
        }
    }

    private static void printBoard(String b) {
        StringBuilder sb = new StringBuilder("\n    1   2   3\n");
        for (int r = 0; r < 3; r++) {
            sb.append(' ').append(r + 1).append("  ");
            for (int c = 0; c < 3; c++) {
                char ch = b.charAt(r * 3 + c);
                sb.append(ch == '.' ? ' ' : ch);
                if (c < 2) {
                    sb.append(" | ");
                }
            }
            sb.append('\n');
            if (r < 2) {
                sb.append("   ---+---+---\n");
            }
        }
        System.out.println(sb);
    }
}