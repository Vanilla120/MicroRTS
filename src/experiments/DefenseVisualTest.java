package experiments;
import ai.abstraction.HeavyRush;
import ai.abstraction.LightRush;
import ai.abstraction.RangedRush;
import ai.abstraction.WorkerRush;
import ai.core.AI;
import ai.custom.AdaptiveDefenseAI;
import ai.custom.FixedDefenseAI;
import gui.PhysicalGameStatePanel;
import javax.swing.JFrame;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.units.UnitTypeTable;
public final class DefenseVisualTest {
    private static final int MAX_CYCLES = 5000;
    private static final int PERIOD_MS = 20;
    private DefenseVisualTest() {
    }
    public static void main(String[] args) throws Exception {
        String attackerName =
                args.length >= 1
                        ? args[0]
                        : "WorkerRush";
        String mapPath =
                args.length >= 2
                        ? args[1]
                        : "maps/16x16/basesWorkers16x16.xml";
        int defenderPlayer =
                args.length >= 3
                        ? Integer.parseInt(args[2])
                        : 0;
        if (defenderPlayer != 0 && defenderPlayer != 1) {
            throw new IllegalArgumentException(
                    "defenderPlayer must be 0 or 1"
            );
        }
        String defenderName =
                args.length >= 4
                        ? args[3]
                        : "FixedDefenseAI";
        UnitTypeTable utt = new UnitTypeTable();
        PhysicalGameState pgs =
                PhysicalGameState.load(mapPath, utt);
        GameState gs = new GameState(pgs, utt);
        AI defender =
                createDefender(defenderName, utt);
        AI attacker =
                createAttacker(attackerName, utt);
        AI player0 =
                defenderPlayer == 0
                        ? defender
                        : attacker;
        AI player1 =
                defenderPlayer == 0
                        ? attacker
                        : defender;
        JFrame window =
                PhysicalGameStatePanel.newVisualizer(
                        gs,
                        640,
                        640,
                        false,
                        PhysicalGameStatePanel.COLORSCHEME_BLACK
                );
        window.setTitle(
                defenderName
                        + " vs "
                        + attackerName
                        + " | defender=P"
                        + defenderPlayer
        );
        boolean gameOver = false;
        long nextUpdate =
                System.currentTimeMillis() + PERIOD_MS;
        while (!gameOver && gs.getTime() < MAX_CYCLES) {
            if (System.currentTimeMillis() >= nextUpdate) {
                PlayerAction action0 =
                        player0.getAction(0, gs);
                PlayerAction action1 =
                        player1.getAction(1, gs);
                gs.issueSafe(action0);
                gs.issueSafe(action1);
                gameOver = gs.cycle();
                window.repaint();
                nextUpdate += PERIOD_MS;
            } else {
                Thread.sleep(1);
            }
        }
        int winner = gs.winner();
        player0.gameOver(winner);
        player1.gameOver(winner);
        int attackerPlayer = 1 - defenderPlayer;
        String winnerText;
        if (winner == -1) {
            winnerText = "DRAW_OR_TIME_LIMIT";
        } else if (winner == defenderPlayer) {
            winnerText = "DEFENDER";
        } else if (winner == attackerPlayer) {
            winnerText = "ATTACKER";
        } else {
            winnerText = "UNKNOWN";
        }
        System.out.println();
        System.out.println("===== Match Result =====");
        System.out.println("defender       : " + defenderName);
        System.out.println("defenderPlayer : " + defenderPlayer);
        System.out.println("attacker       : " + attackerName);
        System.out.println("attackerPlayer : " + attackerPlayer);
        System.out.println("map            : " + mapPath);
        System.out.println("winner         : " + winnerText);
        System.out.println("winnerPlayer   : " + winner);
        System.out.println("endCycle       : " + gs.getTime());
    }
    /*
     * main()の外、DefenseVisualTestクラスの内部に配置する。
     */
    private static AI createDefender(
            String defenderName,
            UnitTypeTable utt) {
        switch (defenderName) {
            case "FixedDefenseAI":
                return new FixedDefenseAI(utt);
            case "AdaptiveDefenseAI":
                return new AdaptiveDefenseAI(utt);
            default:
                throw new IllegalArgumentException(
                        "Unknown defender: "
                                + defenderName
                                + ". Use FixedDefenseAI "
                                + "or AdaptiveDefenseAI."
                );
        }
    }
    private static AI createAttacker(
            String attackerName,
            UnitTypeTable utt) {
        switch (attackerName) {
            case "WorkerRush":
                return new WorkerRush(utt);
            case "LightRush":
                return new LightRush(utt);
            case "HeavyRush":
                return new HeavyRush(utt);
            case "RangedRush":
                return new RangedRush(utt);
            default:
                throw new IllegalArgumentException(
                        "Unknown attacker: "
                                + attackerName
                                + ". Use WorkerRush, LightRush, "
                                + "HeavyRush, or RangedRush."
                );
        }
    }
}
