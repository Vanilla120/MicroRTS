  /*
   * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ai.custom;
import ai.abstraction.AbstractionLayerAI;
import ai.abstraction.AbstractAction;
import ai.abstraction.Harvest;
import ai.abstraction.pathfinding.AStarPathFinding;
import ai.abstraction.pathfinding.PathFinding;
import ai.core.AI;
import ai.core.ParameterSpecification;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import rts.GameState;
import rts.PhysicalGameState;
import rts.Player;
import rts.PlayerAction;
import rts.units.*;
/**
 *
 * @author Cleyton
 */
public class AdaptiveDefenseAI extends AbstractionLayerAI {
    private static final int DEFENSE_RADIUS = 5;
    private static final int RALLY_RADIUS = 3;
    Random r = new Random();
    protected UnitTypeTable utt;
    UnitType workerType;
    UnitType baseType;
    UnitType barracksType;
    UnitType lightType;
    UnitType heavyType;
    UnitType rangedType;
    private int lastEnemyWorkers = -1;
    private int lastEnemyLights = -1;
    private int lastEnemyHeavies = -1;
    private int lastEnemyRanged = -1;
    private EnemyStrategy lastEnemyStrategy = null;
    /*
     * 現在の敵分類と、それに基づいて生産するユニット。
     */
    private EnemyStrategy currentEnemyStrategy = EnemyStrategy.UNKNOWN;
    private UnitType currentProductionType;
    /*
     * 同じ生産判断を毎サイクル表示しないための記録。
     */
    private UnitType lastLoggedProductionType = null;
    // These strategies behave similarly to WD,
    //with  the  difference  being  that  the  defense  line  is  formed  by
    //ranged and lights units for RD and LD, respectively. Since RD
    //and LD train ranged and lights units, they build a barracks with
    //their worker as soon as there is enough resources; the worker
    //returns to collecting resources once the barracks is built.
    public AdaptiveDefenseAI(UnitTypeTable a_utt) {
        this(a_utt, new AStarPathFinding());
    }
    
    
    public AdaptiveDefenseAI(UnitTypeTable a_utt, PathFinding a_pf) {
        super(a_pf);
        reset(a_utt);
    }
    @Override
    public void reset() {
        super.reset();
        currentEnemyStrategy =
                EnemyStrategy.UNKNOWN;
        currentProductionType = lightType;
        lastLoggedProductionType = null;
        resetEnemyObservation();
    }
    
    public void reset(UnitTypeTable a_utt) {
        utt = a_utt;
        workerType = utt.getUnitType("Worker");
        baseType = utt.getUnitType("Base");
        barracksType = utt.getUnitType("Barracks");
        lightType = utt.getUnitType("Light");
        heavyType = utt.getUnitType("Heavy");
        rangedType = utt.getUnitType("Ranged");
        currentEnemyStrategy = EnemyStrategy.UNKNOWN;
        /*
         * 敵編成が分からない初期状態では、
         * 固定型と同じLightを選ぶ。
         */
        currentProductionType = lightType;
        lastLoggedProductionType = null;
        resetEnemyObservation();
    }
    
    public AI clone() {
        return new AdaptiveDefenseAI(utt, pf);
    }
    /*
        This is the main function of the AI. It is called at each game cycle with the most up to date game state and
        returns which actions the AI wants to execute in this cycle.
        The input parameters are:
        - player: the player that the AI controls (0 or 1)
        - gs: the current game state
        This method returns the actions to be sent to each of the units in the gamestate controlled by the player,
        packaged as a PlayerAction.
     */
    public PlayerAction getAction(int player, GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        Player p = gs.getPlayer(player);
        EnemyComposition enemyComposition = analyzeEnemyComposition(player, pgs);
        EnemyStrategy enemyStrategy = classifyEnemyComposition(enemyComposition);
        logEnemyCompositionIfChanged(player, gs.getTime(), enemyComposition, enemyStrategy);
        /*
         * 観測した敵編成から、現在の生産方針を決める。
         */
        currentEnemyStrategy = enemyStrategy;
        currentProductionType = selectDefensiveUnitType(enemyStrategy);
        logProductionDecisionIfChanged(player, gs.getTime(), enemyStrategy, currentProductionType);
//        System.out.println("LightRushAI for player " + player + " (cycle " + gs.getTime() + ")");
        // behavior of bases:
        for (Unit u : pgs.getUnits()) {
            if (u.getType() == baseType
                    && u.getPlayer() == player
                    && gs.getActionAssignment(u) == null) {
                baseBehavior(u, p, pgs);
            }
        }
        // behavior of barracks:
        for (Unit u : pgs.getUnits()) {
            if (u.getType() == barracksType
                    && u.getPlayer() == player
                    && gs.getActionAssignment(u) == null) {
                barracksBehavior(u, p, pgs);
            }
        }
        // behavior of melee units:
        for (Unit u : pgs.getUnits()) {
            if (u.getType().canAttack && !u.getType().canHarvest
                    && u.getPlayer() == player
                    && gs.getActionAssignment(u) == null) {
                meleeUnitBehavior(u, p, gs);
            }
        }
        // behavior of workers:
        List<Unit> workers = new LinkedList<>();
        for (Unit u : pgs.getUnits()) {
            if (u.getType().canHarvest
                    && u.getPlayer() == player) {
                workers.add(u);
            }
        }
        workersBehavior(workers, p, pgs);
        // This method simply takes all the unit actions executed so far, and packages them into a PlayerAction
        return translateActions(player, gs);
    }
    /**
     * 観測した敵編成の分類。
     */
    private enum EnemyStrategy {
        UNKNOWN,
        WORKER_DOMINANT,
        LIGHT_DOMINANT,
        HEAVY_DOMINANT,
        RANGED_DOMINANT
    }
    private static final class EnemyComposition {
        int workers;
        int lights;
        int heavies;
        int ranged;
        int totalCombatUnits() {
            return lights + heavies + ranged;
        }
    }
    /**
     * 現在のゲーム状態に存在する敵ユニットを種類別に数える。
     *
     * Base、Barracks、Resourceは集計しない。
     */
    private EnemyComposition analyzeEnemyComposition(
            int player,
            PhysicalGameState pgs) {
        EnemyComposition result =
                new EnemyComposition();
        for (Unit unit : pgs.getUnits()) {
            // 中立資源などを除外
            if (unit.getPlayer() < 0) {
                continue;
            }
            // 自軍ユニットを除外
            if (unit.getPlayer() == player) {
                continue;
            }
            if (unit.getType() == workerType) {
                result.workers++;
            } else if (unit.getType() == lightType) {
                result.lights++;
            } else if (unit.getType() == heavyType) {
                result.heavies++;
            } else if (unit.getType() == rangedType) {
                result.ranged++;
            }
        }
        return result;
    }
    /**
     * 敵編成を主要ユニット種類に分類する。
     *
     * 戦闘ユニットが存在する場合は、
     * Light、Heavy、Rangedの中で最多の種類を採用する。
     *
     * 戦闘ユニットが存在せずWorkerだけがいる場合は、
     * WORKER_DOMINANTとする。
     *
     * 最多数が同数の場合はUNKNOWNとする。
     */
    private EnemyStrategy classifyEnemyComposition(
            EnemyComposition composition) {
        /*
         * 戦闘ユニットが存在しない場合。
         */
        if (composition.totalCombatUnits() == 0) {
            if (composition.workers > 0) {
                return EnemyStrategy.WORKER_DOMINANT;
            }
            return EnemyStrategy.UNKNOWN;
        }
        int maximumCombatCount =
                Math.max(
                        composition.lights,
                        Math.max(
                                composition.heavies,
                                composition.ranged
                        )
                );
        int numberOfLeaders = 0;
        if (composition.lights == maximumCombatCount) {
            numberOfLeaders++;
        }
        if (composition.heavies == maximumCombatCount) {
            numberOfLeaders++;
        }
        if (composition.ranged == maximumCombatCount) {
            numberOfLeaders++;
        }
        /*
         * 例えばLight 2体、Heavy 2体なら、
         * 単一の主要編成として判断できない。
         */
        if (numberOfLeaders != 1) {
            return EnemyStrategy.UNKNOWN;
        }
        if (composition.lights == maximumCombatCount) {
            return EnemyStrategy.LIGHT_DOMINANT;
        }
        if (composition.heavies == maximumCombatCount) {
            return EnemyStrategy.HEAVY_DOMINANT;
        }
        if (composition.ranged == maximumCombatCount) {
            return EnemyStrategy.RANGED_DOMINANT;
        }
        return EnemyStrategy.UNKNOWN;
    }
    /**
     * 敵編成に応じて生産する防御ユニットを選ぶ。
     *
     * この対応関係は今回の実験用ドクトリンであり、
     * MicroRTSに組み込まれた相性表ではない。
     */
    private UnitType selectDefensiveUnitType(
            EnemyStrategy strategy) {
        switch (strategy) {
            case WORKER_DOMINANT:
                /*
                 * Workerより戦闘能力と耐久力が高く、
                 * 比較的高速なLightを使用する。
                 */
                return lightType;
            case LIGHT_DOMINANT:
                /*
                 * 高い近接火力を持つHeavyを使用する。
                 */
                return heavyType;
            case HEAVY_DOMINANT:
                /*
                 * 遅い近接Heavyに対して、
                 * 射程を持つRangedを使用する。
                 */
                return rangedType;
            case RANGED_DOMINANT:
                /*
                 * 高速で接近でき、Rangedより耐久力がある
                 * Lightを使用する。
                 */
                return lightType;
            case UNKNOWN:
            default:
                /*
                 * 判断不能時は固定型と同じLightへ戻す。
                 */
                return lightType;
        }
    }
    /**
     * 生産するユニット種類が変わった場合だけ表示する。
     */
    private void logProductionDecisionIfChanged(
            int player,
            int cycle,
            EnemyStrategy strategy,
            UnitType productionType) {

        if (productionType == lastLoggedProductionType) {
            return;
        }

        String productionName =
                productionType == null
                        ? "NONE"
                        : productionType.name;

        System.out.println(
                "[AdaptiveDefenseAI]"
                + " player=" + player
                + " cycle=" + cycle
                + " strategy=" + strategy
                + " production=" + productionName
        );

        lastLoggedProductionType = productionType;
    }
    /**
     * 敵編成または分類が変化した場合だけ表示する。
     */
    private void logEnemyCompositionIfChanged(
            int player,
            int cycle,
            EnemyComposition composition,
            EnemyStrategy strategy) {
        boolean compositionChanged =
                composition.workers != lastEnemyWorkers
                || composition.lights != lastEnemyLights
                || composition.heavies != lastEnemyHeavies
                || composition.ranged != lastEnemyRanged;
        boolean strategyChanged =
                strategy != lastEnemyStrategy;
        if (!compositionChanged && !strategyChanged) {
            return;
        }
        System.out.println(
                "[AdaptiveDefenseAI]"
                + " player=" + player
                + " cycle=" + cycle
                + " enemyWorkers=" + composition.workers
                + " enemyLights=" + composition.lights
                + " enemyHeavies=" + composition.heavies
                + " enemyRanged=" + composition.ranged
                + " enemyCombatTotal="
                + composition.totalCombatUnits()
                + " classification=" + strategy
        );
        lastEnemyWorkers = composition.workers;
        lastEnemyLights = composition.lights;
        lastEnemyHeavies = composition.heavies;
        lastEnemyRanged = composition.ranged;
        lastEnemyStrategy = strategy;
    }
    private void resetEnemyObservation() {
        lastEnemyWorkers = -1;
        lastEnemyLights = -1;
        lastEnemyHeavies = -1;
        lastEnemyRanged = -1;
        lastEnemyStrategy = null;
    }
    public void baseBehavior(Unit u, Player p, PhysicalGameState pgs) {
        int nworkers = 0;
        for (Unit u2 : pgs.getUnits()) {
            if (u2.getType() == workerType
                    && u2.getPlayer() == p.getID()) {
                nworkers++;
            }
        }
        if (nworkers < 1 && p.getResources() >= workerType.cost) {
            train(u, workerType);
        }
    }
    /**
     * 現在の敵編成に対応するユニットを生産する。
     */
    public void barracksBehavior(Unit barracks, Player player, PhysicalGameState pgs) {
        UnitType selectedType = currentProductionType;
        /*
         * 初期化されていない場合でも、
         * 固定型と同じLightへフォールバックする。
         */
        if (selectedType == null) {
            selectedType = lightType;
        }
        if (player.getResources() >= selectedType.cost) {
            train(barracks, selectedType);
        }
    }
        /**
     * 戦闘ユニットを自軍基地周辺に留める固定防御行動。
     *
     * 敵が基地からDEFENSE_RADIUS以内にいる場合のみ迎撃する。
     * 敵がいない場合は基地からRALLY_RADIUS以内へ帰還する。
     */
    public void meleeUnitBehavior(
            Unit defender,
            Player player,
            GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        Unit ownBase =
                findClosestOwnBase(defender, player, pgs);
        /*
         * 自軍基地がすべて破壊されている場合は、
         * この初期版では現在位置で待機する。
         */
        if (ownBase == null) {
            idle(defender);
            return;
        }
        Unit closestThreat = null;
        int closestThreatDistance = Integer.MAX_VALUE;
        /*
         * 自軍基地の防御範囲内にいる敵を探す。
         */
        for (Unit candidate : pgs.getUnits()) {
            // 資源など、プレイヤーに属さないものを除外
            if (candidate.getPlayer() < 0) {
                continue;
            }
            // 味方を除外
            if (candidate.getPlayer() == player.getID()) {
                continue;
            }
            /*
             * BaseやBarracksなどの建物を除外する。
             * Workerと戦闘ユニットはcanMoveがtrueなので対象になる。
             */
            if (!candidate.getType().canMove) {
                continue;
            }
            int threatDistanceFromBase =
                    manhattanDistance(candidate, ownBase);
            // 基地から遠い敵は迎撃しない
            if (threatDistanceFromBase > DEFENSE_RADIUS) {
                continue;
            }
            int threatDistanceFromDefender =
                    manhattanDistance(candidate, defender);
            if (threatDistanceFromDefender
                    < closestThreatDistance) {
                closestThreat = candidate;
                closestThreatDistance =
                        threatDistanceFromDefender;
            }
        }
        /*
         * 防御範囲内に敵がいる場合は、
         * 防御ユニットから最も近い敵を迎撃する。
         */
        if (closestThreat != null) {
            attack(defender, closestThreat);
            return;
        }
        int defenderDistanceFromBase =
                manhattanDistance(defender, ownBase);
        /*
         * 敵がいない状態で基地から離れている場合は、
         * 基地周辺の空きマスへ戻る。
         */
        if (defenderDistanceFromBase > RALLY_RADIUS) {
            int[] returnPosition =
                    findReturnPosition(
                            defender,
                            ownBase,
                            pgs
                    );
            if (returnPosition != null) {
                move(
                        defender,
                        returnPosition[0],
                        returnPosition[1]
                );
            } else {
                idle(defender);
            }
            return;
        }
        // 基地周辺にいる場合はその場で待機
        idle(defender);
    }
        /**
     * 防御ユニットから最も近い自軍基地を返す。
     */
    private Unit findClosestOwnBase(
            Unit defender,
            Player player,
            PhysicalGameState pgs) {
        Unit closestBase = null;
        int closestDistance = Integer.MAX_VALUE;
        for (Unit candidate : pgs.getUnits()) {
            if (candidate.getPlayer() != player.getID()) {
                continue;
            }
            if (candidate.getType() != baseType) {
                continue;
            }
            int distance =
                    manhattanDistance(defender, candidate);
            if (distance < closestDistance) {
                closestBase = candidate;
                closestDistance = distance;
            }
        }
        return closestBase;
    }
        /**
     * 基地から距離1～RALLY_RADIUSにある空きマスのうち、
     * 防御ユニットから最も近い場所を返す。
     */
    private int[] findReturnPosition(
            Unit defender,
            Unit ownBase,
            PhysicalGameState pgs) {
        int bestX = -1;
        int bestY = -1;
        int bestDistanceFromDefender =
                Integer.MAX_VALUE;
        int bestDistanceFromBase =
                Integer.MAX_VALUE;
        for (int x = 0; x < pgs.getWidth(); x++) {
            for (int y = 0; y < pgs.getHeight(); y++) {
                int distanceFromBase =
                        manhattanDistance(
                                x,
                                y,
                                ownBase.getX(),
                                ownBase.getY()
                        );
                /*
                 * 基地自身のマスは除外し、
                 * 基地から距離1～3の範囲を候補にする。
                 */
                if (distanceFromBase < 1
                        || distanceFromBase
                        > RALLY_RADIUS) {
                    continue;
                }
                // 壁を除外
                if (pgs.getTerrain(x, y)
                        != PhysicalGameState.TERRAIN_NONE) {
                    continue;
                }
                // 他ユニットがいるマスを除外
                Unit occupant = pgs.getUnitAt(x, y);
                if (occupant != null
                        && occupant != defender) {
                    continue;
                }
                int distanceFromDefender =
                        manhattanDistance(
                                defender.getX(),
                                defender.getY(),
                                x,
                                y
                        );
                boolean isBetter =
                        distanceFromDefender
                        < bestDistanceFromDefender;
                boolean isEqualButCloserToBase =
                        distanceFromDefender
                        == bestDistanceFromDefender
                        && distanceFromBase
                        < bestDistanceFromBase;
                if (isBetter
                        || isEqualButCloserToBase) {
                    bestX = x;
                    bestY = y;
                    bestDistanceFromDefender =
                            distanceFromDefender;
                    bestDistanceFromBase =
                            distanceFromBase;
                }
            }
        }
        if (bestX == -1) {
            return null;
        }
        return new int[]{bestX, bestY};
    }
        private int manhattanDistance(
            Unit first,
            Unit second) {
        return manhattanDistance(
                first.getX(),
                first.getY(),
                second.getX(),
                second.getY()
        );
    }
        private int manhattanDistance(
            int x1,
            int y1,
            int x2,
            int y2) {
        return Math.abs(x1 - x2)
                + Math.abs(y1 - y2);
    }
    public void workersBehavior(List<Unit> workers, Player p, PhysicalGameState pgs) {
        int nbases = 0;
        int nbarracks = 0;
        int resourcesUsed = 0;
        List<Unit> freeWorkers = new LinkedList<>(workers);
        if (workers.isEmpty()) {
            return;
        }
        for (Unit u2 : pgs.getUnits()) {
            if (u2.getType() == baseType
                    && u2.getPlayer() == p.getID()) {
                nbases++;
            }
            if (u2.getType() == barracksType
                    && u2.getPlayer() == p.getID()) {
                nbarracks++;
            }
        }
        List<Integer> reservedPositions = new LinkedList<>();
        if (nbases == 0 && !freeWorkers.isEmpty()) {
            // build a base:
            if (p.getResources() >= baseType.cost + resourcesUsed) {
                Unit u = freeWorkers.remove(0);
                buildIfNotAlreadyBuilding(u,baseType,u.getX(),u.getY(),reservedPositions,p,pgs);
                resourcesUsed += baseType.cost;
            }
        }
        if (nbarracks == 0) {
            // build a barracks:
            if (p.getResources() >= barracksType.cost + resourcesUsed && !freeWorkers.isEmpty()) {
                Unit u = freeWorkers.remove(0);
                buildIfNotAlreadyBuilding(u,barracksType,u.getX(),u.getY(),reservedPositions,p,pgs);
                resourcesUsed += barracksType.cost;
            }
        }
        // harvest with all the free workers:
        for (Unit u : freeWorkers) {
            Unit closestBase = null;
            Unit closestResource = null;
            int closestDistance = 0;
            for (Unit u2 : pgs.getUnits()) {
                if (u2.getType().isResource) {
                    int d = Math.abs(u2.getX() - u.getX()) + Math.abs(u2.getY() - u.getY());
                    if (closestResource == null || d < closestDistance) {
                        closestResource = u2;
                        closestDistance = d;
                    }
                }
            }
            closestDistance = 0;
            for (Unit u2 : pgs.getUnits()) {
                if (u2.getType().isStockpile && u2.getPlayer()==p.getID()) {
                    int d = Math.abs(u2.getX() - u.getX()) + Math.abs(u2.getY() - u.getY());
                    if (closestBase == null || d < closestDistance) {
                        closestBase = u2;
                        closestDistance = d;
                    }
                }
            }
            if (closestResource != null && closestBase != null) {
                AbstractAction aa = getAbstractAction(u);
                if (aa instanceof Harvest) {
                    Harvest h_aa = (Harvest)aa;
                    if (h_aa.getTarget() != closestResource || h_aa.getBase()!=closestBase) harvest(u, closestResource, closestBase);
                } else {
                    harvest(u, closestResource, closestBase);
                }
            }
        }
    }
    
    @Override
    public List<ParameterSpecification> getParameters()
    {
        List<ParameterSpecification> parameters = new ArrayList<>();
        
        parameters.add(new ParameterSpecification("PathFinding", PathFinding.class, new AStarPathFinding()));
        return parameters;
    }    
    
}
