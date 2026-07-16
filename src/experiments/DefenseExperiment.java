package experiments;
import ai.abstraction.HeavyRush;
import ai.abstraction.LightRush;
import ai.abstraction.RangedRush;
import ai.abstraction.WorkerRush;
import ai.core.AI;
import ai.custom.AdaptiveDefenseAI;
import ai.custom.FixedDefenseAI;
import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.units.Unit;
import rts.units.UnitTypeTable;
/**
 * 固定型防御AIと適応型防御AIを、
 * 複数の攻撃AI・マップ・開始位置で自動対戦させる。
 *
 * GUIは表示せず、結果をCSVへ保存する。
 */
public final class DefenseExperiment {
    private static final int MAX_CYCLES = 5000;
    private static final String[] DEFENDER_NAMES = {
        "FixedDefenseAI",
        "AdaptiveDefenseAI"
    };
    private static final String[] ATTACKER_NAMES = {
        "WorkerRush",
        "LightRush",
        "HeavyRush",
        "RangedRush"
    };
    private static final String[] MAP_PATHS = {
        "maps/custom/defense_open24.xml",
        "maps/custom/defense_wall24.xml"
    };
    private DefenseExperiment() {
    }
    public static void main(String[] args) throws Exception {
        String outputPath =
                args.length >= 1
                        ? args[0]
                        : "results/defense-experiment-v1.csv";
        Path output = Paths.get(outputPath);
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        int matchNumber = 0;
        try (
            BufferedWriter bufferedWriter =
                    Files.newBufferedWriter(
                            output,
                            StandardCharsets.UTF_8
                    );
            PrintWriter writer =
                    new PrintWriter(bufferedWriter)
        ) {
            writeHeader(writer);
            for (String mapPath : MAP_PATHS) {
                for (String defenderName : DEFENDER_NAMES) {
                    for (String attackerName : ATTACKER_NAMES) {
                        for (
                            int defenderPlayer = 0;
                            defenderPlayer <= 1;
                            defenderPlayer++
                        ) {
                            matchNumber++;
                            System.out.println();
                            System.out.println(
                                    "Running match "
                                    + matchNumber
                                    + "/32"
                            );
                            System.out.println(
                                    defenderName
                                    + " vs "
                                    + attackerName
                                    + " | map="
                                    + mapPath
                                    + " | defender=P"
                                    + defenderPlayer
                            );
                            MatchResult result =
                                    runMatch(
                                            mapPath,
                                            defenderName,
                                            attackerName,
                                            defenderPlayer
                                    );
                            writer.println(result.toCsv());
                            writer.flush();
                            System.out.println(
                                    "Result: "
                                    + result.practicalResult
                                    + ", officialWinner="
                                    + result.officialWinner
                                    + ", endCycle="
                                    + result.endCycle
                                    + ", lastDamageCycle="
                                    + result.lastDamageCycle
                            );
                        }
                    }
                }
            }
        }
        System.out.println();
        System.out.println(
                "Completed 32 matches."
        );
        System.out.println(
                "CSV: " + output.toAbsolutePath()
        );
    }
    /**
     * 1試合をGUIなしで実行する。
     */
    private static MatchResult runMatch(
            String mapPath,
            String defenderName,
            String attackerName,
            int defenderPlayer) throws Exception {
        int attackerPlayer = 1 - defenderPlayer;
        /*
         * 試合ごとにUnitTypeTableとAIを新しく作る。
         * AIの内部状態を別試合へ持ち越さないため。
         */
        UnitTypeTable utt = new UnitTypeTable();
        PhysicalGameState pgs =
                PhysicalGameState.load(mapPath, utt);
        GameState gs =
                new GameState(pgs, utt);
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
        boolean gameOver = false;
        int lastDamageCycle = -1;
        while (
            !gameOver
            && gs.getTime() < MAX_CYCLES
        ) {
            PlayerAction action0 =
                    player0.getAction(0, gs);
            PlayerAction action1 =
                    player1.getAction(1, gs);
            gs.issueSafe(action0);
            gs.issueSafe(action1);
            /*
             * cycle()前の各ユニットのHPを保存する。
             */
            Map<Long, Integer> hitPointsBefore =
                    snapshotHitPoints(pgs);
            gameOver = gs.cycle();
            /*
             * HP低下またはユニット撃破があった場合、
             * 最終交戦サイクルを更新する。
             */
            if (damageOccurred(hitPointsBefore, pgs)) {
                lastDamageCycle = gs.getTime();
            }
        }
        int officialWinner = gs.winner();
        player0.gameOver(officialWinner);
        player1.gameOver(officialWinner);
        UnitCounts defenderCounts =
                countUnits(pgs, defenderPlayer);
        UnitCounts attackerCounts =
                countUnits(pgs, attackerPlayer);
        String stopReason =
                gameOver
                        ? "GAME_OVER"
                        : "MAX_CYCLES";
        String practicalResult =
                determinePracticalResult(
                        defenderCounts,
                        attackerCounts
                );
        return new MatchResult(
                defenderName,
                attackerName,
                mapPath,
                defenderPlayer,
                attackerPlayer,
                officialWinner,
                practicalResult,
                stopReason,
                gs.getTime(),
                lastDamageCycle,
                gs.getPlayer(defenderPlayer).getResources(),
                gs.getPlayer(attackerPlayer).getResources(),
                defenderCounts,
                attackerCounts
        );
    }
    /**
     * cycle()前の各ユニットのHPを、IDをキーとして保存する。
     */
    private static Map<Long, Integer> snapshotHitPoints(
            PhysicalGameState pgs) {
        Map<Long, Integer> result =
                new HashMap<>();
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() >= 0) {
                result.put(
                        unit.getID(),
                        unit.getHitPoints()
                );
            }
        }
        return result;
    }
    /**
     * 前サイクルからHPが減ったユニット、
     * または撃破されたユニットがあるか確認する。
     *
     * 新しく生産されたユニットはダメージとは扱わない。
     */
    private static boolean damageOccurred(
            Map<Long, Integer> hitPointsBefore,
            PhysicalGameState pgs) {
        for (
            Map.Entry<Long, Integer> entry
                    : hitPointsBefore.entrySet()
        ) {
            Unit currentUnit =
                    pgs.getUnit(entry.getKey());
            /*
             * 前サイクルに存在したユニットが消えていれば、
             * 撃破されたと判断する。
             */
            if (currentUnit == null) {
                return true;
            }
            if (
                currentUnit.getHitPoints()
                < entry.getValue()
            ) {
                return true;
            }
        }
        return false;
    }
    /**
     * 指定プレイヤーのユニットを種類別に数える。
     */
    private static UnitCounts countUnits(
            PhysicalGameState pgs,
            int player) {
        UnitCounts counts =
                new UnitCounts();
        for (Unit unit : pgs.getUnits()) {
            if (unit.getPlayer() != player) {
                continue;
            }
            counts.total++;
            if (unit.getType().canMove) {
                counts.mobile++;
            }
            switch (unit.getType().name) {
                case "Base":
                    counts.bases++;
                    break;
                case "Barracks":
                    counts.barracks++;
                    break;
                case "Worker":
                    counts.workers++;
                    break;
                case "Light":
                    counts.lights++;
                    break;
                case "Heavy":
                    counts.heavies++;
                    break;
                case "Ranged":
                    counts.ranged++;
                    break;
                default:
                    counts.other++;
                    break;
            }
        }
        return counts;
    }
    /**
     * 建物が残って正式終了しない場合を考慮した、
     * 実験上の暫定結果。
     */
    private static String determinePracticalResult(
            UnitCounts defender,
            UnitCounts attacker) {
        if (defender.bases == 0) {
            return "DEFENDER_BASE_LOST";
        }
        if (attacker.mobile == 0) {
            return "ATTACKER_MOBILE_ELIMINATED";
        }
        return "UNRESOLVED";
    }
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
                );
        }
    }
    private static void writeHeader(
            PrintWriter writer) {
        writer.println(
                "defender,"
                + "attacker,"
                + "map,"
                + "defenderPlayer,"
                + "attackerPlayer,"
                + "officialWinner,"
                + "practicalResult,"
                + "stopReason,"
                + "endCycle,"
                + "lastDamageCycle,"
                + "defenderResources,"
                + "attackerResources,"
                + "defenderBases,"
                + "defenderBarracks,"
                + "defenderWorkers,"
                + "defenderLights,"
                + "defenderHeavies,"
                + "defenderRanged,"
                + "defenderMobile,"
                + "defenderTotal,"
                + "attackerBases,"
                + "attackerBarracks,"
                + "attackerWorkers,"
                + "attackerLights,"
                + "attackerHeavies,"
                + "attackerRanged,"
                + "attackerMobile,"
                + "attackerTotal"
        );
    }
    private static final class UnitCounts {
        int bases;
        int barracks;
        int workers;
        int lights;
        int heavies;
        int ranged;
        int other;
        int mobile;
        int total;
    }
    private static final class MatchResult {
        final String defender;
        final String attacker;
        final String map;
        final int defenderPlayer;
        final int attackerPlayer;
        final int officialWinner;
        final String practicalResult;
        final String stopReason;
        final int endCycle;
        final int lastDamageCycle;
        final int defenderResources;
        final int attackerResources;
        final UnitCounts defenderCounts;
        final UnitCounts attackerCounts;
        MatchResult(
                String defender,
                String attacker,
                String map,
                int defenderPlayer,
                int attackerPlayer,
                int officialWinner,
                String practicalResult,
                String stopReason,
                int endCycle,
                int lastDamageCycle,
                int defenderResources,
                int attackerResources,
                UnitCounts defenderCounts,
                UnitCounts attackerCounts) {
            this.defender = defender;
            this.attacker = attacker;
            this.map = map;
            this.defenderPlayer = defenderPlayer;
            this.attackerPlayer = attackerPlayer;
            this.officialWinner = officialWinner;
            this.practicalResult = practicalResult;
            this.stopReason = stopReason;
            this.endCycle = endCycle;
            this.lastDamageCycle = lastDamageCycle;
            this.defenderResources = defenderResources;
            this.attackerResources = attackerResources;
            this.defenderCounts = defenderCounts;
            this.attackerCounts = attackerCounts;
        }
        String toCsv() {
            return defender
                    + "," + attacker
                    + "," + map
                    + "," + defenderPlayer
                    + "," + attackerPlayer
                    + "," + officialWinner
                    + "," + practicalResult
                    + "," + stopReason
                    + "," + endCycle
                    + "," + lastDamageCycle
                    + "," + defenderResources
                    + "," + attackerResources
                    + "," + defenderCounts.bases
                    + "," + defenderCounts.barracks
                    + "," + defenderCounts.workers
                    + "," + defenderCounts.lights
                    + "," + defenderCounts.heavies
                    + "," + defenderCounts.ranged
                    + "," + defenderCounts.mobile
                    + "," + defenderCounts.total
                    + "," + attackerCounts.bases
                    + "," + attackerCounts.barracks
                    + "," + attackerCounts.workers
                    + "," + attackerCounts.lights
                    + "," + attackerCounts.heavies
                    + "," + attackerCounts.ranged
                    + "," + attackerCounts.mobile
                    + "," + attackerCounts.total;
        }
    }
}
