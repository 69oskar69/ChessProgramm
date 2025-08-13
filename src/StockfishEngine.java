import java.io.*;
import java.nio.file.*;
import java.util.Objects;

/**
 * Simple helper around a Stockfish process. It can send commands and
 * retrieve best moves for a given position.
 */
public class StockfishEngine {
    private final String path;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;

    /**
     * Uses the path from {@code stockfish.path} if present or defaults to
     * the executable named {@code stockfish}.
     */
    public StockfishEngine() {
        this(loadPathFromConfig());
    }

    public StockfishEngine(String path) {
        this.path = path == null || path.isBlank() ? "stockfish" : path;
    }

    public String getPath() {
        return path;
    }

    /** Returns {@code true} if the underlying process is running. */
    public boolean isRunning() {
        return process != null;
    }

    /** Starts the Stockfish process and performs a basic UCI handshake. */
    public void start() throws IOException {
        if (process != null) return;
        ensureExecutablePresent();
        try {
            ProcessBuilder pb = new ProcessBuilder(path);
            pb.redirectErrorStream(true);
            process = pb.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            sendCommand("uci");
            readUntil("uciok");
            sendCommand("isready");
            readUntil("readyok");
        } catch (IOException ex) {
            throw new IOException("Failed to start Stockfish at '" + path + "'", ex);
        }
    }

    /** Stops the Stockfish process. */
    public void stop() {
        if (process != null) {
            try { sendCommand("quit"); } catch (IOException ignored) {}
            process.destroy();
            process = null;
        }
    }

    /** Sends an arbitrary command to the engine. */
    public void sendCommand(String cmd) throws IOException {
        if (writer == null) throw new IllegalStateException("Engine not started");
        writer.write(cmd);
        writer.newLine();
        writer.flush();
    }

    /** Reads a single line from the engine. */
    public String readResponse() throws IOException {
        if (reader == null) throw new IllegalStateException("Engine not started");
        return reader.readLine();
    }

    private void readUntil(String token) throws IOException {
        String line;
        while ((line = readResponse()) != null) {
            if (line.contains(token)) break;
        }
    }

    /**
     * Queries the engine for the best move in the given position.
     *
     * @param fen   FEN representation of the position
     * @param depth search depth
     * @return best move or {@code null} if none available
     */
    public ChessGUI.Move getBestMove(String fen, int depth) throws IOException {
        sendCommand("position fen " + fen);
        sendCommand("go depth " + depth);
        ChessGUI.Board board = ChessGUI.Board.fromFEN(fen);
        String line;
        while ((line = readResponse()) != null) {
            if (line.startsWith("bestmove")) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) return null;
                String mv = parts[1];
                int from = ChessGUI.UCI.parseSquare(mv.substring(0, 2));
                int to = ChessGUI.UCI.parseSquare(mv.substring(2, 4));
                ChessGUI.PieceType promo = null;
                if (mv.length() >= 5) {
                    promo = switch (mv.charAt(4)) {
                        case 'q' -> ChessGUI.PieceType.QUEEN;
                        case 'r' -> ChessGUI.PieceType.ROOK;
                        case 'b' -> ChessGUI.PieceType.BISHOP;
                        case 'n' -> ChessGUI.PieceType.KNIGHT;
                        default -> null;
                    };
                }
                for (ChessGUI.Move m : board.legalMoves()) {
                    if (m.from == from && m.to == to && Objects.equals(m.promotion, promo)) {
                        return m;
                    }
                }
                return new ChessGUI.Move(from, to, promo, false, false, false, false);
            }
        }
        return null;
    }

    private static String loadPathFromConfig() {
        Path cfg = Paths.get("stockfish.path");
        if (Files.exists(cfg)) {
            try (BufferedReader br = Files.newBufferedReader(cfg)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        return line;
                    }
                }
            } catch (IOException ignored) {}
        }
        return "stockfish";
    }

    private void ensureExecutablePresent() throws IOException {
        Path p = Paths.get(path);
        if (p.isAbsolute() || path.contains(File.separator) || path.contains("/") || path.contains("\\")) {
            if (!Files.isRegularFile(p) || !Files.isExecutable(p)) {
                throw new FileNotFoundException("Stockfish binary not found or not executable: " + path);
            }
        }
    }
}
