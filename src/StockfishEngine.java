import java.io.*;
import java.util.concurrent.TimeUnit;

public class StockfishEngine {
    private final String path;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;

    public StockfishEngine(String path) {
        this.path = path == null || path.isEmpty() ? "stockfish" : path;
    }

    public void start() throws IOException {
        if (process != null) return;
        ProcessBuilder pb = new ProcessBuilder(path);
        pb.redirectErrorStream(true);
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        sendCommand("uci");
        // wait for uciok
        String line;
        while ((line = readResponse()) != null) {
            if ("uciok".equals(line)) break;
        }
    }

    public void stop() {
        if (process == null) return;
        try {
            sendCommand("quit");
            process.waitFor(100, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
        process.destroy();
        process = null;
    }

    public void sendCommand(String cmd) {
        try {
            writer.write(cmd);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String readResponse() {
        try {
            return reader.readLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public ChessGUI.Move getBestMove(String fen, int depth) {
        if (process == null) throw new IllegalStateException("Engine not started");
        sendCommand("ucinewgame");
        sendCommand("position fen " + fen);
        sendCommand("go depth " + depth);
        try {
            String line;
            while ((line = readResponse()) != null) {
                if (line.startsWith("bestmove")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        String uciMove = parts[1];
                        ChessGUI.Board b = ChessGUI.Board.fromFEN(fen);
                        for (ChessGUI.Move m : b.legalMoves()) {
                            String uci = ChessGUI.UCI.fromTo(m.from, m.to);
                            if (m.promotion != null) uci += ChessGUI.UCI.promoChar(m.promotion);
                            if (uci.equals(uciMove)) {
                                return m;
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}

