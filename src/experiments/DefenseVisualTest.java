package experiments;

import ai.abstraction.HeavyRush;
import ai.abstraction.LightRush;
import ai.abstraction.RangedRush;
import ai.abstraction.WorkerRush;
import ai.core.AI;
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

        UnitTypeTable utt = new UnitTypeTable();

        PhysicalGameState pgs =
                PhysicalGameState.load(mapPath, utt);

        GameState gs = new GameState(pgs, utt);

        // Player 0: 自作の固定型防御AI
        AI defender = new FixedDefenseAI(utt);

        // Player 1: 標準Rush AI
        AI attacker = createAttacker(attackerName, utt);

        JFrame window =
                PhysicalGameStatePanel.newVisualizer(
                        gs,
                        640,
                        640,
                        false,
                        PhysicalGameStatePanel.COLORSCHEME_BLACK
                );

        window.setTitle(
                "FixedDefenseAI vs " + attackerName
        );

        boolean gameOver = false;
        long nextUpdate =
                System.currentTimeMillis() + PERIOD_MS;

        while (!gameOver && gs.getTime() < MAX_CYCLES) {
            if (System.currentTimeMillis() >= nextUpdate) {
                PlayerAction defenderAction =
                        defender.getAction(0, gs);

                PlayerAction attackerAction =
                        attacker.getAction(1, gs);

                gs.issueSafe(defenderAction);
                gs.issueSafe(attackerAction);

                gameOver = gs.cycle();

                window.repaint();
                nextUpdate += PERIOD_MS;
            } else {
                Thread.sleep(1);
            }
        }

        int winner = gs.winner();

        defender.gameOver(winner);
        attacker.gameOver(winner);

        System.out.println();
        System.out.println("===== Match Result =====");
        System.out.println("defender : FixedDefenseAI");
        System.out.println("attacker : " + attackerName);
        System.out.println("map      : " + mapPath);
        System.out.println("winner   : " + winner);
        System.out.println("endCycle : " + gs.getTime());
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
