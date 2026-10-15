package local.cobblemonteamadvisor;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

final class BattleDamageTest {
    static void run() {
        var neutral = user("", "", "", 1);
        var acrobatics = move("acrobatics", "flying", 55, false, "contact");
        check(BattleDamageModifiers.power(neutral, neutral, acrobatics) == 110, "Item-free Acrobatics must double its base power");
        check(BattleDamageModifiers.power(user("", "aguavberry", "", 1), neutral, acrobatics) == 55, "Holding a berry must prevent the Acrobatics boost");
        check(BattleDamageModifiers.power(user("", "", "", 0.03), neutral, move("flail", "normal", 0, false)) == 200,
                "Flail must use its actual low-health power instead of a generic variable-power fallback");
        check(BattleDamageModifiers.powerFactor(user("technician", "", "", 1), move("acrobatics", "flying", 110, false, "contact")) == 1,
                "Technician must test effective power and not boost item-free Acrobatics again");
        check(BattleDamageModifiers.powerFactor(user("technician", "", "", 1), move("feint", "normal", 30, false)) == 1.5, "Technician must boost eligible low-power moves");
        check(BattleDamageModifiers.powerFactor(user("toughclaws", "", "", 1), acrobatics) == 1.3, "Contact attacks must receive Tough Claws");
        check(BattleDamageModifiers.powerFactor(user("sheerforce", "", "", 1), move("flamethrower", "fire", 90, true, "secondary")) == 1.3,
                "Sheer Force must boost secondary-effect attacks");
        check(BattleDamageModifiers.powerFactor(user("sheerforce", "", "", 1), move("dragonpulse", "dragon", 85, true)) == 1,
                "Sheer Force must not boost attacks with no secondary effect");
        check(BattleDamageModifiers.powerFactor(user("blaze", "", "", 0.3), move("flamethrower", "fire", 90, true)) == 1.5, "Low-HP Blaze must affect the KO estimate");
        check(BattleDamageModifiers.type("normal", "aerilate").equals("flying"), "Ability-converted moves must use the converted weakness chart");
        check(BattleDamageModifiers.type("fire", "normalize").equals("normal"), "Normalize must change non-Normal attacks too");
        check(!BattleDamageModifiers.changesType("weatherball"), "Weather Ball must retain its weather-controlled typing");
        var sound = move("boomburst", "normal", 140, true, "sound");
        check(BattleDamageModifiers.immune("soundproof", sound, false), "Soundproof must block a high-power sound attack");
        check(!BattleDamageModifiers.immune("soundproof", sound, true), "Mold Breaker must bypass Soundproof");
        check(BattleDamageModifiers.defenceFactor(user("furcoat", "", "", 1), true, false) == 2, "Fur Coat must double physical defence");
        check(BattleDamageModifiers.defenceFactor(user("furcoat", "", "", 1), true, true) == 1, "Mold Breaker must bypass Fur Coat");
        check(BattleDamageModifiers.damageFactor(neutral, user("icescales", "", "", 1), sound, 1, false) == 0.5, "Ice Scales must halve special damage");
        check(BattleDamageModifiers.damageFactor(neutral, user("prismarmor", "", "", 1), sound, 2, true) == 0.75,
                "Prism Armor must preserve its super-effective reduction through Mold Breaker");
        check(BattleDamageModifiers.power(neutral, user("", "", "brn", 1), move("venoshock", "poison", 65, true)) == 65,
                "Venoshock must not double against a burned target");
        check(BattleDamageModifiers.power(neutral, user("", "", "tox", 1), move("venoshock", "poison", 65, true)) == 130,
                "Venoshock must double against a poisoned target");
        check(BattleMatchupPolicy.accuracy(100, 1, 0, 0.8) == 1, "Apply Hustle before the final accuracy cap");
        check(BattleMatchupPolicy.accuracy(70, 0, 0, 1.3) > 0.9, "Compound Eyes must improve actual KO probability");

        double certain = BattleMatchupPolicy.normalKoChance(110, 130, 100, 1);
        double highRoll = BattleMatchupPolicy.normalKoChance(85, 100, 100, 1);
        check(certain == 1 && highRoll == 1.0 / 16, "Normal-roll KO probability must distinguish overkill from a maximum-roll KO");
        check(BattleMatchupPolicy.normalKoChance(60, 80, 100, 1) == 0, "Random critical hits must not create a normal-hit KO chance");
        double guaranteedValue = BattleMatchupPolicy.moveValue(-1000, 0, 1.1, 1, certain, true, false);
        check(guaranteedValue > BattleMatchupPolicy.moveValue(1000, 0, 0.9, 1, highRoll, true, false),
                "A reliable finisher must outrank planning advantages for roll-dependent attacks");
        check(guaranteedValue > BattleMatchupPolicy.moveValue(1000, 0, 20, 0.5, 0.5, true, false),
                "An inaccurate overkill must not beat a reliable KO just because its damage is larger");
        check(BattleMatchupPolicy.moveValue(0, 0, 2.4, 0.5, 0.5, true, false)
                        > BattleMatchupPolicy.moveValue(100, 0, 0.85, 1, 0.0, true, false),
                "Zap Cannon's chance to finish must beat nonlethal Dragon Pulse when the target survives Dragon Pulse");
        check(BattleMatchupPolicy.moveValue(0, 0, 9, 1, 0.0, true, true)
                        > BattleMatchupPolicy.moveValue(0, 0, 5, 1, 0.0, true, true),
                "Raid damage must remain uncapped when both attacks exceed ordinary species HP");

        var lastPp = new BattlePlanner.Action("finish", 120, 1, 0, 1, BattlePlanner.Effect.NONE, false, false, true);
        var fullPp = new BattlePlanner.Action("finish", 120, 1, 0, 15, BattlePlanner.Effect.NONE, false, false, true);
        var own = new BattlePlanner.Combatant(100, 100, 100, 0, 0, "", List.of(lastPp));
        var foe = new BattlePlanner.Combatant(100, 100, 50, 0, 0, "", List.of(BattlePlanner.Action.attack("reply", 100, 0)));
        check(BattlePlanner.evaluate(own, foe, lastPp, false, false).value() == BattlePlanner.evaluate(own, foe, fullPp, false, false).value(),
                "Spending the last PP for an immediate KO must have no conservation penalty");
        var gamble = new BattlePlanner.Action("gamble", 1000, 0.5, 0, 10, BattlePlanner.Effect.NONE, false, false, true);
        check(BattlePlanner.evaluate(own, foe, gamble, false, false).value() < BattlePlanner.evaluate(own, foe, lastPp, false, false).value(),
                "Expected overkill damage must not make a 50%-accurate attack stop the opponent with certainty");

        var protect = new BattlePlanner.Action("protect", 0, 1, 4, 10,
                new BattlePlanner.Effect(0, 0, 0, 1, 1, 1, 0, "", true, false), false, false, false);
        var protectedFoe = new BattlePlanner.Combatant(20, 100, 50, 0, 0, "", List.of(protect));
        var feint = new BattlePlanner.Action("feint", 30, 1, 2, 10, BattlePlanner.Effect.NONE, false, false, true, false, false, false, true);
        check(BattlePlanner.evaluate(own, protectedFoe, feint, false, false).next().isEmpty(), "Feint must be able to finish through Protect immediately");
        try (var stream = BattleDamageTest.class.getResourceAsStream("/battle-move-effects.json")) {
            var effects = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            check(effects.getAsJsonObject("feint").get("breaksProtect").getAsBoolean(), "Packaged metadata must describe Feint breaking Protect");
            check(effects.getAsJsonObject("flareblitz").get("contact").getAsBoolean(), "Packaged metadata must describe contact boosts");
            check(effects.getAsJsonObject("flamethrower").get("secondary").getAsBoolean(), "Packaged metadata must describe Sheer Force eligibility");
        } catch (IOException error) { throw new AssertionError(error); }
        System.out.println("Shared KO-first ranking, PP legality, hit chance and damage-modifier checks passed.");
    }

    private static BattleDamageModifiers.User user(String ability, String item, String status, double hp) {
        return new BattleDamageModifiers.User(ability, item, status, hp, "");
    }

    private static BattleDamageModifiers.Move move(String id, String type, double power, boolean special, String... flags) {
        return new BattleDamageModifiers.Move(id, type, type, power, special, Set.of(flags));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
