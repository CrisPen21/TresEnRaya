import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Servidor árbitro del tres en raya. Gestiona una única partida de dos jugadores.
 *
 * Uso: java Server [puerto]      (por defecto 5050)
 *
 * Concurrencia: cada cliente se atiende en su propio hilo, pero TODO el estado
 * de la partida vive en la clase Game, cuyos métodos son synchronized sobre una
 * única cerradura. Así, dos MOVE simultáneos se serializan y nunca se corrompe
 * el tablero ni se aceptan dos jugadas en el mismo turno.
 */
public class Server {

    private static final int DEFAULT_PORT = 5050;
    private static final int HANDSHAKE_TIMEOUT_MS = 15000;

    private final Game game = new Game();

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Puerto no válido: " + args[0]);
                return;
            }
        }
        new Server().run(port);
    }

    private void run(int port) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            log("Servidor escuchando en el puerto " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                Thread t = new Thread(() -> handleClient(socket), "cliente-" + socket.getRemoteSocketAddress());
                t.setDaemon(true);
                t.start();
            }
        }
    }

    /** Hilo de atención a un cliente: registro, bucle de lectura y limpieza. */
    private void handleClient(Socket socket) {
        Player player = null;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // Registro: el primer mensaje debe ser PLAY <nombre>. Con timeout para no
            // dejar hilos colgados con conexiones que no dicen nada.
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            String line = in.readLine();
            if (line == null) {
                return;
            }
            String[] parts = line.trim().split("\\s+", 2);
            if (!parts[0].equalsIgnoreCase("PLAY") || parts.length < 2 || parts[1].isBlank()) {
                out.println("ERROR Se esperaba: PLAY <nombre>");
                return;
            }
            socket.setSoTimeout(0);

            player = new Player(sanitize(parts[1]), socket, out);
            if (!game.join(player)) {
                // Se leyó antes el PLAY del cliente para que el cierre sea limpio
                // (sin datos pendientes de leer no hay RST que borre el FULL).
                out.println("FULL");
                log("Conexión rechazada (partida llena): " + socket.getRemoteSocketAddress());
                try {
                    socket.shutdownOutput();
                } catch (IOException ignored) {
                }
                return;
            }

            while ((line = in.readLine()) != null) {
                if (!game.handle(player, line.trim())) {
                    break; // BYE
                }
            }
        } catch (IOException e) {
            // Caída brusca del cliente: se trata igual que una desconexión (finally).
        } finally {
            if (player != null && player.joined) {
                game.leave(player);
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Nombre de una sola palabra, para que el protocolo (separado por espacios) no sea ambiguo. */
    private static String sanitize(String raw) {
        String n = raw.trim().replaceAll("\\s+", "_");
        return n.length() > 20 ? n.substring(0, 20) : n;
    }

    static void log(String msg) {
        System.out.println("[servidor] " + msg);
    }

    // ------------------------------------------------------------------ Player

    static class Player {
        final String name;
        final Socket socket;
        private final PrintWriter out;
        char mark;
        boolean joined;

        Player(String name, Socket socket, PrintWriter out) {
            this.name = name;
            this.socket = socket;
            this.out = out;
        }

        /** Dos hilos distintos pueden escribir al mismo jugador: se serializa. */
        synchronized void send(String msg) {
            out.println(msg);
        }
    }

    // -------------------------------------------------------------------- Game

    static class Game {
        enum State { WAITING, PLAYING, FINISHED }

        private final List<Player> players = new ArrayList<>();
        private final Set<Player> rematchVotes = new HashSet<>();
        private final char[] board = new char[9];
        private State state = State.WAITING;
        private char turn = 'X';

        Game() {
            resetBoard();
        }

        private void resetBoard() {
            java.util.Arrays.fill(board, '.');
            turn = 'X';
        }

        /** Intenta sentar al jugador. False si la partida ya está completa. */
        synchronized boolean join(Player p) {
            if (players.size() >= 2 || state != State.WAITING) {
                return false;
            }
            p.joined = true;
            players.add(p);
            if (players.size() == 1) {
                p.mark = 'X';
                p.send("WAIT");
                log(p.name + " conectado como X, esperando rival");
            } else {
                p.mark = 'O';
                log(p.name + " conectado como O, empieza la partida");
                startGame();
            }
            return true;
        }

        private void startGame() {
            resetBoard();
            rematchVotes.clear();
            state = State.PLAYING;
            for (Player p : players) {
                p.send("START " + p.mark + " " + opponentOf(p).name);
            }
            broadcast("TURN X");
        }

        /** Procesa una línea del cliente. Devuelve false si debe cerrarse la conexión. */
        synchronized boolean handle(Player p, String line) {
            if (line.isEmpty()) {
                return true;
            }
            String[] parts = line.split("\\s+", 2);
            String cmd = parts[0].toUpperCase();
            String arg = parts.length > 1 ? parts[1].trim() : "";
            switch (cmd) {
                case "MOVE":
                    move(p, arg);
                    return true;
                case "REMATCH":
                    rematch(p);
                    return true;
                case "BYE":
                    return false;
                default:
                    p.send("ERROR Comando desconocido: " + cmd);
                    return true;
            }
        }

        private void move(Player p, String arg) {
            if (state == State.WAITING) {
                p.send("DENIED La partida aun no ha empezado");
                return;
            }
            if (state == State.FINISHED) {
                p.send("DENIED Partida terminada");
                return;
            }
            if (p.mark != turn) {
                p.send("DENIED No es tu turno");
                return;
            }
            int row;
            int col;
            try {
                String[] rc = arg.split("\\s*,\\s*");
                if (rc.length != 2) {
                    throw new NumberFormatException();
                }
                row = Integer.parseInt(rc[0].trim());
                col = Integer.parseInt(rc[1].trim());
            } catch (NumberFormatException e) {
                p.send("DENIED Formato invalido, usa MOVE fila,col (1-3)");
                return;
            }
            if (row < 1 || row > 3 || col < 1 || col > 3) {
                p.send("DENIED Casilla fuera del tablero (fila y columna de 1 a 3)");
                return;
            }
            int idx = (row - 1) * 3 + (col - 1);
            if (board[idx] != '.') {
                p.send("DENIED Casilla ocupada");
                return;
            }

            board[idx] = p.mark;
            log(p.name + " (" + p.mark + ") juega " + row + "," + col);
            broadcast("OK " + new String(board));

            char winner = winner();
            if (winner != 0) {
                state = State.FINISHED;
                broadcast("END WIN " + winner + " " + p.name);
                log("Fin de partida: gana " + p.name);
            } else if (isFull()) {
                state = State.FINISHED;
                broadcast("END DRAW");
                log("Fin de partida: empate");
            } else {
                turn = (turn == 'X') ? 'O' : 'X';
                broadcast("TURN " + turn);
            }
        }

        private void rematch(Player p) {
            if (state != State.FINISHED) {
                p.send("DENIED La revancha solo se pide al terminar la partida");
                return;
            }
            if (players.size() < 2) {
                p.send("DENIED El rival ya no esta conectado");
                return;
            }
            rematchVotes.add(p);
            if (rematchVotes.size() == 2) {
                // Revancha: se intercambian las marcas para alternar quién empieza.
                for (Player q : players) {
                    q.mark = (q.mark == 'X') ? 'O' : 'X';
                }
                log("Revancha aceptada por ambos");
                startGame();
            } else {
                p.send("WAIT");
                opponentOf(p).send("REMATCH");
            }
        }

        /** Se invoca al cerrarse la conexión de un jugador (BYE, caída o error). */
        synchronized void leave(Player p) {
            if (!players.remove(p)) {
                return;
            }
            rematchVotes.remove(p);
            log(p.name + " desconectado");
            if (!players.isEmpty()) {
                Player other = players.get(0);
                if (state == State.PLAYING) {
                    state = State.FINISHED;
                    other.send("END WIN " + other.mark + " " + other.name + " ABANDONO");
                } else if (state == State.FINISHED) {
                    rematchVotes.clear();
                    other.send("LEFT");
                }
            } else {
                // Sala vacía: se reinicia para aceptar una nueva pareja de jugadores.
                state = State.WAITING;
                resetBoard();
                rematchVotes.clear();
                log("Sala vacia, lista para una nueva partida");
            }
        }

        // ------------------------------------------------------------ helpers

        private Player opponentOf(Player p) {
            return players.get(0) == p ? players.get(1) : players.get(0);
        }

        private void broadcast(String msg) {
            for (Player p : players) {
                p.send(msg);
            }
        }

        private boolean isFull() {
            for (char c : board) {
                if (c == '.') {
                    return false;
                }
            }
            return true;
        }

        private static final int[][] LINES = {
                {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
                {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
                {0, 4, 8}, {2, 4, 6}
        };

        /** Devuelve la marca ganadora o 0 si nadie ha hecho tres en línea. */
        private char winner() {
            for (int[] l : LINES) {
                if (board[l[0]] != '.' && board[l[0]] == board[l[1]] && board[l[1]] == board[l[2]]) {
                    return board[l[0]];
                }
            }
            return 0;
        }
    }
}