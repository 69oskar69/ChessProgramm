import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.sound.sampled.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.*;
import javax.swing.Timer;
import java.awt.image.BufferedImage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Hashtable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Comparator;
import javax.swing.event.MouseInputAdapter;


/**
 * Schach mit Swing-GUI, KI, Analyse, Sounds, Drag & Drop und animierten Zügen.
 * - Spielerfarbe ist unten
 * - Quick-Analyse (ca. 10–30 s)
 * - ELO-Slider (800/1100/1400/1700)
 */
public class ChessGUI {
    // --- Board-Konstanten & Palette ---
    private static final int TILE = 72;          // Kachelgröße in px
    private static final int MARGIN = 16;        // äußerer Rand ums Brett
    private static final int ANIM_MS = 180;      // Animationsdauer in ms
    private static final int AI_DELAY_MS = 300;  // Verzögerung vor KI-Zug in ms

    // Farben (kannst du nach Geschmack anpassen)
    private static final Color LIGHT = new Color(238,238,210);
    private static final Color DARK  = new Color(118,150, 86);
    private static final Color LAST  = new Color(255,215,  0, 90);   // letzte Bewegung
    private static final Color CHECK = new Color(255, 80, 80,120);   // König im Schach
    private static final Color SEL   = new Color( 70,130,180,120);   // ausgewähltes Feld
    private static final Color CAP   = new Color(220, 90, 90,180);   // Capture-Ring
    private static final Color MOVE  = new Color( 30,144,255,180);   // Punkt für stillen Zug


    // ---------- Engine-Basics ----------
    enum Side { WHITE, BLACK;
        Side opposite() { return this==WHITE?BLACK:WHITE; }
        @Override public String toString(){ return this==WHITE ? "Weiß" : "Schwarz"; }
    }
    enum PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

    static final class Piece {
        final PieceType type; final Side side;
        Piece(PieceType t, Side s){ type=t; side=s; }
        char symbolUnicode(){
            return switch(type){
                case KING-> (side==Side.WHITE?'♔':'♚');
                case QUEEN->(side==Side.WHITE?'♕':'♛');
                case ROOK-> (side==Side.WHITE?'♖':'♜');
                case BISHOP->(side==Side.WHITE?'♗':'♝');
                case KNIGHT->(side==Side.WHITE?'♘':'♞');
                case PAWN->  (side==Side.WHITE?'♙':'♟');
            };
        }
    }

    static final class Move {
        final int from,to;
        final PieceType promotion; // null=keine
        final boolean castleK, castleQ, enPassant;
        final boolean isCapture;
        Move(int f,int t){ this(f,t,null,false,false,false,false); }
        Move(int f,int t,PieceType promo,boolean cK,boolean cQ,boolean ep,boolean capture){
            from=f; to=t; promotion=promo; castleK=cK; castleQ=cQ; enPassant=ep; isCapture=capture||ep;
        }
        boolean isPromotion(){ return promotion!=null; }
        @Override public String toString(){ return UCI.fromTo(from,to) + (promotion!=null? UCI.promoChar(promotion): ""); }
    }

    static final class Board {
        final Piece[] sq = new Piece[64];
        Side sideToMove = Side.WHITE;
        int enPassant = -1;
        boolean wCastleK=true,wCastleQ=true,bCastleK=true,bCastleQ=true;
        int halfmoveClock=0, fullmoveNumber=1;

        static int idx(int f,int r){ return r*8+f; }
        static int file(int i){ return i%8; }
        static int rank(int i){ return i/8; }
        static boolean in(int f,int r){ return f>=0&&f<8&&r>=0&&r<8; }

        static Board initial(){
            Board b=new Board();
            // Weiß
            b.sq[idx(0,0)]=new Piece(PieceType.ROOK,Side.WHITE);
            b.sq[idx(1,0)]=new Piece(PieceType.KNIGHT,Side.WHITE);
            b.sq[idx(2,0)]=new Piece(PieceType.BISHOP,Side.WHITE);
            b.sq[idx(3,0)]=new Piece(PieceType.QUEEN,Side.WHITE);
            b.sq[idx(4,0)]=new Piece(PieceType.KING,Side.WHITE);
            b.sq[idx(5,0)]=new Piece(PieceType.BISHOP,Side.WHITE);
            b.sq[idx(6,0)]=new Piece(PieceType.KNIGHT,Side.WHITE);
            b.sq[idx(7,0)]=new Piece(PieceType.ROOK,Side.WHITE);
            for(int f=0;f<8;f++) b.sq[idx(f,1)]=new Piece(PieceType.PAWN,Side.WHITE);
            // Schwarz
            b.sq[idx(0,7)]=new Piece(PieceType.ROOK,Side.BLACK);
            b.sq[idx(1,7)]=new Piece(PieceType.KNIGHT,Side.BLACK);
            b.sq[idx(2,7)]=new Piece(PieceType.BISHOP,Side.BLACK);
            b.sq[idx(3,7)]=new Piece(PieceType.QUEEN,Side.BLACK);
            b.sq[idx(4,7)]=new Piece(PieceType.KING,Side.BLACK);
            b.sq[idx(5,7)]=new Piece(PieceType.BISHOP,Side.BLACK);
            b.sq[idx(6,7)]=new Piece(PieceType.KNIGHT,Side.BLACK);
            b.sq[idx(7,7)]=new Piece(PieceType.ROOK,Side.BLACK);
            for(int f=0;f<8;f++) b.sq[idx(f,6)]=new Piece(PieceType.PAWN,Side.BLACK);
            return b;
        }

        static Board fromFEN(String fen){
            String[] parts = fen.trim().split("\\s+");
            if(parts.length < 4) throw new IllegalArgumentException("Ungültige FEN: "+fen);
            Board b = new Board();
            String[] ranks = parts[0].split("/");
            if(ranks.length!=8) throw new IllegalArgumentException("Ungültige FEN Ränge: "+fen);
            for(int r=7; r>=0; r--){
                String row = ranks[7-r];
                int f=0;
                for(char c: row.toCharArray()){
                    if(Character.isDigit(c)){ f += (c - '0'); }
                    else {
                        Side s = Character.isUpperCase(c) ? Side.WHITE : Side.BLACK;
                        char lc = Character.toLowerCase(c);
                        PieceType t = switch(lc){
                            case 'k'->PieceType.KING; case 'q'->PieceType.QUEEN; case 'r'->PieceType.ROOK;
                            case 'b'->PieceType.BISHOP; case 'n'->PieceType.KNIGHT; case 'p'->PieceType.PAWN;
                            default -> throw new IllegalArgumentException("Bad piece char in FEN: "+c);
                        };
                        b.sq[idx(f,r)] = new Piece(t,s); f++;
                    }
                }
                if(f!=8) throw new IllegalArgumentException("FEN Spaltenfehler in Rang "+(8-r));
            }
            b.sideToMove = parts[1].equals("w") ? Side.WHITE : Side.BLACK;
            String cast = parts[2];
            b.wCastleK = cast.contains("K");
            b.wCastleQ = cast.contains("Q");
            b.bCastleK = cast.contains("k");
            b.bCastleQ = cast.contains("q");
            b.enPassant = "-".equals(parts[3]) ? -1 : UCI.parseSquare(parts[3]);
            if(parts.length>=6){
                try { b.halfmoveClock = Integer.parseInt(parts[4]); } catch(Exception ignored){}
                try { b.fullmoveNumber = Integer.parseInt(parts[5]); } catch(Exception ignored){}
            }
            return b;
        }

        Board copy(){
            Board b=new Board();
            System.arraycopy(sq,0,b.sq,0,64);
            b.sideToMove=sideToMove; b.enPassant=enPassant;
            b.wCastleK=wCastleK; b.wCastleQ=wCastleQ; b.bCastleK=bCastleK; b.bCastleQ=bCastleQ;
            b.halfmoveClock=halfmoveClock; b.fullmoveNumber=fullmoveNumber;
            return b;
        }
        Piece at(int i){ return (i>=0&&i<64)?sq[i]:null; }
        int kingSquare(Side s){ for(int i=0;i<64;i++){ Piece p=sq[i]; if(p!=null&&p.type==PieceType.KING&&p.side==s) return i; } return -1; }

        boolean isInCheck(Side s){ int k=kingSquare(s); return isSquareAttacked(k, s.opposite()); }

        boolean isSquareAttacked(int target, Side by){
            int tx=file(target), ty=rank(target);
            for(int i=0;i<64;i++){
                Piece p=sq[i]; if(p==null||p.side!=by) continue;
                int x=file(i), y=rank(i);
                switch(p.type){
                    case PAWN -> {
                        int dir=(by==Side.WHITE)?1:-1;
                        if(x+1==tx && y+dir==ty) return true;
                        if(x-1==tx && y+dir==ty) return true;
                    }
                    case KNIGHT -> {
                        int[][] N={{1,2},{2,1},{2,-1},{1,-2},{-1,-2},{-2,-1},{-2,1},{-1,2}};
                        for(int[] d:N) if(x+d[0]==tx && y+d[1]==ty) return true;
                    }
                    case KING -> {
                        for(int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=1;dy++){
                            if(dx==0&&dy==0) continue;
                            if(x+dx==tx && y+dy==ty) return true;
                        }
                    }
                    case BISHOP,ROOK,QUEEN -> {
                        int[][] dirs = switch(p.type){
                            case BISHOP -> new int[][]{{1,1},{1,-1},{-1,1},{-1,-1}};
                            case ROOK   -> new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
                            default     -> new int[][]{{1,1},{1,-1},{-1,1},{-1,-1},{1,0},{-1,0},{0,1},{0,-1}};
                        };
                        for(int[] d: dirs){
                            int fx=x+d[0], fy=y+d[1];
                            while(in(fx,fy)){
                                int j=idx(fx,fy);
                                if(j==target) return true;
                                if(sq[j]!=null) break;
                                fx+=d[0]; fy+=d[1];
                            }
                        }
                    }
                }
            }
            return false;
        }

        List<Move> legalMoves(){
            List<Move> pm = pseudoMoves();
            List<Move> res = new ArrayList<>();
            Side me=sideToMove;
            for(Move m: pm){
                Board nb = makeMove(m);
                if(!nb.isInCheck(me)) res.add(m);
            }
            return res;
        }

        private void addPromotions(List<Move> list,int from,int to,boolean capture){
            list.add(new Move(from,to,PieceType.QUEEN,false,false,false,capture));
            list.add(new Move(from,to,PieceType.ROOK, false,false,false,capture));
            list.add(new Move(from,to,PieceType.BISHOP,false,false,false,capture));
            list.add(new Move(from,to,PieceType.KNIGHT,false,false,false,capture));
        }

        List<Move> pseudoMoves(){
            List<Move> list=new ArrayList<>();
            Side me=sideToMove, opp=me.opposite();
            for(int i=0;i<64;i++){
                Piece p=sq[i]; if(p==null||p.side!=me) continue;
                int x=file(i), y=rank(i);
                switch(p.type){
                    case PAWN -> {
                        int dir=(me==Side.WHITE)?1:-1;
                        int startRank=(me==Side.WHITE)?1:6;
                        int promoteRank=(me==Side.WHITE)?6:1;
                        int ny=y+dir;
                        if(in(x,ny) && at(idx(x,ny))==null){
                            if(y==promoteRank) addPromotions(list,i,idx(x,ny),false);
                            else list.add(new Move(i,idx(x,ny)));
                            if(y==startRank){
                                int ny2=y+2*dir;
                                if(at(idx(x,ny2))==null) list.add(new Move(i,idx(x,ny2)));
                            }
                        }
                        for(int dx: new int[]{-1,1}){
                            int nx=x+dx; if(!in(nx,ny)) continue;
                            int j=idx(nx,ny);
                            if(at(j)!=null && at(j).side==opp){
                                if(y==promoteRank) addPromotions(list,i,j,true);
                                else list.add(new Move(i,j,null,false,false,false,true));
                            }
                        }
                        if(enPassant!=-1){
                            int ex=file(enPassant), ey=rank(enPassant);
                            if(ey==y+dir && Math.abs(ex-x)==1) list.add(new Move(i,enPassant,null,false,false,true,true));
                        }
                    }
                    case KNIGHT -> {
                        int[][] N={{1,2},{2,1},{2,-1},{1,-2},{-1,-2},{-2,-1},{-2,1},{-1,2}};
                        for(int[] d:N){
                            int nx=x+d[0], ny=y+d[1]; if(!in(nx,ny)) continue;
                            int j=idx(nx,ny);
                            if(at(j)==null) list.add(new Move(i,j));
                            else if(at(j).side==opp) list.add(new Move(i,j,null,false,false,false,true));
                        }
                    }
                    case BISHOP,ROOK,QUEEN -> {
                        int[][] dirs = switch(p.type){
                            case BISHOP -> new int[][]{{1,1},{1,-1},{-1,1},{-1,-1}};
                            case ROOK   -> new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
                            default     -> new int[][]{{1,1},{1,-1},{-1,1},{-1,-1},{1,0},{-1,0},{0,1},{0,-1}};
                        };
                        for(int[] d:dirs){
                            int nx=x+d[0], ny=y+d[1];
                            while(in(nx,ny)){
                                int j=idx(nx,ny);
                                if(at(j)==null) list.add(new Move(i,j));
                                else { if(at(j).side==opp) list.add(new Move(i,j,null,false,false,false,true)); break; }
                                nx+=d[0]; ny+=d[1];
                            }
                        }
                    }
                    case KING -> {
                        for(int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=1;dy++){
                            if(dx==0&&dy==0) continue;
                            int nx=x+dx, ny=y+dy; if(!in(nx,ny)) continue;
                            int j=idx(nx,ny);
                            if(at(j)==null) list.add(new Move(i,j));
                            else if(at(j).side==opp) list.add(new Move(i,j,null,false,false,false,true));
                        }
                        // Rochade
                        if(me==Side.WHITE && i==idx(4,0)){
                            if(wCastleK && at(idx(5,0))==null && at(idx(6,0))==null
                                    && !isSquareAttacked(idx(4,0),opp) && !isSquareAttacked(idx(5,0),opp) && !isSquareAttacked(idx(6,0),opp)
                                    && at(idx(7,0))!=null && at(idx(7,0)).type==PieceType.ROOK && at(idx(7,0)).side==Side.WHITE){
                                list.add(new Move(i,idx(6,0),null,true,false,false,false));
                            }
                            if(wCastleQ && at(idx(3,0))==null && at(idx(2,0))==null && at(idx(1,0))==null
                                    && !isSquareAttacked(idx(4,0),opp) && !isSquareAttacked(idx(3,0),opp) && !isSquareAttacked(idx(2,0),opp)
                                    && at(idx(0,0))!=null && at(idx(0,0)).type==PieceType.ROOK && at(idx(0,0)).side==Side.WHITE){
                                list.add(new Move(i,idx(2,0),null,false,true,false,false));
                            }
                        } else if(me==Side.BLACK && i==idx(4,7)){
                            if(bCastleK && at(idx(5,7))==null && at(idx(6,7))==null
                                    && !isSquareAttacked(idx(4,7),opp) && !isSquareAttacked(idx(5,7),opp) && !isSquareAttacked(idx(6,7),opp)
                                    && at(idx(7,7))!=null && at(idx(7,7)).type==PieceType.ROOK && at(idx(7,7)).side==Side.BLACK){
                                list.add(new Move(i,idx(6,7),null,true,false,false,false));
                            }
                            if(bCastleQ && at(idx(3,7))==null && at(idx(2,7))==null && at(idx(1,7))==null
                                    && !isSquareAttacked(idx(4,7),opp) && !isSquareAttacked(idx(3,7),opp) && !isSquareAttacked(idx(2,7),opp)
                                    && at(idx(0,7))!=null && at(idx(0,7)).type==PieceType.ROOK && at(idx(0,7)).side==Side.BLACK){
                                list.add(new Move(i,idx(2,7),null,false,true,false,false));
                            }
                        }
                    }
                }
            }
            return list;
        }

        Board makeMove(Move m){
            Board b=this.copy();
            b.enPassant=-1;
            Piece mover=b.sq[m.from];
            Piece captured=b.sq[m.to];

            boolean isPawnMove=(mover.type==PieceType.PAWN);
            boolean isAnyCapture = m.isCapture || captured!=null;
            b.halfmoveClock = (isPawnMove || isAnyCapture) ? 0 : (b.halfmoveClock+1);

            if(m.castleK||m.castleQ){
                if(mover.side==Side.WHITE){
                    b.sq[idx(4,0)]=null;
                    if(m.castleK){
                        b.sq[idx(6,0)]=new Piece(PieceType.KING,Side.WHITE);
                        b.sq[idx(7,0)]=null; b.sq[idx(5,0)]=new Piece(PieceType.ROOK,Side.WHITE);
                    } else {
                        b.sq[idx(2,0)]=new Piece(PieceType.KING,Side.WHITE);
                        b.sq[idx(0,0)]=null; b.sq[idx(3,0)]=new Piece(PieceType.ROOK,Side.WHITE);
                    }
                    b.wCastleK=b.wCastleQ=false;
                } else {
                    b.sq[idx(4,7)]=null;
                    if(m.castleK){
                        b.sq[idx(6,7)]=new Piece(PieceType.KING,Side.BLACK);
                        b.sq[idx(7,7)]=null; b.sq[idx(5,7)]=new Piece(PieceType.ROOK,Side.BLACK);
                    } else {
                        b.sq[idx(2,7)]=new Piece(PieceType.KING,Side.BLACK);
                        b.sq[idx(0,7)]=null; b.sq[idx(3,7)]=new Piece(PieceType.ROOK,Side.BLACK);
                    }
                    b.bCastleK=b.bCastleQ=false;
                }
            } else if(m.enPassant){
                b.sq[m.from]=null; b.sq[m.to]=mover;
                if(mover.side==Side.WHITE) b.sq[m.to-8]=null; else b.sq[m.to+8]=null;
            } else {
                b.sq[m.from]=null; b.sq[m.to]=mover;
                if(m.isPromotion()) b.sq[m.to]=new Piece(m.promotion, mover.side);
            }

            if(!m.castleK && !m.castleQ && mover.type==PieceType.PAWN){
                int fr=rank(m.from), tr=rank(m.to);
                if(Math.abs(tr-fr)==2) b.enPassant=(mover.side==Side.WHITE)?(m.from+8):(m.from-8);
            }

            if(mover.type==PieceType.KING){ if(mover.side==Side.WHITE){ b.wCastleK=b.wCastleQ=false; } else { b.bCastleK=b.bCastleQ=false; } }
            if(mover.type==PieceType.ROOK){
                if(m.from==idx(0,0)) b.wCastleQ=false;
                if(m.from==idx(7,0)) b.wCastleK=false;
                if(m.from==idx(0,7)) b.bCastleQ=false;
                if(m.from==idx(7,7)) b.bCastleK=false;
            }
            if(captured!=null && captured.type==PieceType.ROOK){
                if(m.to==idx(0,0)) b.wCastleQ=false;
                if(m.to==idx(7,0)) b.wCastleK=false;
                if(m.to==idx(0,7)) b.bCastleQ=false;
                if(m.to==idx(7,7)) b.bCastleK=false;
            }

            if(b.sideToMove==Side.BLACK) b.fullmoveNumber++;
            b.sideToMove=b.sideToMove.opposite();
            return b;
        }
    }

    static final class UCI {
        static String sq(int i){ return ""+(char)('a'+Board.file(i)) + (char)('1'+Board.rank(i)); }
        static String fromTo(int f,int t){ return sq(f)+sq(t); }
        static char promoChar(PieceType p){ return switch(p){ case QUEEN->'q'; case ROOK->'r'; case BISHOP->'b'; case KNIGHT->'n'; default->'?'; }; }
        static int parseSquare(String s){
            if(s==null||s.length()!=2) return -1;
            int f = s.charAt(0)-'a', r = s.charAt(1)-'1';
            if(f<0||f>7||r<0||r>7) return -1;
            return Board.idx(f,r);
        }
    }

    static final class Eval {
        static final int[][] PST_P = mirror(new int[]{
                0,0,0,0,0,0,0,0,  5,10,10,-20,-20,10,10,5,  5,-5,-10,0,0,-10,-5,5,
                0,0,0,20,20,0,0,0, 5,5,10,25,25,10,5,5, 10,10,20,30,30,20,10,10,
                50,50,50,50,50,50,50,50, 0,0,0,0,0,0,0,0 });
        static final int[][] PST_N = mirror(new int[]{
                -50,-40,-30,-30,-30,-30,-40,-50, -40,-20,0,5,5,0,-20,-40, -30,5,10,15,15,10,5,-30,
                -30,0,15,20,20,15,0,-30, -30,5,15,20,20,15,5,-30, -30,0,10,15,15,10,0,-30,
                -40,-20,0,0,0,0,-20,-40, -50,-40,-30,-30,-30,-30,-40,-50 });
        static final int[][] PST_B = mirror(new int[]{
                -20,-10,-10,-10,-10,-10,-10,-20, -10,0,0,0,0,0,0,-10, -10,0,5,10,10,5,0,-10,
                -10,5,5,10,10,5,5,-10, -10,0,10,10,10,10,0,-10, -10,10,10,10,10,10,10,-10,
                -10,5,0,0,0,0,5,-10, -20,-10,-10,-10,-10,-10,-10,-20 });
        static final int[][] PST_R = mirror(new int[]{
                0,0,0,5,5,0,0,0, -5,0,0,0,0,0,0,-5, -5,0,0,0,0,0,0,-5,
                -5,0,0,0,0,0,0,-5, -5,0,0,0,0,0,0,-5, -5,0,0,0,0,0,0,-5,
                5,10,10,10,10,10,10,5, 0,0,0,0,0,0,0,0 });
        static final int[][] PST_Q = mirror(new int[]{
                -20,-10,-10,-5,-5,-10,-10,-20, -10,0,0,0,0,0,0,-10, -10,0,5,5,5,5,0,-10,
                -5,0,5,5,5,5,0,-5, 0,0,5,5,5,5,0,-5, -10,5,5,5,5,5,0,-10,
                -10,0,5,0,0,0,0,-10, -20,-10,-10,-5,-5,-10,-10,-20 });
        static final int[][] PST_K = mirror(new int[]{
                -30,-40,-40,-50,-50,-40,-40,-30, -30,-40,-40,-50,-50,-40,-40,-30, -30,-40,-40,-50,-50,-40,-40,-30,
                -30,-40,-40,-50,-50,-40,-40,-30, -20,-30,-30,-40,-40,-30,-30,-20, -10,-20,-20,-20,-20,-20,-20,-10,
                20,20,0,0,0,0,20,20, 20,30,10,0,0,10,30,20 });

        private static int[][] mirror(int[] base){
            int[] white = Arrays.copyOf(base, 64);
            int[] black = new int[64];
            for(int r=0;r<8;r++) for(int f=0;f<8;f++) black[Board.idx(f,7-r)] = base[Board.idx(f,r)];
            return new int[][]{white, black};
        }
        static int val(PieceType t){
            return switch(t){ case PAWN->100; case KNIGHT->320; case BISHOP->330; case ROOK->500; case QUEEN->900; case KING->0; };
        }
        static int evaluate(Board b){
            int score=0;
            for(int i=0;i<64;i++){
                Piece p=b.sq[i]; if(p==null) continue;
                int pst = switch(p.type){
                    case PAWN->PST_P[p.side==Side.WHITE?0:1][i];
                    case KNIGHT->PST_N[p.side==Side.WHITE?0:1][i];
                    case BISHOP->PST_B[p.side==Side.WHITE?0:1][i];
                    case ROOK->PST_R[p.side==Side.WHITE?0:1][i];
                    case QUEEN->PST_Q[p.side==Side.WHITE?0:1][i];
                    case KING->PST_K[p.side==Side.WHITE?0:1][i];
                };
                int s = val(p.type)+pst;
                score += (p.side==Side.WHITE)? s : -s;
            }
            int mob = b.legalMoves().size();
            score += (b.sideToMove==Side.WHITE?1:-1) * (mob/3);
            return score;
        }
    }

    static final class AI {
        static final int MATE = 1_000_000;
        private int maxDepth;
        AI(int d){ maxDepth=Math.max(1,d); }
        int getDepth(){ return maxDepth; }
        void setDepth(int d){ maxDepth=Math.max(1,d); }

        Move findBestMove(Board b){
            List<Move> moves=b.legalMoves();
            if(moves.isEmpty()) return null;
            moves.sort((a,c)-> Boolean.compare((c.isCapture||c.isPromotion()), (a.isCapture||a.isPromotion())));
            int bestScore=Integer.MIN_VALUE; Move best=moves.get(0);
            for(Move m: moves){
                int s = scoreMove(b, m, maxDepth);
                if(s>bestScore){ bestScore=s; best=m; }
            }
            return best;
        }
        int scoreMove(Board b, Move m, int depth){
            Board nb=b.makeMove(m);
            return -negamax(nb, depth-1, -MATE, MATE, 1);
        }
        List<ScoredMove> analyzeRoot(Board b, int depth){
            List<ScoredMove> out=new ArrayList<>();
            for(Move m: b.legalMoves()){
                int s = scoreMove(b, m, depth);
                out.add(new ScoredMove(m,s));
            }
            out.sort((x,y)-> Integer.compare(y.score, x.score));
            return out;
        }
        // Quick-Analyse: nur Top-K Wurzelzüge scoren
        int bestScoreApprox(Board b, int depth, int topK){
            List<Move> moves=b.legalMoves();
            if(moves.isEmpty()) return 0;
            moves.sort((a,c)-> Boolean.compare((c.isCapture||c.isPromotion()), (a.isCapture||a.isPromotion())));
            int limit = Math.min(topK, moves.size());
            int best = Integer.MIN_VALUE;
            for(int i=0;i<limit;i++){
                Move m=moves.get(i);
                int s = scoreMove(b, m, depth);
                if(s>best) best=s;
            }
            return best;
        }
        private int negamax(Board b,int depth,int alpha,int beta,int ply){
            List<Move> moves=b.legalMoves();
            if(depth==0) return (b.sideToMove==Side.WHITE?1:-1)*Eval.evaluate(b);
            if(moves.isEmpty()){
                if(b.isInCheck(b.sideToMove)) return -MATE + ply;
                return 0;
            }
            moves.sort((a,c)-> Boolean.compare((c.isCapture||c.isPromotion()), (a.isCapture||a.isPromotion())));
            int best=Integer.MIN_VALUE/2;
            for(Move m: moves){
                Board nb=b.makeMove(m);
                int val = -negamax(nb,depth-1,-beta,-alpha,ply+1);
                if(val>best) best=val;
                if(val>alpha) alpha=val;
                if(alpha>=beta) break;
            }
            return best;
        }
        static final class ScoredMove { final Move move; final int score; ScoredMove(Move m,int s){ move=m; score=s; } }
    }

    // ---------- Sound ----------
    static final class SoundFX {
        static boolean enabledPawn = true;
        static boolean enabledExtra = false; // Capture/Check

        static void pawn(){ if(enabledPawn) tone(760, 70, 0.3); }
        static void capture(){ if(enabledExtra) tone(280, 120, 0.35); }
        static void check(){ if(enabledExtra) tone(1040, 120, 0.35); }

        private static void tone(int hz, int ms, double vol){
            new Thread(() -> {
                try{
                    float SR = 44100f;
                    AudioFormat af = new AudioFormat(SR, 8, 1, true, false);
                    try (SourceDataLine line = AudioSystem.getSourceDataLine(af)) {
                        line.open(af);
                        line.start();
                        int len = (int)(ms * SR / 1000);
                        byte[] buf = new byte[len];
                        for(int i=0;i<len;i++){
                            double angle = 2*Math.PI * i * hz / SR;
                            buf[i] = (byte)(Math.sin(angle) * 127 * vol);
                        }
                        line.write(buf,0,buf.length);
                        line.drain(); line.stop();
                    }
                } catch (Exception ignored){}
            }).start();
        }
    }

    // ---------- GUI & Spiel-Logik ----------
    private JFrame frame;
    private BoardPanel boardPanel;
    private JLabel status;

    private Board board = Board.initial();
    private final Deque<Board> history = new ArrayDeque<>();
    private final List<PlyRecord> plies = new ArrayList<>();

    private final List<PieceType> capturedByWhite = new ArrayList<>();
    private final List<PieceType> capturedByBlack = new ArrayList<>();
    private JLabel capNorthLabel, capSouthLabel;

    private AI ai = new AI(3);
    private Side human = Side.WHITE;
    private boolean flip = false; // true = Schwarz unten

    private int selected=-1;
    private List<Move> legalFromSelected = List.of();
    private Move lastMove=null, hintMove=null;

    private volatile boolean busy=false;

    // Controls
    private JPanel rightPanel;
    private JLabel depthLabel;
    private JSlider depthSlider;
    private JCheckBox sfxPawn, sfxExtra;
    private EvalBar evalBar;

    // ELO-Stufen → Suchtiefe
    private final int[] ELO_LEVELS = {800, 1100, 1400, 1700};
    private int depthFromIndex(int idx){
        return switch(idx){
            case 0 -> 2; // ~800
            case 1 -> 3; // ~1100
            case 2 -> 4; // ~1400
            default -> 5; // ~1700
        };
    }
    private String labelFromIndex(int idx){
        return switch(idx){
            case 0 -> "leicht";
            case 1 -> "normal";
            case 2 -> "stark";
            default -> "sehr stark";
        };
    }

    public static void main(String[] args){
        try{
            for(UIManager.LookAndFeelInfo info: UIManager.getInstalledLookAndFeels()){
                if("Nimbus".equals(info.getName())){ UIManager.setLookAndFeel(info.getClassName()); break; }
            }
        }catch(Exception ignored){}
        SwingUtilities.invokeLater(() -> new ChessGUI().start());
    }

    private void start(){
        frame=new JFrame("Schach mit KI – GUI & Analyse");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        boardPanel=new BoardPanel();
        JPanel boardContainer=new JPanel(new BorderLayout());
        boardContainer.setOpaque(false);
        capNorthLabel=new JLabel();
        capSouthLabel=new JLabel();
        capNorthLabel.setHorizontalAlignment(SwingConstants.CENTER);
        capSouthLabel.setHorizontalAlignment(SwingConstants.CENTER);
        Font scoreFont=new Font("SansSerif", Font.PLAIN, 20);
        capNorthLabel.setFont(scoreFont);
        capSouthLabel.setFont(scoreFont);
        boardContainer.add(capNorthLabel, BorderLayout.NORTH);
        boardContainer.add(boardPanel, BorderLayout.CENTER);
        boardContainer.add(capSouthLabel, BorderLayout.SOUTH);
        frame.add(boardContainer, BorderLayout.CENTER);

        rightPanel=createRightControls();
        frame.add(rightPanel, BorderLayout.EAST);
        frame.setJMenuBar(createMenu());

        status=new JLabel("Bereit.");
        status.setBorder(new EmptyBorder(6,10,6,10));
        frame.add(status, BorderLayout.SOUTH);

        // Direkt starten als Weiß (kein Dialog)
        newGame(Side.WHITE);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        updateEvalBar();
        updateScoreBoard();
    }

    private JMenuBar createMenu(){
        JMenuBar mb=new JMenuBar();

        JMenu game=new JMenu("Spiel");
        JMenuItem newW=new JMenuItem("Als Weiß spielen");
        JMenuItem newB=new JMenuItem("Als Schwarz spielen");
        JMenuItem undo=new JMenuItem("Undo", createUndoIcon());
        JMenuItem analyzeInfo=new JMenuItem("Analyse‑Info");
        JMenuItem quit=new JMenuItem("Beenden");
        newW.addActionListener(e -> newGame(Side.WHITE));
        newB.addActionListener(e -> newGame(Side.BLACK));
        undo.addActionListener(e -> onUndo());
        analyzeInfo.addActionListener(e -> JOptionPane.showMessageDialog(frame, "Die Post‑Game‑Analyse startet automatisch bei Spielende (Matt/Patt).", "Info", JOptionPane.INFORMATION_MESSAGE));
        quit.addActionListener(e -> frame.dispose());
        game.add(newW); game.add(newB); game.addSeparator(); game.add(undo); game.add(analyzeInfo); game.addSeparator(); game.add(quit);

        JMenu view = new JMenu("Ansicht");
        JCheckBoxMenuItem zen = new JCheckBoxMenuItem("Zen‑Mode (Seitenleiste ausblenden)");
        zen.addActionListener(e -> {
            rightPanel.setVisible(!zen.isSelected());
            frame.pack();
        });
        view.add(zen);

        mb.add(game); mb.add(view);
        return mb;
    }

    private JPanel createRightControls(){
        JPanel root=new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(12,12,12,12));
        root.setOpaque(false);

        JPanel top=new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel title=new JLabel("Steuerung");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(title);
        top.add(Box.createVerticalStrut(10));

        evalBar = new EvalBar();
        evalBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(evalBar);
        top.add(Box.createVerticalStrut(12));

        root.add(top, BorderLayout.NORTH);

        // Play-Controls
        JPanel p=new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        JLabel diffLbl=new JLabel("KI‑Schwierigkeit (ELO)");
        diffLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(diffLbl);

        depthSlider=new JSlider(0, ELO_LEVELS.length-1, 1); // default ~1100 → Tiefe 3
        depthSlider.setPaintTicks(true);
        depthSlider.setMajorTickSpacing(1);
        depthSlider.setSnapToTicks(true);
        depthSlider.setPaintLabels(true);
        Hashtable<Integer, JLabel> labels = new Hashtable<>();
        for (int i=0;i<ELO_LEVELS.length;i++) labels.put(i, new JLabel(String.valueOf(ELO_LEVELS[i])));
        depthSlider.setLabelTable(labels);

        ai.setDepth(depthFromIndex(depthSlider.getValue()));
        depthLabel=new JLabel();
        depthSlider.addChangeListener(e -> updateDepthLabel());
        updateDepthLabel(); // initial

        depthSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(depthSlider);

        depthLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(depthLabel);
        p.add(Box.createVerticalStrut(12));

        JLabel sfxLbl=new JLabel("Sounds");
        sfxLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(sfxLbl);
        sfxPawn = new JCheckBox("Bauer bewegt (an)"); sfxPawn.setSelected(true);
        sfxPawn.addActionListener(e -> SoundFX.enabledPawn = sfxPawn.isSelected());
        sfxExtra = new JCheckBox("Zusätzlich: Schlag/Schach");
        sfxExtra.addActionListener(e -> SoundFX.enabledExtra = sfxExtra.isSelected());
        sfxPawn.setAlignmentX(Component.LEFT_ALIGNMENT);
        sfxExtra.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(sfxPawn); p.add(sfxExtra);
        p.add(Box.createVerticalStrut(12));

        JPanel btns=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));
        JButton undoBtn=new JButton("Undo", createUndoIcon());
        JButton hintBtn=new JButton("Hint", createHintIcon());
        Stream.of(undoBtn, hintBtn).forEach(b -> {
            b.setFocusPainted(false);
            b.setBorder(new LineBorder(new Color(200,200,200)));
            b.setBackground(new Color(245,245,245));
        });
        undoBtn.setToolTipText("Letzten Zug rückgängig machen (U)");
        hintBtn.setToolTipText("Vorschlag für nächsten Zug (H)");
        undoBtn.addActionListener(e -> onUndo());
        hintBtn.addActionListener(e -> onHint());
        btns.add(undoBtn); btns.add(hintBtn);
        btns.setOpaque(false);
        btns.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(btns);

        p.add(Box.createVerticalGlue());
        JLabel tip=new JLabel("<html><div style='width:210px'>Ziehe Figuren mit der Maus. " +
                "Promotion wird abgefragt. Die schnelle Analyse erscheint am Spielende.</div></html>");
        tip.setForeground(new Color(80,80,80));
        tip.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(tip);

        root.add(p, BorderLayout.CENTER);

        rightPanel = root;
        return root;
    }

    private static Icon createUndoIcon(){
        int s=16;
        BufferedImage img=new BufferedImage(s,s,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(60,60,60));
        g.setStroke(new BasicStroke(2f));
        g.drawArc(2,2,12,12,90,270);
        g.drawLine(2,8,8,2);
        g.drawLine(2,8,8,14);
        g.dispose();
        return new ImageIcon(img);
    }

    private static Icon createHintIcon(){
        int s=16;
        BufferedImage img=new BufferedImage(s,s,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(255,200,0));
        g.fillOval(3,1,10,10);
        g.setColor(new Color(200,200,200));
        g.fillRect(6,11,4,4);
        g.setColor(new Color(120,120,120));
        g.drawOval(3,1,10,10);
        g.drawLine(6,15,10,15);
        g.dispose();
        return new ImageIcon(img);
    }

    private void updateDepthLabel(){
        int idx = depthSlider.getValue();
        ai.setDepth(depthFromIndex(idx));
        depthLabel.setText("ELO ca. " + ELO_LEVELS[idx] + "  –  " + labelFromIndex(idx) + "  (Tiefe " + ai.getDepth() + ")");
    }

    private void newGame(Side side){
        if(busy) return;
        human=side;
        flip=(human==Side.WHITE); // deine Farbe unten
        board=Board.initial();
        history.clear(); plies.clear();
        capturedByWhite.clear(); capturedByBlack.clear();
        updateScoreBoard();
        lastMove=null; hintMove=null; selected=-1; legalFromSelected=List.of();
        status.setText("Neues Spiel: Du spielst " + human + ". " + board.sideToMove + " am Zug.");
        boardPanel.repaint();
        updateEvalBar();
        maybeAIThink();
    }

    private void onUndo(){
        if(busy || history.isEmpty()) return;

        hintMove = null;
        selected = -1;
        legalFromSelected = List.of();

        // revert last move (AI or human)
        board = history.pop();
        if(!plies.isEmpty()) plies.remove(plies.size()-1);

        // if still not player's turn, undo one more (undo pair)
        if(board.sideToMove != human && !history.isEmpty()){
            board = history.pop();
            if(!plies.isEmpty()) plies.remove(plies.size()-1);
        }

        lastMove = plies.isEmpty() ? null : plies.get(plies.size()-1).move;
        recalcCaptures();
        updateScoreBoard();
        boardPanel.repaint();
        updateEvalBar();
        status.setText("Zug zurückgenommen. " + board.sideToMove + " am Zug.");
    }

    private void onHint(){
        if(busy) return;
        if(board.legalMoves().isEmpty()) return;

        status.setText("Hint wird berechnet…");
        busy = true;
        new SwingWorker<Move,Void>(){
            @Override protected Move doInBackground(){ return ai.findBestMove(board); }
            @Override protected void done(){
                try{
                    hintMove = get();
                    if(hintMove != null)
                        status.setText("Hint: " + pretty(hintMove));
                    else
                        status.setText("Kein Zug verfügbar.");
                }catch(Exception ex){
                    hintMove = null;
                    status.setText("Hint fehlgeschlagen.");
                }
                boardPanel.repaint();
                busy = false;
            }
        }.execute();
    }

    private void maybeAIThink(){
        if(busy) return;
        if(board.legalMoves().isEmpty()){ onGameOverWithAnalysis(); return; }
        if(board.sideToMove!=human){
            status.setText("KI denkt… (Tiefe "+ai.getDepth()+")");
            busy=true;
            new SwingWorker<Move,Void>(){
                @Override protected Move doInBackground(){ return ai.findBestMove(board); }
                @Override protected void done(){
                    try{
                        Move m=get();
                        if(m==null){ onGameOverWithAnalysis(); busy=false; return; }
                        Timer delay = new Timer(AI_DELAY_MS, e ->
                                playMove(m,
                                        () -> status.setText(board.legalMoves().isEmpty()
                                                ? "Spielende."
                                                : "Du bist dran (" + human + ")."),
                                        false)); // <— teleport AI move too
                        delay.setRepeats(false);
                        delay.start();
                    }catch(Exception ex){ status.setText("Fehler in der KI."); busy=false; }
                }
            }.execute();
        } else {
            status.setText("Du bist dran ("+human+").");
        }
    }

    private void onGameOverWithAnalysis(){
        boolean check = board.isInCheck(board.sideToMove);
        String msg = check ? ("Schachmatt! "+board.sideToMove+" ist matt. "+board.sideToMove.opposite()+" gewinnt.")
                : "Patt! Unentschieden.";
        JOptionPane.showMessageDialog(frame, msg + "\nDie Partie wird jetzt analysiert (Schnellmodus).", "Spielende", JOptionPane.INFORMATION_MESSAGE);
        runPostGameAnalysis();
    }

    // --- animiertes Ausführen (für Mensch & KI)
    private void playMove(Move m, Runnable after, boolean animate){
        busy = true;

        Piece moved = board.at(m.from);
        if(moved!=null && moved.type==PieceType.PAWN) SoundFX.pawn();
        if(m.isCapture) SoundFX.capture();

        if(animate){
            Board pre = board.copy();
            boardPanel.animateMove(pre, m, () -> {
                commitMoveAndRecord(m);
                if(board.isInCheck(board.sideToMove)) SoundFX.check();
                boardPanel.repaint();
                updateEvalBar();
                busy = false;

                if(board.legalMoves().isEmpty()){
                    onGameOverWithAnalysis();
                    return;
                }
                if(after!=null) after.run();
                if(board.sideToMove!=human) maybeAIThink();
            });
        } else {
            // Teleport immediately (no animation)
            commitMoveAndRecord(m);
            if(board.isInCheck(board.sideToMove)) SoundFX.check();
            boardPanel.repaint();
            updateEvalBar();
            busy = false;

            if(board.legalMoves().isEmpty()){
                onGameOverWithAnalysis();
                return;
            }
            if(after!=null) after.run();
            if(board.sideToMove!=human) maybeAIThink();
        }
    }

    private void commitMoveAndRecord(Move m){
        history.push(board.copy());
        plies.add(new PlyRecord(board.copy(), m));
        Piece capturedPiece = null;
        if(m.enPassant){
            capturedPiece = board.at(m.to + (board.sideToMove==Side.WHITE? -8 : 8));
        } else {
            capturedPiece = board.at(m.to);
        }
        if(capturedPiece != null){
            if(board.sideToMove==Side.WHITE) capturedByWhite.add(capturedPiece.type);
            else capturedByBlack.add(capturedPiece.type);
        }
        board = board.makeMove(m);
        lastMove = m;
        selected=-1; legalFromSelected=List.of(); hintMove=null;
        updateScoreBoard();
    }

    private static String pretty(Move m){
        if(m.castleK) return "O-O";
        if(m.castleQ) return "O-O-O";
        String s=UCI.fromTo(m.from, m.to);
        if(m.isPromotion()) s += UCI.promoChar(m.promotion);
        return s;
    }

    private void beep(){ Toolkit.getDefaultToolkit().beep(); }

    private void updateEvalBar(){
        int cp = (int)Math.max(-2000, Math.min(2000, (double)Eval.evaluate(board)));
        evalBar.setEvalCp(cp, flip);
    }

    private void updateScoreBoard(){
        List<PieceType> playerCaps = (human==Side.WHITE) ? capturedByWhite : capturedByBlack;
        List<PieceType> aiCaps = (human==Side.WHITE) ? capturedByBlack : capturedByWhite;
        Side aiSide = human.opposite();
        Side playerSide = human;

        String aiPieces = piecesToString(aiCaps, playerSide);
        String playerPieces = piecesToString(playerCaps, aiSide);

        int diff = totalValue(playerCaps) - totalValue(aiCaps);
        String aiScore = diff < 0 ? "+" + (-diff) : "";
        String playerScore = diff > 0 ? "+" + diff : "";

        capNorthLabel.setText(aiPieces + (aiScore.isEmpty()? "" : "  " + aiScore));
        capSouthLabel.setText((playerScore.isEmpty()? "" : playerScore + "  ") + playerPieces);
    }

    private String piecesToString(List<PieceType> pieces, Side side){
        List<PieceType> sorted = new ArrayList<>(pieces);
        sorted.sort(Comparator.comparingInt(Eval::val));
        StringBuilder sb=new StringBuilder();
        for(PieceType pt: sorted){
            sb.append(new Piece(pt, side).symbolUnicode());
        }
        return sb.toString();
    }

    private int totalValue(List<PieceType> pieces){
        int sum=0;
        for(PieceType pt: pieces) sum += Eval.val(pt)/100;
        return sum;
    }

    private void recalcCaptures(){
        capturedByWhite.clear();
        capturedByBlack.clear();
        for(PlyRecord pr: plies){
            Move mv=pr.move;
            Board b=pr.before;
            Piece capturedPiece;
            if(mv.enPassant){
                capturedPiece = b.at(mv.to + (b.sideToMove==Side.WHITE ? -8 : 8));
            } else {
                capturedPiece = b.at(mv.to);
            }
            if(capturedPiece!=null){
                if(b.sideToMove==Side.WHITE) capturedByWhite.add(capturedPiece.type);
                else capturedByBlack.add(capturedPiece.type);
            }
        }
    }

    // ---------- Analyse (Quick) ----------
    static final class PlyRecord {
        final Board before;
        final Move move;
        PlyRecord(Board b, Move m){ before=b; move=m; }
    }
    static final class MoveAnalysis {
        final int plyIndex;
        final int moveNumber; // 1..n
        final Side side;
        final String moveStr;
        final String bestStr;
        final int lossCp;      // Centipawn-Verlust ggü. Best
        final int evalAfterW;  // Eval nach dem Zug, Weiß-Sicht (cp)
        final String label;
        MoveAnalysis(int plyIndex,int moveNumber,Side s,String mv,String best,int loss,int evalW,String label){
            this.plyIndex=plyIndex; this.moveNumber=moveNumber; this.side=s;
            this.moveStr=mv; this.bestStr=best; this.lossCp=loss; this.evalAfterW=evalW; this.label=label;
        }
    }
    static final class AnalysisResult {
        final List<MoveAnalysis> rows;
        final double accWhite, accBlack;
        final int acplWhite, acplBlack;
        final boolean truncated;
        final int analyzed, total;
        AnalysisResult(List<MoveAnalysis> rows,double accW,double accB,int acplW,int acplB, boolean truncated, int analyzed, int total){
            this.rows=rows; this.accWhite=accW; this.accBlack=accB; this.acplWhite=acplW; this.acplBlack=acplB;
            this.truncated=truncated; this.analyzed=analyzed; this.total=total;
        }
    }

    private void runPostGameAnalysis(){
        if(plies.isEmpty()){
            JOptionPane.showMessageDialog(frame,"Keine Züge zu analysieren.","Analyse",JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Quick-Analyse Parameter (auf ~10–30s gezielt)
        final int MAX_PLIES = 80;                 // analysiere nur die letzten 80 Halbzüge
        final long TIME_BUDGET_MS = 20_000L;      // ~20 Sekunden Budget
        final int TOP_K = 8;                      // Top-K Wurzelzüge
        final int ANALYSIS_DEPTH = Math.min(3, ai.getDepth()); // Tiefe 2–3 reicht meist

        final int totalPlies = plies.size();
        final int startIndex = Math.max(0, totalPlies - MAX_PLIES);
        final int toAnalyze = totalPlies - startIndex;

        final JDialog progress = new JDialog(frame,"Analysiere…",true);
        final JProgressBar bar=new JProgressBar(0, Math.max(1,toAnalyze));
        bar.setStringPainted(true);
        bar.setBorder(new EmptyBorder(10,10,10,10));
        progress.add(bar);
        progress.setSize(320,90);
        progress.setLocationRelativeTo(frame);

        SwingWorker<AnalysisResult,Integer> worker = new SwingWorker<>() {
            @Override protected AnalysisResult doInBackground(){
                int sumLossW=0, sumLossB=0, countW=0, countB=0;
                List<MoveAnalysis> rows=new ArrayList<>();
                long startTime = System.currentTimeMillis();
                boolean truncated=false;
                int processed=0;

                for(int i=startIndex;i<totalPlies;i++){
                    PlyRecord pr = plies.get(i);
                    Side mover = pr.before.sideToMove;

                    int bestScore   = ai.bestScoreApprox(pr.before, ANALYSIS_DEPTH, TOP_K);
                    int chosenScore = ai.scoreMove(pr.before, pr.move, ANALYSIS_DEPTH);

                    int loss = Math.max(0, toCp(bestScore) - toCp(chosenScore));
                    int evalAfterW = (mover==Side.WHITE) ? toCp(chosenScore) : -toCp(chosenScore);
                    String label = classify(loss, bestScore, chosenScore);

                    if(mover==Side.WHITE){ sumLossW+=loss; countW++; } else { sumLossB+=loss; countB++; }

                    int moveNo = pr.before.fullmoveNumber;
                    String moveStr = pretty(pr.move);

                    List<AI.ScoredMove> root = ai.analyzeRoot(pr.before, 1); // nur 1 Ply fürs Label – billig
                    root.sort((x,y)-> Integer.compare(y.score, x.score));
                    String bestStr = root.isEmpty()? moveStr : pretty(root.get(0).move);

                    rows.add(new MoveAnalysis(i, moveNo, mover, moveStr, bestStr, loss, evalAfterW, label));
                    processed++;
                    publish(processed);

                    if(System.currentTimeMillis() - startTime > TIME_BUDGET_MS){
                        truncated=true;
                        break;
                    }
                }

                int acplW = countW==0?0: (int)Math.round((double)sumLossW/countW);
                int acplB = countB==0?0: (int)Math.round((double)sumLossB/countB);

                double accW = Math.max(0, 100.0 - acplW/12.0);
                double accB = Math.max(0, 100.0 - acplB/12.0);

                return new AnalysisResult(rows, round1(accW), round1(accB), acplW, acplB, truncated, processed, toAnalyze);
            }
            @Override protected void process(List<Integer> chunks){
                bar.setValue(chunks.get(chunks.size()-1));
            }
            @Override protected void done(){
                progress.dispose();
                try{ showAnalysisDialog(get()); } catch(Exception ex){
                    JOptionPane.showMessageDialog(frame,"Analyse fehlgeschlagen.","Analyse",JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
        progress.setVisible(true);
    }

    private static int toCp(int score){
        return (Math.abs(score) >= AI.MATE/2) ? (score>0? 10000 : -10000) : score;
    }
    private static String classify(int lossCp, int bestScore, int chosenScore){
        int loss = Math.abs(lossCp);
        if(loss <= 20)  return "Bester Zug";
        if(loss <= 60)  return "Sehr gut";
        if(loss <= 100) return "Gut";
        if(loss <= 200) return "Ungenauigkeit";
        if(loss <= 400) return "Fehler";
        return "Patzer (Blunder)";
    }
    private static double round1(double x){ return Math.round(x*10.0)/10.0; }

    private void showAnalysisDialog(AnalysisResult ar){
        String[] cats={"Bester Zug","Sehr gut","Gut","Ungenauigkeit","Fehler","Patzer (Blunder)"};
        Map<String,Integer> wCnt=new LinkedHashMap<>(), bCnt=new LinkedHashMap<>();
        for(String c:cats){ wCnt.put(c,0); bCnt.put(c,0); }
        for(MoveAnalysis r: ar.rows){
            if(r.side==Side.WHITE) wCnt.put(r.label, wCnt.get(r.label)+1);
            else bCnt.put(r.label, bCnt.get(r.label)+1);
        }

        JDialog dlg=new JDialog(frame,"Partie‑Analyse (Schnell)",true);
        dlg.setLayout(new BorderLayout(10,10));
        dlg.getRootPane().setBorder(new EmptyBorder(10,10,10,10));

        JPanel top=new JPanel(new GridLayout(3,1,6,6));
        JLabel accLine = new JLabel("Genauigkeit (Schätzung):  Weiß " + ar.accWhite + "%   |   Schwarz " + ar.accBlack + "%");
        JLabel acplLine= new JLabel("ACPL: Weiß " + ar.acplWhite + "   |   Schwarz " + ar.acplBlack + "   (niedriger ist besser)");
        String trunc = ar.truncated ? ("Hinweis: Quick‑Analyse – " + ar.analyzed + "/" + ar.total + " Halbzüge gewertet, Top‑K=8, Tiefe≈2–3.")
                : "Quick‑Analyse – vollständige Auswertung.";
        JLabel info = new JLabel(trunc);
        accLine.setFont(accLine.getFont().deriveFont(Font.BOLD, 15f));
        top.add(accLine); top.add(acplLine); top.add(info);
        dlg.add(top, BorderLayout.NORTH);

        JTable table=new JTable(new AbstractTableModel(){
            final String[] cols={"#", "Seite", "Zug", "Kategorie", "Verlust (cp)", "Bester Zug", "Eval nach Zug (Weiß)"};
            @Override public int getRowCount(){ return ar.rows.size(); }
            @Override public int getColumnCount(){ return cols.length; }
            @Override public String getColumnName(int c){ return cols[c]; }
            @Override public Object getValueAt(int r,int c){
                MoveAnalysis m=ar.rows.get(r);
                return switch(c){
                    case 0 -> (m.side==Side.WHITE? m.moveNumber+".": m.moveNumber+"...");
                    case 1 -> m.side.toString();
                    case 2 -> m.moveStr;
                    case 3 -> m.label;
                    case 4 -> m.lossCp;
                    case 5 -> m.bestStr;
                    case 6 -> (m.evalAfterW>0? "+" : "") + m.evalAfterW;
                    default -> "";
                };
            }
        });
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(4).setMaxWidth(100);
        dlg.add(new JScrollPane(table), BorderLayout.CENTER);

        JLabel note=new JLabel("<html><i>Hinweis:</i> Quick‑Analyse nutzt eine reduzierte Suchtiefe und nur die besten Wurzelzüge. " +
                "Für exaktere Ergebnisse könnte man später eine Tiefenanalyse hinzufügen.</html>");
        note.setForeground(new Color(90,90,90));
        dlg.add(note, BorderLayout.SOUTH);

        dlg.setSize(840, 540);
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
    }

    // ---------- Eval-Bar ----------
    final class EvalBar extends JComponent {
        private int evalCp = 0; // >0 = Vorteil Weiß
        private boolean whiteBottom = true;
        void setEvalCp(int cp, boolean whiteBottom){
            this.evalCp = cp;
            this.whiteBottom = whiteBottom;
            repaint();
        }
        @Override public Dimension getPreferredSize(){ return new Dimension(220, 18); }
        @Override protected void paintComponent(Graphics g){
            Graphics2D g2=(Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(), h=getHeight();
            g2.setColor(new Color(0,0,0,40)); g2.fillRoundRect(0,0,w,h,10,10);
            double cp = Math.max(-1200, Math.min(1200, evalCp));
            double pWhite = 1.0/(1.0 + Math.exp(-cp/400.0));
            double pBottom = whiteBottom? pWhite : (1.0-pWhite);
            int fill = (int)Math.round(pBottom * (w-4));
            g2.setColor(new Color(60,160,90)); g2.fillRoundRect(2,2,fill, h-4,10,10);
            g2.setColor(new Color(200,60,60)); g2.fillRoundRect(2+fill,2,(w-4)-fill, h-4,10,10);
            g2.setColor(Color.DARK_GRAY); g2.drawRoundRect(0,0,w-1,h-1,10,10);
            String txt = (evalCp>900?"+M": evalCp<-900?"-M" : (evalCp>0? "+"+evalCp : ""+evalCp));
            g2.setColor(Color.WHITE); g2.drawString("Eval: "+txt+" cp", 8, h-6);
            g2.dispose();
        }
    }

    // ---------- Zeichenbrett (mit Drag&Drop + Animation + deutlichem Hint) ----------
    final class BoardPanel extends JPanel {
        private int paintDragX = 0, paintDragY = 0;   // last drawn top-left of floating piece

        // --- Layout / Farben
        final int TILE=86;
        final int MARGIN=28;
        final Color LIGHT=new Color(0xEE,0xEE,0xD2);
        final Color DARK =new Color(0x76,0x96,0x56);
        final Color SEL  =new Color(72,136,248,120);
        final Color MOVE =new Color(40,40,40,110);
        final Color CAP  =new Color(210,60,60,160);
        final Color LAST =new Color(246,246,105,160);
        final Color CHECK=new Color(255,70,70,150);

        // Hint-Overlays (kontrastreich)
        final Color HINT_FROM_OVER = new Color(255, 196, 0, 160);   // amber
        final Color HINT_TO_OVER   = new Color(64, 200, 255, 170);  // cyan

        // --- Drag & Drop
        private boolean dragging=false;
        private int dragFrom=-1;
        private Piece dragPiece=null;
        private int dragX=0, dragY=0; // Mausposition
        private int dragOffsetX=0, dragOffsetY=0; // Offset zwischen Klickpunkt und Feld
        private Timer dragTimer=null;           // regelmäßiges Repaint für flüssiges Ziehen
        // --- Drag capture via glass pane + window watcher
        private JComponent glass = null;
        private final MouseAdapter glassForwarder = new MouseAdapter() {
            private void forward(MouseEvent e){
                if (!dragging) return;
                // Forward to the board's coordinate space
                MouseEvent conv = SwingUtilities.convertMouseEvent((Component)e.getSource(), e, BoardPanel.this);
                if (e.getID() == MouseEvent.MOUSE_RELEASED) onRelease(conv);
                else onDrag(conv);
            }
            @Override public void mouseDragged(MouseEvent e){ forward(e); }
            @Override public void mouseMoved(MouseEvent e){ forward(e); }
            @Override public void mouseReleased(MouseEvent e){ forward(e); }
        };
        private WindowAdapter windowWatcher = null;

        // --- Animation
        private boolean animating=false;
        private final int ANIM_MS=220;       // Dauer der Zuganimation
        private Timer animTimer=null;    // <- bleibt, aber wir setzen ihn künftig nach stop() auf null
        private long animStart=0;
        private Board animBoard=null;        // Stellung vor dem Zug
        private Move animMove=null;
        private Piece animPiece=null;
        private Runnable animDone=null;
        private int animOffsetX = 0;
        private int animOffsetY = 0;



        private void endDrag() {
            if (dragTimer != null) {
                dragTimer.stop();
                dragTimer = null;             // <-- ensure GC + no repeats
            }
            uninstallGlass();                  // <-- NEW: always remove glass capture

            dragging = false;
            dragPiece = null;
            dragFrom = -1;
            dragOffsetX = dragOffsetY = 0;
        }

        private void installGlass(){
            if (glass != null) return;
            glass = (JComponent) frame.getGlassPane();
            glass.setVisible(true);
            glass.setOpaque(false);
            glass.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            glass.addMouseListener(glassForwarder);
            glass.addMouseMotionListener(glassForwarder);
        }
        private void uninstallGlass(){
            if (glass == null) return;
            glass.removeMouseListener(glassForwarder);
            glass.removeMouseMotionListener(glassForwarder);
            glass.setCursor(Cursor.getDefaultCursor());
            glass.setVisible(false);
            glass = null;
        }

        BoardPanel(){
            setPreferredSize(new Dimension(MARGIN*2 + TILE*8 + 8, MARGIN*2 + TILE*8 + 8));
            setBackground(new Color(24,28,28));
            setFocusable(true);            // wichtig für Keyboard-Shortcuts
            setDoubleBuffered(true);       // flüssiges Neuzeichnen beim Draggen
            // Tastatur-Shortcuts und Drag&Drop reagieren erst zuverlässig,
            // wenn das Panel selbst den Fokus hält
            SwingUtilities.invokeLater(this::requestFocusInWindow);

            MouseAdapter ma = new MouseAdapter(){
                @Override public void mousePressed(MouseEvent e){ onPress(e); }
                @Override public void mouseDragged(MouseEvent e){ onDrag(e); }
                @Override public void mouseReleased(MouseEvent e){ onRelease(e); }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);

            // ESC cancels any stuck drag
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelDrag");
            getActionMap().put("cancelDrag", new AbstractAction(){
                @Override public void actionPerformed(ActionEvent e){ endDrag(); repaint(); }
            });

            // Cancel drag if the window deactivates / loses focus
            windowWatcher = new WindowAdapter() {
                @Override public void windowDeactivated(WindowEvent e){ endDrag(); }
                @Override public void windowLostFocus(WindowEvent e){ endDrag(); }
            };
            frame.addWindowFocusListener(windowWatcher);
            frame.addWindowListener(windowWatcher);

            // Shortcuts: H=Hint, U=Undo
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke('H'), "hint");
            getActionMap().put("hint", new AbstractAction(){
                @Override public void actionPerformed(ActionEvent e){ ChessGUI.this.onHint(); }
            });
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke('U'), "undo");
            getActionMap().put("undo", new AbstractAction(){
                @Override public void actionPerformed(ActionEvent e){ ChessGUI.this.onUndo(); }
            });
        }

        // ------ Animation API
        void animateMove(Board pre, Move m, Runnable done){
            endDrag();
            selected = -1;
            legalFromSelected = List.of();
            hintMove = null;

            animating = true;
            animStart = System.currentTimeMillis();
            animBoard = pre;
            animMove = m;
            animPiece = pre.at(m.from);
            animDone = done;

            // Pre-calc offsets so there’s no first-frame jump
            FontMetrics fm = getFontMetrics(getBestPieceFont((int)(TILE * 0.82)));
            String glyph = String.valueOf(animPiece.symbolUnicode());
            int glyphW = fm.stringWidth(glyph);
            animOffsetX = (TILE - glyphW) / 2;
            animOffsetY = (TILE + fm.getAscent() - fm.getDescent()) / 2 - fm.getAscent();

            if(animTimer != null) { animTimer.stop(); animTimer = null; }
            animTimer = new Timer(1000/60, e -> {
                long t = System.currentTimeMillis() - animStart;
                if(t >= ANIM_MS){
                    animTimer.stop();
                    animTimer = null;
                    animating = false;
                    repaint();
                    if(animDone != null) SwingUtilities.invokeLater(animDone);
                } else {
                    repaint();
                }
            });
            animTimer.start();
        }

        // ------ DnD-Handler
        private void onPress(MouseEvent e){
            if(e.getButton() != MouseEvent.BUTTON1) return;

            // NEW: don’t start a drag while the AI/animation is running
            if (busy || animating) { beep(); return; }

            requestFocusInWindow();
            int i = pointToSquare(e.getX(), e.getY());
            if(i==-1) return;
            Piece p = board.at(i);
            boolean allowed = (p!=null && p.side==board.sideToMove && p.side==human);
            if(!allowed){ beep(); return; }

            selected=i;
            legalFromSelected = board.legalMoves().stream().filter(m -> m.from==selected).collect(Collectors.toList());

            dragging=true; dragFrom=i; dragPiece=p; dragX=e.getX(); dragY=e.getY();
            Point tl = boardIndexToVisualXY(i);
            dragOffsetX = dragX - tl.x;
            dragOffsetY = dragY - tl.y;
            paintDragX = dragX - dragOffsetX;
            paintDragY = dragY - dragOffsetY;
            repaint(new Rectangle(paintDragX, paintDragY, TILE, TILE));

            // regelmäßiges Repaint, falls keine Drag-Events eintreffen


            // NEW: capture all mouse events within the frame while dragging
            installGlass();

            // (Optional) If you keep the Toolkit listener, you can remove it now;
            // glass pane makes it unnecessary.
            repaint();
        }
        private void onDrag(MouseEvent e){
            if(!dragging || busy || animating) return;

            // If the button is no longer down, treat as release
            if ((e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0) {
                onRelease(e);
                return;
            }

            // Save old floating-piece rect
            int oldX = paintDragX;
            int oldY = paintDragY;

            // Update mouse + floating-piece top-left
            dragX = e.getX();
            dragY = e.getY();
            paintDragX = dragX - dragOffsetX;
            paintDragY = dragY - dragOffsetY;

            // Repaint only the area that actually changed
            int pad = 3; // small padding for AA/shadow
            Rectangle r1 = new Rectangle(oldX - pad, oldY - pad, TILE + 2*pad, TILE + 2*pad);
            Rectangle r2 = new Rectangle(paintDragX - pad, paintDragY - pad, TILE + 2*pad, TILE + 2*pad);
            repaint(r1.union(r2));
        }

        private void onRelease(MouseEvent e){
            if(!dragging) return;
            endDrag();

            int to = pointToSquare(e.getX(), e.getY());
            if(to==-1){
                selected=-1; legalFromSelected=List.of();
                repaint();
                return;
            }

            List<Move> candidates = legalFromSelected.stream()
                    .filter(m -> m.to==to)
                    .collect(Collectors.toList());
            if(candidates.isEmpty()){
                selected=-1; legalFromSelected=List.of();
                repaint();
                return;
            }

            Move chosen;
            if(candidates.size()==1 && !candidates.get(0).isPromotion()){
                chosen=candidates.get(0);
            } else {
                PieceType promo = askPromotion(board.sideToMove);
                Move m = candidates.stream().filter(x -> Objects.equals(x.promotion, promo)).findFirst().orElse(null);
                if(m==null) m = candidates.stream().filter(x -> Objects.equals(x.promotion, PieceType.QUEEN)).findFirst().orElse(candidates.get(0));
                chosen=m;
            }

            // Animation + Move starten
            ChessGUI.this.playMove(chosen, () -> status.setText("Du bist dran ("+human+")."), false);

            selected=-1; legalFromSelected=List.of();
            repaint();
        }

        // ------ Hilfen
        private PieceType askPromotion(Side side){
            boolean needsPromo = legalFromSelected.stream().anyMatch(Move::isPromotion);
            if(!needsPromo) return null;
            String[] names={"Dame (Q)","Turm (R)","Läufer (B)","Springer (N)"};
            PieceType[] types={PieceType.QUEEN,PieceType.ROOK,PieceType.BISHOP,PieceType.KNIGHT};
            int r=JOptionPane.showOptionDialog(frame,"Umwandlung – wähle eine Figur:","Umwandlung",
                    JOptionPane.DEFAULT_OPTION,JOptionPane.QUESTION_MESSAGE,null,names,names[0]);
            return r<0? PieceType.QUEEN : types[r];
        }

        private int pointToSquare(int px,int py){
            int x=px - MARGIN - 4, y=py - MARGIN - 4;
            if(x<0||y<0) return -1;
            int f=x/TILE, r=y/TILE;
            if(f<0||f>7||r<0||r>7) return -1;
            return visualToBoardIndex(f,r);
        }
        private int visualToBoardIndex(int f,int r){ return flip ? Board.idx(7-f, 7-r) : Board.idx(f,r); }
        private Point boardIndexToVisualXY(int index){
            int f=Board.file(index), r=Board.rank(index);
            if(flip){ f=7-f; r=7-r; }
            int x=MARGIN + 4 + f*TILE;
            int y=MARGIN + 4 + r*TILE;
            return new Point(x,y);
        }

        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);
            Graphics2D g2=(Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Hintergrund & Rahmen
            GradientPaint gp=new GradientPaint(0,0,new Color(34,38,38), getWidth(), getHeight(), new Color(22,24,24));
            g2.setPaint(gp); g2.fillRect(0,0,getWidth(),getHeight());
            int bw=TILE*8+8, bh=TILE*8+8;
            int bx=MARGIN, by=MARGIN;
            g2.setColor(new Color(0,0,0,60)); g2.fillRoundRect(bx-6,by-6,bw+12,bh+12,16,16);
            g2.setColor(new Color(0,0,0,110)); g2.fillRoundRect(bx-2,by-2,bw+4,bh+4,16,16);

            // Felder
            for(int r=0;r<8;r++){
                for(int f=0;f<8;f++){
                    int bi=visualToBoardIndex(f,r);
                    int x=MARGIN+4+f*TILE, y=MARGIN+4+r*TILE;
                    g2.setColor(((f+r)%2==0)? LIGHT : DARK);
                    g2.fillRect(x,y,TILE,TILE);
                    if(lastMove!=null && (bi==lastMove.from || bi==lastMove.to)){
                        g2.setColor(LAST); g2.fillRect(x,y,TILE,TILE);
                    }
                }
            }

            // Check
            if(board.isInCheck(board.sideToMove) && !animating){
                int k=board.kingSquare(board.sideToMove);
                Point p=boardIndexToVisualXY(k);
                g2.setColor(CHECK); g2.fillRect(p.x,p.y,TILE,TILE);
            }

            // Auswahl + Ziele (nur wenn nicht animiert)
            if(selected!=-1 && !animating){
                Point s=boardIndexToVisualXY(selected);
                g2.setColor(SEL); g2.fillRect(s.x,s.y,TILE,TILE);
                for(Move m: legalFromSelected){
                    Point p=boardIndexToVisualXY(m.to);
                    int cx=p.x+TILE/2, cy=p.y+TILE/2;
                    if(board.at(m.to)!=null || m.isCapture){
                        g2.setStroke(new BasicStroke(3f)); g2.setColor(CAP);
                        g2.drawOval(p.x+6,p.y+6,TILE-12,TILE-12);
                    } else {
                        g2.setColor(MOVE); int d=TILE/6; g2.fillOval(cx-d, cy-d, 2*d, 2*d);
                    }
                }
            }

            // Figuren (normal / Drag / Animation)
            Font pieceFont=getBestPieceFont((int)(TILE*0.82));
            g2.setFont(pieceFont);
            FontMetrics fm=g2.getFontMetrics();

            if(animating && animBoard != null && animMove != null){
                int hideCap = -1;
                if(animMove.enPassant){
                    hideCap = (animPiece.side == Side.WHITE) ? (animMove.to - 8) : (animMove.to + 8);
                } else if (animBoard.at(animMove.to) != null) {
                    hideCap = animMove.to;
                }

                for(int r = 0; r < 8; r++){
                    for(int f = 0; f < 8; f++){
                        int bi = visualToBoardIndex(f, r);
                        if(bi == animMove.from || bi == hideCap) continue;
                        Piece p = animBoard.at(bi);
                        if(p == null) continue;
                        drawGlyph(g2, fm, p, MARGIN + 4 + f * TILE, MARGIN + 4 + r * TILE);
                    }
                }

                Point a = boardIndexToVisualXY(animMove.from);
                Point b = boardIndexToVisualXY(animMove.to);
                double t = Math.min(1.0, (System.currentTimeMillis() - animStart) / (double) ANIM_MS);
                double s = 0.5 - 0.5 * Math.cos(Math.PI * t);

                int x = (int) Math.round(a.x + (b.x - a.x) * s) + animOffsetX;
                int y = (int) Math.round(a.y + (b.y - a.y) * s) + animOffsetY;

                drawGlyph(g2, fm, animPiece, x, y);
            } else {
                // Normal + Drag: zeichne Brett (ggf. ohne Drag-Quelle)
                for(int r=0;r<8;r++){
                    for(int f=0;f<8;f++){
                        int bi=visualToBoardIndex(f,r);
                        if(dragging && bi==dragFrom) continue; // Stück wird oben drüber gezeichnet
                        Piece p=board.at(bi); if(p==null) continue;
                        drawGlyph(g2,fm,p, MARGIN+4+f*TILE, MARGIN+4+r*TILE);
                    }
                }
                // Ziehendes Stück semi-transparent am Cursor zeichnen
                if(dragging && dragPiece!=null){
                    int x = dragX - dragOffsetX;
                    int y = dragY - dragOffsetY;
                    drawGlyph(g2,fm,dragPiece, x, y, 0.75f);
                }
            }

            // Koordinaten
            g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g2.setColor(new Color(30,30,30));
            for(int f=0; f<8; f++){
                char fileChar=(char)((flip? 'h'-f : 'a'+f));
                g2.drawString(String.valueOf(fileChar), MARGIN+4+f*TILE + TILE-12, MARGIN+4+8*TILE + 18);
            }
            for(int r=0; r<8; r++){
                char rankChar=(char)((flip? '1'+r : '8'-r));
                g2.drawString(String.valueOf(rankChar), MARGIN+4 - 18, MARGIN+4 + r*TILE + 14);
            }

            // Hint – kontrastreich: Overlays
            if(hintMove!=null && !animating){
                Point af=boardIndexToVisualXY(hintMove.from);
                Point bf=boardIndexToVisualXY(hintMove.to);

                // Overlays
                g2.setColor(HINT_FROM_OVER); g2.fillRect(af.x, af.y, TILE, TILE);
                g2.setColor(HINT_TO_OVER);   g2.fillRect(bf.x, bf.y, TILE, TILE);
            }

            g2.dispose();
        }

        private void drawGlyph(Graphics2D g2, FontMetrics fm, Piece p, int x, int y){
            drawGlyph(g2, fm, p, x, y, 1f);
        }
        // alpha=1.0 => normal, <1.0 => transparent (für Dragging)
        private void drawGlyph(Graphics2D g2, FontMetrics fm, Piece p, int x, int y, float alpha){
            String s=String.valueOf(p.symbolUnicode());
            int tx=x + (TILE - fm.stringWidth(s))/2;
            int ty=y + (TILE + fm.getAscent() - fm.getDescent())/2;
            Composite old=g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g2.setColor(new Color(0,0,0,90)); g2.drawString(s, tx+2, ty+2);
            g2.setColor(p.side==Side.WHITE? Color.WHITE : Color.BLACK);
            g2.drawString(s, tx, ty);
            g2.setComposite(old);
        }
        private Font getBestPieceFont(int px){
            Font f = new Font("Segoe UI Symbol", Font.PLAIN, px);
            if(!isFontSupported(f)) f = new Font("Arial Unicode MS", Font.PLAIN, px);
            if(!isFontSupported(f)) f = new Font("SansSerif", Font.PLAIN, px);
            return f;
        }
        private boolean isFontSupported(Font f){
            FontMetrics fm=getFontMetrics(f);
            return fm.charWidth('♔')>0 && fm.charWidth('♚')>0;
        }
    }
}
