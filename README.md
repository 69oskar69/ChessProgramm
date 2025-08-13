# ChessProgramm

Java Swing basierte Schachoberfläche mit Drag & Drop.

## How to run

Compile and run using the included `ChessGUI` main method:

```bash
javac --release 21 -encoding UTF-8 -d out src/ChessGUI.java src/StockfishEngine.java
java -cp out ChessGUI
```

Requires Java 21.

## Stockfish Engine

This project can make use of the external Stockfish chess engine. To enable it:

1. Download a Stockfish binary from <https://stockfishchess.org/download/> and place it somewhere on your system.
2. Create a file named `stockfish.path` in the project root. The first non-empty line should contain the absolute path to the Stockfish executable. Lines beginning with `#` are treated as comments.

Example `stockfish.path`:

```
# Absolute path to your Stockfish binary
/usr/games/stockfish
```

`StockfishEngine` reads this file on startup and validates that the executable exists and is runnable.
