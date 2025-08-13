import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Minimal Stockfish engine helper that reads the path to the Stockfish
 * executable from a configuration file. The configuration file is named
 * {@code stockfish.path} and must contain the absolute path to the
 * Stockfish binary in its first non-empty, non-comment line.
 */
public class StockfishEngine {
    private final String path;

    public StockfishEngine() {
        this.path = loadPath();
        validateExecutable();
    }

    /**
     * Returns the configured path to the Stockfish executable.
     */
    public String getPath() {
        return path;
    }

    private String loadPath() {
        Path config = Paths.get("stockfish.path");
        if (!Files.exists(config)) {
            throw new IllegalStateException("Missing configuration file stockfish.path");
        }
        try (BufferedReader br = Files.newBufferedReader(config)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                return line;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read stockfish.path", e);
        }
        throw new IllegalStateException("No path specified in stockfish.path");
    }

    private void validateExecutable() {
        Path exec = Paths.get(path);
        if (!Files.exists(exec)) {
            throw new IllegalStateException("Stockfish executable not found: " + path);
        }
        if (!Files.isRegularFile(exec)) {
            throw new IllegalStateException("Stockfish path is not a file: " + path);
        }
        if (!Files.isExecutable(exec)) {
            throw new IllegalStateException("Stockfish file is not executable: " + path);
        }
    }
}
